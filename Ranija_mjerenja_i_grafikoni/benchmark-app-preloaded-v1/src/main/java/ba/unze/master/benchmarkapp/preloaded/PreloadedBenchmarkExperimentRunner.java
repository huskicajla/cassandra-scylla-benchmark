package ba.unze.master.benchmarkapp.preloaded;

import ba.unze.master.benchmarkapp.benchmark.BenchmarkMetrics;
import ba.unze.master.benchmarkapp.benchmark.BenchmarkProgressReporter;
import ba.unze.master.benchmarkapp.benchmark.BenchmarkQualityGate;
import ba.unze.master.benchmarkapp.benchmark.BenchmarkResult;
import ba.unze.master.benchmarkapp.benchmark.BenchmarkResultWriter;
import ba.unze.master.benchmarkapp.benchmark.BenchmarkRunContext;
import ba.unze.master.benchmarkapp.benchmark.ExperimentContext;
import ba.unze.master.benchmarkapp.benchmark.ExperimentEventWriter;
import ba.unze.master.benchmarkapp.benchmark.ExperimentMetadataWriter;
import ba.unze.master.benchmarkapp.benchmark.ExperimentProtocolWriter;
import ba.unze.master.benchmarkapp.config.BenchmarkProperties;
import ba.unze.master.benchmarkapp.config.DatabaseProperties;
import ba.unze.master.benchmarkapp.config.DatasetProperties;
import ba.unze.master.benchmarkapp.data.CsvDatasetReader;
import ba.unze.master.benchmarkapp.data.TelemetryRecord;
import ba.unze.master.benchmarkapp.database.DatabaseClient;
import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.metadata.NodeState;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

@Component
public class PreloadedBenchmarkExperimentRunner {

    private static final DateTimeFormatter ID_TIME = DateTimeFormatter
            .ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final PreloadedTelemetryRepository repository;
    private final PreloadedBenchmarkProperties preloaded;
    private final CsvDatasetReader datasetReader;
    private final BenchmarkResultWriter resultWriter;
    private final BenchmarkQualityGate qualityGate;
    private final ExperimentMetadataWriter metadataWriter;
    private final ExperimentProtocolWriter protocolWriter;
    private final ExperimentEventWriter eventWriter;
    private final PreloadedStateWriter stateWriter;
    private final BaselineSeedFailureWriter seedFailureWriter;
    private final DatabaseClient databaseClient;
    private final DatabaseProperties database;
    private final DatasetProperties dataset;
    private final BenchmarkProperties benchmark;

    public PreloadedBenchmarkExperimentRunner(
            PreloadedTelemetryRepository repository,
            PreloadedBenchmarkProperties preloaded,
            CsvDatasetReader datasetReader,
            BenchmarkResultWriter resultWriter,
            BenchmarkQualityGate qualityGate,
            ExperimentMetadataWriter metadataWriter,
            ExperimentProtocolWriter protocolWriter,
            ExperimentEventWriter eventWriter,
            PreloadedStateWriter stateWriter,
            BaselineSeedFailureWriter seedFailureWriter,
            DatabaseClient databaseClient,
            DatabaseProperties database,
            DatasetProperties dataset,
            BenchmarkProperties benchmark
    ) {
        this.repository = repository;
        this.preloaded = preloaded;
        this.datasetReader = datasetReader;
        this.resultWriter = resultWriter;
        this.qualityGate = qualityGate;
        this.metadataWriter = metadataWriter;
        this.protocolWriter = protocolWriter;
        this.eventWriter = eventWriter;
        this.stateWriter = stateWriter;
        this.seedFailureWriter = seedFailureWriter;
        this.databaseClient = databaseClient;
        this.database = database;
        this.dataset = dataset;
        this.benchmark = benchmark;
    }

