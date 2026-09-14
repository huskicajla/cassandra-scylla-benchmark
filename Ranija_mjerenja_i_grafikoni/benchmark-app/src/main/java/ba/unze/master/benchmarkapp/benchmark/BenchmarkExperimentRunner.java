package ba.unze.master.benchmarkapp.benchmark;

import ba.unze.master.benchmarkapp.config.BenchmarkProperties;
import ba.unze.master.benchmarkapp.config.DatabaseProperties;
import ba.unze.master.benchmarkapp.config.DatasetProperties;
import ba.unze.master.benchmarkapp.data.DatasetLoader;
import ba.unze.master.benchmarkapp.data.TelemetryRecord;
import ba.unze.master.benchmarkapp.database.DatabaseClient;
import ba.unze.master.benchmarkapp.database.TelemetryRepository;
import com.datastax.oss.driver.api.core.metadata.NodeState;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class BenchmarkExperimentRunner {

    private static final DateTimeFormatter EXPERIMENT_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                    .withZone(ZoneOffset.UTC);

    private final BenchmarkRunner benchmarkRunner;
    private final ReadBenchmarkRunner readBenchmarkRunner;
    private final DatasetLoader datasetLoader;
    private final TelemetryRepository repository;
    private final BenchmarkResultWriter resultWriter;
    private final BenchmarkQualityGate qualityGate;
    private final ExperimentMetadataWriter metadataWriter;
    private final ExperimentProtocolWriter protocolWriter;
    private final ExperimentEventWriter eventWriter;
    private final DatabaseClient databaseClient;
    private final DatabaseProperties databaseProperties;
    private final DatasetProperties datasetProperties;
    private final BenchmarkProperties benchmarkProperties;

    public BenchmarkExperimentRunner(
            BenchmarkRunner benchmarkRunner,
            ReadBenchmarkRunner readBenchmarkRunner,
            DatasetLoader datasetLoader,
            TelemetryRepository repository,
            BenchmarkResultWriter resultWriter,
            BenchmarkQualityGate qualityGate,
            ExperimentMetadataWriter metadataWriter,
            ExperimentProtocolWriter protocolWriter,
            ExperimentEventWriter eventWriter,
            DatabaseClient databaseClient,
            DatabaseProperties databaseProperties,
            DatasetProperties datasetProperties,
            BenchmarkProperties benchmarkProperties
    ) {
        this.benchmarkRunner = benchmarkRunner;
        this.readBenchmarkRunner = readBenchmarkRunner;
        this.datasetLoader = datasetLoader;
        this.repository = repository;
        this.resultWriter = resultWriter;
        this.qualityGate = qualityGate;
        this.metadataWriter = metadataWriter;
        this.protocolWriter = protocolWriter;
        this.eventWriter = eventWriter;
        this.databaseClient = databaseClient;
        this.databaseProperties = databaseProperties;
        this.datasetProperties = datasetProperties;
        this.benchmarkProperties = benchmarkProperties;
    }

    public void runAllExperiments() {

        ExperimentContext experiment = createExperimentContext();
        metadataWriter.write(experiment);
        protocolWriter.write(experiment, benchmarkProperties);
        eventWriter.writeStarted(experiment, benchmarkProperties.getTestSeries());

        printExperimentHeader(experiment);

        try {
            runWriteExperiments(experiment);

            List<TelemetryRecord> readPopulation = datasetLoader.loadForRead(
                    benchmarkProperties.getReadDatasetSize()
            );

            runReadExperiments(experiment, readPopulation);
            runMixedWorkloads(experiment, readPopulation);
            runSustainedIngestion(experiment, readPopulation);
            eventWriter.writeCompleted(experiment, benchmarkProperties.getTestSeries());

        } catch (RuntimeException | Error failure) {
            eventWriter.writeAborted(
                    experiment,
                    benchmarkProperties.getTestSeries(),
                    failure
            );
            throw failure;
        }
    }

    private ExperimentContext createExperimentContext() {

        Instant startedAt = Instant.now();

        String experimentId = "EXP-"
                + EXPERIMENT_TIME_FORMAT.format(startedAt)
                + "-"
                + UUID.randomUUID().toString().substring(0, 8);

        return new ExperimentContext(
                experimentId,
                startedAt.toString(),
                databaseProperties.getType().trim().toLowerCase(),
                databaseProperties.getKeyspace(),
                databaseProperties.getConsistencyLevel().trim().toUpperCase(),
                databaseProperties.getReplicationFactor(),
                (int) databaseClient.getSession().getMetadata().getNodes().values()
                        .stream()
                        .filter(node -> node.getState() == NodeState.UP)
                        .count(),
                datasetProperties.getPath(),
                datasetProperties.getSize(),
                benchmarkProperties.getReadDatasetSize(),
                benchmarkProperties.getConcurrency(),
                benchmarkProperties.getWarmupRuns(),
                benchmarkProperties.getMeasurementRuns()
        );
    }

    private void printExperimentHeader(ExperimentContext experiment) {
        System.out.println();
        System.out.println("========================================");
        System.out.println("BENCHMARK EXPERIMENT");
        System.out.println("Experiment ID: " + experiment.experimentId());
        System.out.println("Test series: " + benchmarkProperties.getTestSeries());
        System.out.println("Database: " + experiment.database());
        System.out.println("Cluster nodes: " + experiment.clusterNodeCount());
        System.out.println("Consistency level: " + experiment.consistencyLevel());
        System.out.println("Replication factor: " + experiment.replicationFactor());
        System.out.println("Sequential write operations: "
                + benchmarkProperties.getSequentialWriteSize());
        System.out.println("Concurrent write operations: "
                + benchmarkProperties.getConcurrentWriteSize());
        System.out.println("Read population / operations per run: "
                + benchmarkProperties.getReadDatasetSize() + " / "
                + benchmarkProperties.getReadOperationCount());
        System.out.println("Mixed operations per run: "
                + benchmarkProperties.getMixedOperationCount());
        System.out.println("========================================");
    }

    private void runWriteExperiments(ExperimentContext experiment) {
        runSequential(experiment);
        runConcurrent(experiment);
    }

    private void runSequential(ExperimentContext experiment) {
        for (int run = 1; run <= experiment.configuredWarmupRuns(); run++) {
            repository.clearData();
            benchmarkRunner.runSequentialWrite(runContext(
                    experiment,
                    "SEQUENTIAL_WRITE",
                    "WRITE_ONLY",
                    benchmarkProperties.getSequentialWriteSize(),
                    1,
                    100,
                    0,
                    0,
                    0,
                    "WARMUP",
                    run
            ));
        }

        for (int run = 1; run <= experiment.configuredMeasurementRuns(); run++) {
            repository.clearData();
            benchmarkRunner.runSequentialWrite(runContext(
                    experiment,
                    "SEQUENTIAL_WRITE",
                    "WRITE_ONLY",
                    benchmarkProperties.getSequentialWriteSize(),
                    1,
                    100,
                    0,
                    0,
                    0,
                    "MEASUREMENT",
                    run
            ));
        }
    }

    private void runConcurrent(ExperimentContext experiment) {
        for (int run = 1; run <= experiment.configuredWarmupRuns(); run++) {
            repository.clearData();
            benchmarkRunner.runConcurrentWrite(runContext(
                    experiment,
                    "CONCURRENT_WRITE",
                    "WRITE_ONLY",
                    benchmarkProperties.getConcurrentWriteSize(),
                    experiment.configuredConcurrency(),
                    100,
                    0,
                    0,
                    0,
                    "WARMUP",
                    run
            ));
        }

        for (int run = 1; run <= experiment.configuredMeasurementRuns(); run++) {
            repository.clearData();
            benchmarkRunner.runConcurrentWrite(runContext(
                    experiment,
                    "CONCURRENT_WRITE",
                    "WRITE_ONLY",
                    benchmarkProperties.getConcurrentWriteSize(),
                    experiment.configuredConcurrency(),
                    100,
                    0,
                    0,
                    0,
                    "MEASUREMENT",
                    run
            ));
        }
    }

    private void runReadExperiments(
            ExperimentContext experiment,
            List<TelemetryRecord> population
    ) {
        preloadReadData(experiment, population, "READ_SUITE", "SETUP", 1);
        runRead(experiment, population, "READ_SINGLE");
        runRead(experiment, population, "READ_LATEST");
        runRead(experiment, population, "READ_PARTITION_WINDOW");
        runRead(experiment, population, "READ_TIME_RANGE");
    }

    private void runRead(
            ExperimentContext experiment,
            List<TelemetryRecord> population,
            String operation
    ) {
        for (int run = 1; run <= experiment.configuredWarmupRuns(); run++) {
            executeRead(
                    experiment,
                    operationSample(population,
                            benchmarkProperties.getReadOperationCount(),
                            operation, "WARMUP", run),
                    operation,
                    "WARMUP",
                    run
            );
        }

        for (int run = 1; run <= experiment.configuredMeasurementRuns(); run++) {
            executeRead(
                    experiment,
                    operationSample(population,
                            benchmarkProperties.getReadOperationCount(),
                            operation, "MEASUREMENT", run),
                    operation,
                    "MEASUREMENT",
                    run
            );
        }
    }

    private void executeRead(
            ExperimentContext experiment,
            List<TelemetryRecord> records,
            String operation,
            String runType,
            int runNumber
    ) {
        BenchmarkRunContext context = runContext(
                experiment,
                operation,
                "READ_ONLY",
                records.size(),
                experiment.configuredConcurrency(),
                0,
                100,
                0,
                0,
                runType,
                runNumber
        );

        switch (operation) {
            case "READ_SINGLE" ->
                    readBenchmarkRunner.runReadSingle(records, context);
            case "READ_LATEST" ->
                    readBenchmarkRunner.runReadLatest(records, context);
            case "READ_PARTITION_WINDOW" ->
                    readBenchmarkRunner.runReadPartition(records, context);
            case "READ_TIME_RANGE" ->
                    readBenchmarkRunner.runReadTimeRange(records, context);
            default -> throw new IllegalArgumentException(
                    "Unsupported read operation: " + operation
            );
        }
    }

    private List<TelemetryRecord> operationSample(
            List<TelemetryRecord> population,
            int requestedOperations,
            String operation,
            String runType,
            int runNumber
    ) {
        if (requestedOperations <= 0) {
            throw new IllegalArgumentException(
                    "Operation count must be greater than zero."
            );
        }
        if (requestedOperations > population.size()) {
            throw new IllegalArgumentException(
                    "Operation count " + requestedOperations
                            + " exceeds loaded population "
                            + population.size() + "."
            );
        }
        if (requestedOperations == population.size()) {
            return population;
        }

        int stride = population.size() / requestedOperations;
        int offset = Math.floorMod(
                (operation + ":" + runType + ":" + runNumber).hashCode(),
                stride
        );
        List<TelemetryRecord> sample = new ArrayList<>(requestedOperations);

        for (int index = 0; index < requestedOperations; index++) {
            sample.add(population.get(offset + index * stride));
        }

        return sample;
    }

    private void preloadReadData(
            ExperimentContext experiment,
            List<TelemetryRecord> records,
            String targetOperation,
            String runType,
            int runNumber
    ) {
        repository.clearData();

        BenchmarkRunContext context = runContext(
                experiment,
                "DATA_PRELOAD",
                "SETUP_FOR_" + targetOperation,
                records.size(),
                experiment.configuredConcurrency(),
                100,
                0,
                0,
                0,
                runType,
                runNumber
        );

        BenchmarkMetrics metrics = new BenchmarkMetrics();
        BenchmarkProgressReporter progress = new BenchmarkProgressReporter(
                context,
                records.size(),
                benchmarkProperties.getProgressReportSeconds()
        );
        ExecutorService executor = Executors.newFixedThreadPool(
                context.concurrency()
        );
        Semaphore inFlight = new Semaphore(context.concurrency() * 4);
        AtomicInteger index = new AtomicInteger();

        metrics.start();

        try {
            for (TelemetryRecord record : records) {
                int currentIndex = index.getAndIncrement();
                acquire(inFlight, "preloading read data");

                executor.submit(() -> {
                    long start = System.nanoTime();

                    try {
                        repository.insertAsync(record)
                                .toCompletableFuture()
                                .join();
                        metrics.recordSuccess(System.nanoTime() - start);
                        progress.recordSuccess();
                    } catch (Exception e) {
                        metrics.recordFailure(
                                System.nanoTime() - start,
                                currentIndex,
                                e
                        );
                        progress.recordFailure();
                    } finally {
                        inFlight.release();
                    }
                });
            }
        } finally {
            shutdownExecutor(executor);
            metrics.stop();
            progress.finish();
        }

        BenchmarkResult result = BenchmarkResult.from(context, metrics);
        resultWriter.write(result, metrics.getFailures());

        if (result.failedOperations() > 0) {
            throw new IllegalStateException(
                    "Read-data preload failed for " + targetOperation
                            + ". The corresponding read result is invalid."
            );
        }
    }

    private void runMixedWorkloads(
            ExperimentContext experiment,
            List<TelemetryRecord> population
    ) {
        runMixed(experiment, population, "WRITE_HEAVY", 80);
        runMixed(experiment, population, "BALANCED", 50);
        runMixed(experiment, population, "READ_HEAVY", 20);
    }

    private void runMixed(
            ExperimentContext experiment,
            List<TelemetryRecord> population,
            String workload,
            int writePercentage
    ) {
        preloadReadData(experiment, population, "MIXED_" + workload,
                "SETUP", 1);

        for (int run = 1; run <= experiment.configuredWarmupRuns(); run++) {
            executeMixed(
                    experiment,
                    operationSample(population,
                            benchmarkProperties.getMixedOperationCount(),
                            workload, "WARMUP", run),
                    workload,
                    writePercentage,
                    "WARMUP",
                    run
            );
        }

        for (int run = 1; run <= experiment.configuredMeasurementRuns(); run++) {
            executeMixed(
                    experiment,
                    operationSample(population,
                            benchmarkProperties.getMixedOperationCount(),
                            workload, "MEASUREMENT", run),
                    workload,
                    writePercentage,
                    "MEASUREMENT",
                    run
            );
        }
    }

    private void executeMixed(
            ExperimentContext experiment,
            List<TelemetryRecord> records,
            String workload,
            int writePercentage,
            String runType,
            int runNumber
    ) {
        BenchmarkRunContext context = runContext(
                experiment,
                "MIXED_WORKLOAD",
                workload,
                records.size(),
                experiment.configuredConcurrency(),
                writePercentage,
                100 - writePercentage,
                0,
                0,
                runType,
                runNumber
        );

        BenchmarkMetrics metrics = new BenchmarkMetrics();
        BenchmarkProgressReporter progress = new BenchmarkProgressReporter(
                context,
                records.size(),
                benchmarkProperties.getProgressReportSeconds()
        );
        ExecutorService executor = Executors.newFixedThreadPool(
                context.concurrency()
        );
        AtomicInteger index = new AtomicInteger();

        printMixedHeader(context);
        metrics.start();

        for (int worker = 0; worker < context.concurrency(); worker++) {
            executor.submit(() -> {
                ThreadLocalRandom random = ThreadLocalRandom.current();

                while (true) {
                    int currentIndex = index.getAndIncrement();

                    if (currentIndex >= records.size()) {
                        break;
                    }

                    TelemetryRecord record = records.get(currentIndex);
                    boolean write = random.nextInt(100)
                            < context.writePercentage();
                    long start = System.nanoTime();

                    try {
                        if (write) {
                            repository.insertAsync(BenchmarkRunner.uniqueWriteRecord(
                                    record,
                                    currentIndex
                            )).toCompletableFuture().join();
                        } else {
                            repository.readLatestAsync(record.deviceId())
                                    .toCompletableFuture()
                                    .join();
                        }

                        metrics.recordSuccess(System.nanoTime() - start);
                        progress.recordSuccess();

                    } catch (Exception e) {
                        metrics.recordFailure(
                                System.nanoTime() - start,
                                currentIndex,
                                e
                        );
                        progress.recordFailure();
                    }
                }
            });
        }

        shutdownExecutor(executor);
        metrics.stop();
        progress.finish();

        metrics.printResults(
                context.operation() + "_" + context.workloadProfile(),
                records.size(),
                context.concurrency()
        );

        BenchmarkResult result = BenchmarkResult.from(context, metrics);
        resultWriter.write(result, metrics.getFailures());
        qualityGate.requireSuccessfulOperations(result);
    }

    private void runSustainedIngestion(
            ExperimentContext experiment,
            List<TelemetryRecord> records
    ) {
        repository.clearData();

        long plannedOperations = (long) benchmarkProperties
                .getSustainedTargetOpsPerSecond()
                * benchmarkProperties.getSustainedDurationSeconds();

        BenchmarkRunContext context = runContext(
                experiment,
                "SUSTAINED_INGESTION",
                "SUSTAINED_WRITE",
                plannedOperations,
                experiment.configuredConcurrency(),
                100,
                0,
                benchmarkProperties.getSustainedTargetOpsPerSecond(),
                benchmarkProperties.getSustainedDurationSeconds(),
                "MEASUREMENT",
                1
        );

        System.out.println();
        System.out.println("========================================");
        System.out.println("SUSTAINED TELEMETRY INGESTION");
        System.out.println("Duration: " + context.plannedDurationSeconds()
                + " seconds");
        System.out.println("Target rate: " + context.targetOpsPerSecond()
                + " ops/sec");
        System.out.println("Concurrency: " + context.concurrency());
        System.out.println("========================================");

        BenchmarkMetrics metrics = new BenchmarkMetrics();
        BenchmarkProgressReporter progress = new BenchmarkProgressReporter(
                context,
                plannedOperations,
                benchmarkProperties.getProgressReportSeconds()
        );
        ExecutorService executor = Executors.newFixedThreadPool(
                context.concurrency()
        );
        Semaphore inFlight = new Semaphore(context.concurrency() * 4);

        long startNanos = System.nanoTime();
        long endNanos = startNanos
                + context.plannedDurationSeconds() * 1_000_000_000L;
        long intervalNanos = 1_000_000_000L / context.targetOpsPerSecond();
        long nextDeadline = startNanos;
        long sequence = 0;

        metrics.start();

        try {
            while (System.nanoTime() < endNanos) {
                sleepUntil(nextDeadline);
                acquire(inFlight, "scheduling sustained ingestion");

                TelemetryRecord source = records.get(
                        (int) (sequence % records.size())
                );
                TelemetryRecord event = BenchmarkRunner.uniqueWriteRecord(
                        source,
                        sequence
                );
                long currentSequence = sequence;
                long operationStart = System.nanoTime();

                executor.submit(() -> {
                    try {
                        repository.insertAsync(event)
                                .toCompletableFuture()
                                .join();
                        metrics.recordSuccess(
                                System.nanoTime() - operationStart
                        );
                        progress.recordSuccess();
                    } catch (Exception e) {
                        metrics.recordFailure(
                                System.nanoTime() - operationStart,
                                currentSequence,
                                e
                        );
                        progress.recordFailure();
                    } finally {
                        inFlight.release();
                    }
                });

                sequence++;
                nextDeadline += intervalNanos;
            }
        } finally {
            shutdownExecutor(executor);
            metrics.stop();
            progress.finish();
        }

        metrics.printResults(
                context.operation(),
                (int) Math.min(Integer.MAX_VALUE, sequence),
                context.concurrency()
        );

        BenchmarkResult result = BenchmarkResult.from(context, metrics);
        resultWriter.write(result, metrics.getFailures());
        qualityGate.requireSuccessfulOperations(result);
    }

    private BenchmarkRunContext runContext(
            ExperimentContext experiment,
            String operation,
            String workloadProfile,
            long requestedDatasetSize,
            int concurrency,
            int writePercentage,
            int readPercentage,
            int targetOpsPerSecond,
            int plannedDurationSeconds,
            String runType,
            int runNumber
    ) {
        return new BenchmarkRunContext(
                experiment,
                operation,
                workloadProfile,
                requestedDatasetSize,
                concurrency,
                writePercentage,
                readPercentage,
                targetOpsPerSecond,
                plannedDurationSeconds,
                runType,
                runNumber
        );
    }

    private void printMixedHeader(BenchmarkRunContext context) {
        System.out.println();
        System.out.println("========================================");
        System.out.println("MIXED WORKLOAD");
        System.out.println("Profile: " + context.workloadProfile());
        System.out.println("Write percentage: " + context.writePercentage() + "%");
        System.out.println("Read percentage: " + context.readPercentage() + "%");
        System.out.println("Operations: " + context.requestedDatasetSize());
        System.out.println("Concurrency: " + context.concurrency());
        System.out.println("Run type: " + context.runType());
        System.out.println("Run number: " + context.runNumber());
        System.out.println("========================================");
    }

    private void acquire(Semaphore semaphore, String operation) {
        try {
            semaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Benchmark interrupted while " + operation + ".",
                    e
            );
        }
    }

    private void sleepUntil(long deadlineNanos) {
        long remaining = deadlineNanos - System.nanoTime();

        if (remaining <= 0) {
            return;
        }

        try {
            TimeUnit.NANOSECONDS.sleep(remaining);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Sustained ingestion interrupted.",
                    e
            );
        }
    }

    private void shutdownExecutor(ExecutorService executor) {
        executor.shutdown();

        try {
            if (!executor.awaitTermination(30, TimeUnit.MINUTES)) {
                executor.shutdownNow();

                if (!executor.awaitTermination(1, TimeUnit.MINUTES)) {
                    throw new IllegalStateException(
                            "Benchmark executor did not terminate."
                    );
                }
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Benchmark execution interrupted.",
                    e
            );
        }
    }
}
