package ba.unze.master.benchmarkapp.benchmark;

import ba.unze.master.benchmarkapp.config.BenchmarkProperties;
import ba.unze.master.benchmarkapp.data.CsvDatasetReader;
import ba.unze.master.benchmarkapp.data.TelemetryRecord;
import ba.unze.master.benchmarkapp.database.TelemetryRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

@Component
public class BenchmarkRunner {

    private final CsvDatasetReader datasetReader;
    private final TelemetryRepository repository;
    private final BenchmarkResultWriter resultWriter;
    private final BenchmarkProperties benchmarkProperties;
    private final BenchmarkQualityGate qualityGate;

    public BenchmarkRunner(
            CsvDatasetReader datasetReader,
            TelemetryRepository repository,
            BenchmarkResultWriter resultWriter,
            BenchmarkProperties benchmarkProperties,
            BenchmarkQualityGate qualityGate
    ) {
        this.datasetReader = datasetReader;
        this.repository = repository;
        this.resultWriter = resultWriter;
        this.benchmarkProperties = benchmarkProperties;
        this.qualityGate = qualityGate;
    }

    public BenchmarkResult runSequentialWrite(
            BenchmarkRunContext context
    ) {

        BenchmarkMetrics metrics = new BenchmarkMetrics();
        BenchmarkProgressReporter progress = progressFor(context);

        printWriteHeader("SEQUENTIAL WRITE", context);

        metrics.start();

        long operationIndex = 0;

        try (Stream<TelemetryRecord> records =
                     datasetReader.stream(context.requestedDatasetSize())) {

            var iterator = records.iterator();

            while (iterator.hasNext()) {

                TelemetryRecord record = iterator.next();
                long currentIndex = operationIndex++;
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
                }
            }

        } finally {
            metrics.stop();
            progress.finish();
        }

        return finish(metrics, context);
    }

    public BenchmarkResult runConcurrentWrite(
            BenchmarkRunContext context
    ) {

        BenchmarkMetrics metrics = new BenchmarkMetrics();
        BenchmarkProgressReporter progress = progressFor(context);
        ExecutorService executor = Executors.newFixedThreadPool(
                context.concurrency()
        );
        Semaphore inFlight = new Semaphore(context.concurrency() * 4);
        AtomicLong operationIndex = new AtomicLong();

        printWriteHeader("CONCURRENT WRITE", context);

        metrics.start();

        try (Stream<TelemetryRecord> records =
                     datasetReader.stream(context.requestedDatasetSize())) {

            records.forEach(record -> {
                long currentIndex = operationIndex.getAndIncrement();

                acquire(inFlight);

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
            });

        } finally {
            shutdownAndWait(executor);
            metrics.stop();
            progress.finish();
        }

        return finish(metrics, context);
    }

    private BenchmarkResult finish(
            BenchmarkMetrics metrics,
            BenchmarkRunContext context
    ) {

        metrics.printResults(
                context.operation(),
                (int) Math.min(
                        Integer.MAX_VALUE,
                        context.requestedDatasetSize()
                ),
                context.concurrency()
        );

        BenchmarkResult result = BenchmarkResult.from(context, metrics);

        resultWriter.write(result, metrics.getFailures());
        qualityGate.requireSuccessfulOperations(result);

        return result;
    }

    private BenchmarkProgressReporter progressFor(
            BenchmarkRunContext context
    ) {
        return new BenchmarkProgressReporter(
                context,
                context.requestedDatasetSize(),
                benchmarkProperties.getProgressReportSeconds()
        );
    }

    private void printWriteHeader(
            String title,
            BenchmarkRunContext context
    ) {
        System.out.println();
        System.out.println("========================================");
        System.out.println(title);
        System.out.println("Records: " + context.requestedDatasetSize());
        System.out.println("Concurrency: " + context.concurrency());
        System.out.println("Consistency level: "
                + context.experiment().consistencyLevel());
        System.out.println("Run type: " + context.runType());
        System.out.println("Run number: " + context.runNumber());
        System.out.println("========================================");
    }

    private void acquire(Semaphore inFlight) {
        try {
            inFlight.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Write benchmark interrupted while waiting for capacity.",
                    e
            );
        }
    }

    private void shutdownAndWait(ExecutorService executor) {
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
            throw new IllegalStateException("Benchmark interrupted.", e);
        }
    }

    public static TelemetryRecord uniqueWriteRecord(
            TelemetryRecord source,
            long sequence
    ) {

        return new TelemetryRecord(
                source.deviceId(),
                source.eventTime().plusMillis(sequence),
                UUID.randomUUID(),
                source.metricType(),
                source.value()
        );
    }
}