    public void runAllExperiments() {
        ensureBaseline();
        ExperimentContext experiment = experimentContext();
        metadataWriter.write(experiment);
        protocolWriter.write(experiment, benchmark);
        eventWriter.writeStarted(experiment, benchmark.getTestSeries());
        state(experiment, "BASELINE_ATTACHED", 0,
                "Persistent baseline was verified before measured workloads.");
        printHeader(experiment);

        try {
            runWriteSuite(experiment);
            List<TelemetryRecord> source = operationSource();
            runReadSuite(experiment, source);
            runMixedSuite(experiment, source);
            runSustainedSuite(experiment, source);
            eventWriter.writeCompleted(experiment, benchmark.getTestSeries());
        } catch (RuntimeException | Error failure) {
            eventWriter.writeAborted(experiment, benchmark.getTestSeries(), failure);
            throw failure;
        }
    }

    private void ensureBaseline() {
        if (preloaded.getBaselineSize() <= 0) {
            throw new IllegalArgumentException("Preloaded baseline size must be greater than zero.");
        }
        String baselineRunId = "BASELINE-" + ID_TIME.format(Instant.now()) + "-"
                + UUID.randomUUID().toString().substring(0, 8);
        if (preloaded.isForceReseed()) {
            repository.clearBaseline();
            state(baselineRunId, "BASELINE_RESET", 0,
                    "Explicit benchmark.preloaded.force-reseed=true.");
        }
        var existing = repository.findBaseline(preloaded.getBaselineId());
        if (existing.isPresent()) {
            if (existing.get().recordCount() != preloaded.getBaselineSize()) {
                throw new IllegalStateException("Existing baseline marker has "
                        + existing.get().recordCount() + " records, but profile requires "
                        + preloaded.getBaselineSize() + ". Set force-reseed=true only when a fresh baseline is intended.");
            }
            state(baselineRunId, "BASELINE_REUSED", 0,
                    "Seed skipped; marker prepared at " + existing.get().preparedAt() + ".");
            return;
        }

        ExperimentContext seedContext = new ExperimentContext(
                baselineRunId, Instant.now().toString(), database.getType().trim().toLowerCase(),
                database.getKeyspace(), database.getConsistencyLevel().trim().toUpperCase(),
                database.getReplicationFactor(), liveNodes(), dataset.getPath(),
                preloaded.getBaselineSize(), 0, preloaded.getSeedConcurrency(), 0, 0);
        BenchmarkRunContext seedRun = context(seedContext, "BASELINE_SEED", "ONE_TIME_SETUP",
                preloaded.getBaselineSize(), preloaded.getSeedConcurrency(), 100, 0, 0, 0, "SETUP", 1);
        BenchmarkMetrics metrics = new BenchmarkMetrics();
        BenchmarkProgressReporter progress = new BenchmarkProgressReporter(seedRun,
                preloaded.getBaselineSize(), benchmark.getProgressReportSeconds());
        ExecutorService executor = Executors.newFixedThreadPool(seedRun.concurrency());
        Semaphore inFlight = new Semaphore(seedRun.concurrency() * preloaded.getSeedInFlightPerWorker());
        AtomicLong index = new AtomicLong();
        AtomicLong transientFailures = new AtomicLong();
        System.out.println("Seeding one persistent baseline of " + preloaded.getBaselineSize()
                + " records. This setup is audited but excluded from measured latency.");
        state(baselineRunId, "BASELINE_SEED_STARTED", 0,
                "One-time seed started with concurrency=" + seedRun.concurrency()
                        + ", in_flight_per_worker=" + preloaded.getSeedInFlightPerWorker()
                        + ", retry_attempts=" + preloaded.getSeedRetryAttempts() + ".");
        metrics.start();
        try (Stream<TelemetryRecord> records = datasetReader.stream(preloaded.getBaselineSize())) {
            records.forEach(record -> {
                long current = index.getAndIncrement();
                acquire(inFlight, "seeding baseline");
                executor.submit(() -> seedOne(record, current, baselineRunId, metrics,
                        progress, inFlight, transientFailures));
            });
            shutdown(executor);
            metrics.stop();
            progress.finish();
            if (metrics.getFailedOperations() > 0 || metrics.getSuccessfulOperations() != preloaded.getBaselineSize()) {
                throw new IllegalStateException("Baseline seed invalid: attempted=" + metrics.getTotalOperations()
                        + ", successful=" + metrics.getSuccessfulOperations()
                        + ", failed=" + metrics.getFailedOperations());
            }
            repository.markBaselineReady(preloaded.getBaselineId(), preloaded.getBaselineSize(), dataset.getPath());
            state(baselineRunId, "BASELINE_SEEDED", 0,
                    "One-time setup complete; successful records=" + metrics.getSuccessfulOperations()
                            + "; recorded transient failures/retries=" + transientFailures.get() + ".");
        } catch (RuntimeException | Error failure) {
            if (!executor.isTerminated()) {
                shutdown(executor);
            }
            metrics.stop();
            progress.finish();
            state(baselineRunId, "BASELINE_SEED_FAILED", 0,
                    "attempted=" + metrics.getTotalOperations() + "; successful="
                            + metrics.getSuccessfulOperations() + "; failed=" + metrics.getFailedOperations()
                            + "; transient retries=" + transientFailures.get() + "; "
                            + failure.getClass().getName() + ": " + failure.getMessage());
            throw failure;
        }
    }

    private List<TelemetryRecord> operationSource() {
        int requested = Math.max(benchmark.getReadOperationCount(), benchmark.getMixedOperationCount());
        if (requested <= 0 || requested > preloaded.getBaselineSize()) {
            throw new IllegalArgumentException("Read/mixed operation count must be within the preloaded baseline size.");
        }
        return datasetReader.readRecords(requested);
    }

    private void seedOne(
            TelemetryRecord record,
            long index,
            String baselineRunId,
            BenchmarkMetrics metrics,
            BenchmarkProgressReporter progress,
            Semaphore inFlight,
            AtomicLong transientFailures
    ) {
        long started = System.nanoTime();
        int maxAttempts = Math.max(1, preloaded.getSeedRetryAttempts());
        try {
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    repository.insertBaselineAsync(record).toCompletableFuture().join();
                    metrics.recordSuccess(System.nanoTime() - started);
                    progress.recordSuccess();
                    return;
                } catch (Exception error) {
                    transientFailures.incrementAndGet();
                    seedFailureWriter.write(baselineRunId,
                            database.getType().trim().toLowerCase(),
                            preloaded.getBaselineId(), index, attempt, maxAttempts, error);
                    if (attempt == maxAttempts) {
                        metrics.recordFailure(System.nanoTime() - started, index, error);
                        progress.recordFailure();
                        return;
                    }
                    retryPause(attempt);
                }
            }
        } finally {
            inFlight.release();
        }
    }

    private void runWriteSuite(ExperimentContext experiment) {
        runWrite(experiment, "SEQUENTIAL_WRITE", benchmark.getSequentialWriteSize(), 1);
        runWrite(experiment, "CONCURRENT_WRITE", benchmark.getConcurrentWriteSize(), experiment.configuredConcurrency());
    }

    private void runWrite(ExperimentContext experiment, String operation, long operations, int concurrency) {
        for (int run = 1; run <= experiment.configuredWarmupRuns(); run++) {
            executeWrite(experiment, operation, operations, concurrency, "WARMUP", run);
        }
        for (int run = 1; run <= experiment.configuredMeasurementRuns(); run++) {
            executeWrite(experiment, operation, operations, concurrency, "MEASUREMENT", run);
        }
    }

    private void executeWrite(ExperimentContext experiment, String operation, long operations,
                              int concurrency, String runType, int runNumber) {
        BenchmarkRunContext run = context(experiment, operation, "WRITE_ONLY", operations,
                concurrency, 100, 0, 0, 0, runType, runNumber);
        resetWorkload(run, "Empty working table before write run.");
        BenchmarkMetrics metrics = new BenchmarkMetrics();
        BenchmarkProgressReporter progress = new BenchmarkProgressReporter(run, operations,
                benchmark.getProgressReportSeconds());
        System.out.println(operation + " / " + runType + " " + runNumber + " started: "
                + operations + " operations, concurrency=" + concurrency + ".");
        metrics.start();
        if (concurrency == 1) {
            long index = 0;
            try (Stream<TelemetryRecord> records = datasetReader.stream(operations)) {
                var iterator = records.iterator();
                while (iterator.hasNext()) {
                    TelemetryRecord record = iterator.next();
                    long started = System.nanoTime();
                    try {
                        repository.insertWorkloadAsync(unique(record, run, index)).toCompletableFuture().join();
                        metrics.recordSuccess(System.nanoTime() - started);
                        progress.recordSuccess();
                    } catch (Exception e) {
                        metrics.recordFailure(System.nanoTime() - started, index, e);
                        progress.recordFailure();
                    }
                    index++;
                }
            } finally {
                metrics.stop();
                progress.finish();
            }
        } else {
            ExecutorService executor = Executors.newFixedThreadPool(concurrency);
            Semaphore inFlight = new Semaphore(concurrency * 4);
            AtomicLong index = new AtomicLong();
            try (Stream<TelemetryRecord> records = datasetReader.stream(operations)) {
                records.forEach(record -> {
                    long current = index.getAndIncrement();
                    acquire(inFlight, "scheduling concurrent write");
                    executor.submit(() -> writeOne(record, run, current, metrics, progress, inFlight));
                });
            } finally {
                shutdown(executor);
                metrics.stop();
                progress.finish();
            }
        }
        finish(run, metrics);
    }

    private void runReadSuite(ExperimentContext experiment, List<TelemetryRecord> source) {
        runRead(experiment, source, "READ_SINGLE");
        runRead(experiment, source, "READ_LATEST");
        runRead(experiment, source, "READ_PARTITION_WINDOW");
        runRead(experiment, source, "READ_TIME_RANGE");
    }

    private void runRead(ExperimentContext experiment, List<TelemetryRecord> source, String operation) {
        for (int run = 1; run <= experiment.configuredWarmupRuns(); run++) {
            executeRead(experiment, sample(source, benchmark.getReadOperationCount(), operation, "WARMUP", run),
                    operation, "WARMUP", run);
        }
        for (int run = 1; run <= experiment.configuredMeasurementRuns(); run++) {
            executeRead(experiment, sample(source, benchmark.getReadOperationCount(), operation, "MEASUREMENT", run),
                    operation, "MEASUREMENT", run);
        }
    }

    private void executeRead(ExperimentContext experiment, List<TelemetryRecord> records,
                             String operation, String runType, int runNumber) {
        BenchmarkRunContext run = context(experiment, operation, "READ_ONLY", records.size(),
                experiment.configuredConcurrency(), 0, 100, 0, 0, runType, runNumber);
        Instant min = records.stream().map(TelemetryRecord::eventTime).min(Instant::compareTo).orElseThrow();
        Instant max = records.stream().map(TelemetryRecord::eventTime).max(Instant::compareTo).orElseThrow();
        runConcurrent(records, run, (record, ignored) -> switch (operation) {
            case "READ_SINGLE" -> repository.readSingleAsync(record.deviceId(), record.eventTime(), record.id());
            case "READ_LATEST" -> repository.readLatestAsync(record.deviceId());
            case "READ_PARTITION_WINDOW" -> repository.readPartitionAsync(record.deviceId());
            case "READ_TIME_RANGE" -> repository.readTimeRangeAsync(record.deviceId(), min, max);
            default -> throw new IllegalArgumentException("Unsupported read operation: " + operation);
        });
    }

    private void runMixedSuite(ExperimentContext experiment, List<TelemetryRecord> source) {
        runMixed(experiment, source, "WRITE_HEAVY", 80);
        runMixed(experiment, source, "BALANCED", 50);
        runMixed(experiment, source, "READ_HEAVY", 20);
    }

    private void runMixed(ExperimentContext experiment, List<TelemetryRecord> source, String profile, int writePercentage) {
        for (int run = 1; run <= experiment.configuredWarmupRuns(); run++) {
            executeMixed(experiment, sample(source, benchmark.getMixedOperationCount(), profile, "WARMUP", run),
                    profile, writePercentage, "WARMUP", run);
        }
        for (int run = 1; run <= experiment.configuredMeasurementRuns(); run++) {
            executeMixed(experiment, sample(source, benchmark.getMixedOperationCount(), profile, "MEASUREMENT", run),
                    profile, writePercentage, "MEASUREMENT", run);
        }
    }

    private void executeMixed(ExperimentContext experiment, List<TelemetryRecord> records, String profile,
                              int writePercentage, String runType, int runNumber) {
        BenchmarkRunContext run = context(experiment, "MIXED_WORKLOAD", profile, records.size(),
                experiment.configuredConcurrency(), writePercentage, 100 - writePercentage,
                0, 0, runType, runNumber);
        resetWorkload(run, "Empty working table before mixed " + profile + " run.");
        Instant min = records.stream().map(TelemetryRecord::eventTime).min(Instant::compareTo).orElseThrow();
        Instant max = records.stream().map(TelemetryRecord::eventTime).max(Instant::compareTo).orElseThrow();
        runConcurrent(records, run, (record, index) -> {
            boolean write = Math.floorMod(index * 37 + profile.hashCode() + runNumber, 100) < writePercentage;
            if (write) {
                return repository.insertWorkloadAsync(unique(record, run, index));
            }
            return repository.readTimeRangeAsync(record.deviceId(), min, max);
        });
    }

    private void runSustainedSuite(ExperimentContext experiment, List<TelemetryRecord> source) {
        long planned = Math.multiplyExact(benchmark.getSustainedDurationSeconds(), benchmark.getSustainedTargetOpsPerSecond());
        BenchmarkRunContext run = context(experiment, "SUSTAINED_INGESTION", "SUSTAINED_WRITE", planned,
                experiment.configuredConcurrency(), 100, 0, benchmark.getSustainedTargetOpsPerSecond(),
                benchmark.getSustainedDurationSeconds(), "MEASUREMENT", 1);
        resetWorkload(run, "Empty working table before sustained ingestion.");
        BenchmarkMetrics metrics = new BenchmarkMetrics();
        BenchmarkProgressReporter progress = new BenchmarkProgressReporter(run, planned, benchmark.getProgressReportSeconds());
        ExecutorService executor = Executors.newFixedThreadPool(run.concurrency());
        Semaphore inFlight = new Semaphore(run.concurrency() * 4);
        long interval = 1_000_000_000L / Math.max(benchmark.getSustainedTargetOpsPerSecond(), 1);
        long deadline = System.nanoTime();
        long end = deadline + TimeUnit.SECONDS.toNanos(benchmark.getSustainedDurationSeconds());
        long sequence = 0;
        metrics.start();
        try {
            while (System.nanoTime() < end) {
                sleepUntil(deadline);
                acquire(inFlight, "scheduling sustained write");
                long current = sequence++;
                TelemetryRecord record = source.get((int) (current % source.size()));
                long started = System.nanoTime();
                executor.submit(() -> {
                    try {
                        repository.insertWorkloadAsync(unique(record, run, current)).toCompletableFuture().join();
                        metrics.recordSuccess(System.nanoTime() - started);
                        progress.recordSuccess();
                    } catch (Exception e) {
                        metrics.recordFailure(System.nanoTime() - started, current, e);
                        progress.recordFailure();
                    } finally {
                        inFlight.release();
                    }
                });
                deadline += interval;
            }
        } finally {
            shutdown(executor);
            metrics.stop();
            progress.finish();
        }
        finish(run, metrics);
    }

    private void runConcurrent(List<TelemetryRecord> records, BenchmarkRunContext run, Operation operation) {
        BenchmarkMetrics metrics = new BenchmarkMetrics();
        BenchmarkProgressReporter progress = new BenchmarkProgressReporter(run, records.size(), benchmark.getProgressReportSeconds());
        ExecutorService executor = Executors.newFixedThreadPool(run.concurrency());
        AtomicInteger index = new AtomicInteger();
        metrics.start();
        for (int worker = 0; worker < run.concurrency(); worker++) {
            executor.submit(() -> {
                while (true) {
                    int current = index.getAndIncrement();
                    if (current >= records.size()) {
                        return;
                    }
                    long started = System.nanoTime();
                    try {
                        AsyncResultSet result = operation.execute(records.get(current), current).toCompletableFuture().join();
                        for (var row : result.currentPage()) { /* materialize first response page */ }
                        metrics.recordSuccess(System.nanoTime() - started);
                        progress.recordSuccess();
                    } catch (Exception e) {
                        metrics.recordFailure(System.nanoTime() - started, current, e);
                        progress.recordFailure();
                    }
                }
            });
        }
        shutdown(executor);
        metrics.stop();
        progress.finish();
        finish(run, metrics);
    }

    private void writeOne(TelemetryRecord source, BenchmarkRunContext run, long index,
                          BenchmarkMetrics metrics, BenchmarkProgressReporter progress, Semaphore inFlight) {
        long started = System.nanoTime();
        try {
            repository.insertWorkloadAsync(unique(source, run, index)).toCompletableFuture().join();
            metrics.recordSuccess(System.nanoTime() - started);
            progress.recordSuccess();
        } catch (Exception e) {
            metrics.recordFailure(System.nanoTime() - started, index, e);
            progress.recordFailure();
        } finally {
            inFlight.release();
        }
    }

    private void resetWorkload(BenchmarkRunContext run, String detail) {
        repository.clearWorkload();
        state(run.experiment(), "WORKLOAD_TABLE_RESET", run.requestedDatasetSize(), detail
                + " operation=" + run.operation() + "; profile=" + run.workloadProfile()
                + "; run=" + run.runType() + " " + run.runNumber() + ".");
    }

    private void finish(BenchmarkRunContext run, BenchmarkMetrics metrics) {
        metrics.printResults(run.operation() + "_" + run.workloadProfile(),
                (int) Math.min(Integer.MAX_VALUE, run.requestedDatasetSize()), run.concurrency());
        BenchmarkResult result = BenchmarkResult.from(run, metrics);
        resultWriter.write(result, metrics.getFailures());
        qualityGate.requireSuccessfulOperations(result);
        cooldownBetweenRuns();
    }

    private List<TelemetryRecord> sample(List<TelemetryRecord> population, int count,
                                         String label, String runType, int runNumber) {
        if (count <= 0 || count > population.size()) {
            throw new IllegalArgumentException("Requested operation sample is outside the loaded source population.");
        }
        if (count == population.size()) {
            return population;
        }
        int stride = population.size() / count;
        int offset = Math.floorMod((label + ":" + runType + ":" + runNumber).hashCode(), stride);
        List<TelemetryRecord> selected = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            selected.add(population.get(offset + index * stride));
        }
        return selected;
    }

    private TelemetryRecord unique(TelemetryRecord source, BenchmarkRunContext run, long sequence) {
        String token = run.experiment().experimentId() + "|" + run.operation() + "|"
                + run.workloadProfile() + "|" + run.runType() + "|" + run.runNumber()
                + "|" + source.id() + "|" + sequence;
        return new TelemetryRecord(source.deviceId(), source.eventTime().plusMillis(sequence % 1_000_000L),
                UUID.nameUUIDFromBytes(token.getBytes(StandardCharsets.UTF_8)), source.metricType(), source.value());
    }

    private ExperimentContext experimentContext() {
        Instant now = Instant.now();
        return new ExperimentContext("EXP-" + ID_TIME.format(now) + "-" + UUID.randomUUID().toString().substring(0, 8),
                now.toString(), database.getType().trim().toLowerCase(), database.getKeyspace(),
                database.getConsistencyLevel().trim().toUpperCase(), database.getReplicationFactor(), liveNodes(),
                dataset.getPath(), preloaded.getBaselineSize(), Math.toIntExact(preloaded.getBaselineSize()), benchmark.getConcurrency(),
                benchmark.getWarmupRuns(), benchmark.getMeasurementRuns());
    }

    private int liveNodes() {
        return (int) databaseClient.getSession().getMetadata().getNodes().values().stream()
                .filter(node -> node.getState() == NodeState.UP).count();
    }

    private BenchmarkRunContext context(ExperimentContext experiment, String operation, String profile,
                                        long requested, int concurrency, int writePercent, int readPercent,
                                        int targetRate, int duration, String runType, int runNumber) {
        return new BenchmarkRunContext(experiment, operation, profile, requested, concurrency,
                writePercent, readPercent, targetRate, duration, runType, runNumber);
    }

    private void state(ExperimentContext experiment, String event, long workloadOperations, String detail) {
        stateWriter.write(experiment.experimentId(), experiment.database(), preloaded.getBaselineId(), event,
                repository.baselineTable(), repository.workloadTable(), preloaded.getBaselineSize(), workloadOperations, detail);
    }

    private void state(String id, String event, long workloadOperations, String detail) {
        stateWriter.write(id, database.getType().trim().toLowerCase(), preloaded.getBaselineId(), event,
                repository.baselineTable(), repository.workloadTable(), preloaded.getBaselineSize(), workloadOperations, detail);
    }

    private void acquire(Semaphore semaphore, String action) {
        try {
            semaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Benchmark interrupted while " + action + ".", e);
        }
    }

    private void shutdown(ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.MINUTES)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(1, TimeUnit.MINUTES)) {
                    throw new IllegalStateException("Benchmark executor did not terminate.");
                }
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Benchmark interrupted.", e);
        }
    }

    private void sleepUntil(long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            return;
        }
        try {
            TimeUnit.NANOSECONDS.sleep(remaining);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Sustained benchmark interrupted.", e);
        }
    }

    private void retryPause(int attempt) {
        long pause = (long) Math.max(0, preloaded.getSeedRetryDelayMillis()) * attempt;
        if (pause == 0) {
            return;
        }
        try {
            TimeUnit.MILLISECONDS.sleep(pause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Baseline seed retry interrupted.", e);
        }
    }

    private void cooldownBetweenRuns() {
        int seconds = benchmark.getInterRunCooldownSeconds();
        if (seconds <= 0) {
            return;
        }
        System.out.println("Recovery cooldown: " + seconds
                + " seconds (outside benchmark timing).");
        try {
            TimeUnit.SECONDS.sleep(seconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Benchmark cooldown interrupted.", e);
        }
    }

    private void printHeader(ExperimentContext experiment) {
        System.out.println("Preloaded benchmark experiment " + experiment.experimentId());
        System.out.println("Database=" + experiment.database() + ", nodes=" + experiment.clusterNodeCount()
                + ", baseline=" + preloaded.getBaselineSize() + ", read table=" + repository.baselineTable()
                + ", write table=" + repository.workloadTable());
    }

    @FunctionalInterface
    private interface Operation {
        CompletionStage<AsyncResultSet> execute(TelemetryRecord record, int index);
    }
}
