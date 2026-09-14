package ba.unze.master.benchmarkapp.benchmark;

import ba.unze.master.benchmarkapp.config.BenchmarkProperties;
import ba.unze.master.benchmarkapp.data.TelemetryRecord;
import ba.unze.master.benchmarkapp.database.TelemetryRepository;
import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class ReadBenchmarkRunner {

    private final TelemetryRepository repository;
    private final BenchmarkResultWriter resultWriter;
    private final BenchmarkProperties benchmarkProperties;
    private final BenchmarkQualityGate qualityGate;

    public ReadBenchmarkRunner(
            TelemetryRepository repository,
            BenchmarkResultWriter resultWriter,
            BenchmarkProperties benchmarkProperties,
            BenchmarkQualityGate qualityGate
    ) {
        this.repository = repository;
        this.resultWriter = resultWriter;
        this.benchmarkProperties = benchmarkProperties;
        this.qualityGate = qualityGate;
    }

    public BenchmarkResult runReadSingle(
            List<TelemetryRecord> records,
            BenchmarkRunContext context
    ) {

        return runConcurrentRead(
                records,
                context,
                record ->
                        repository.readSingleAsync(
                                record.deviceId(),
                                record.eventTime(),
                                record.id()
                        )
        );
    }

    public BenchmarkResult runReadLatest(
            List<TelemetryRecord> records,
            BenchmarkRunContext context
    ) {

        return runConcurrentRead(
                records,
                context,
                record ->
                        repository.readLatestAsync(
                                record.deviceId()
                        )
        );
    }

    public BenchmarkResult runReadPartition(
            List<TelemetryRecord> records,
            BenchmarkRunContext context
    ) {

        return runConcurrentRead(
                records,
                context,
                record ->
                        repository.readPartitionAsync(
                                record.deviceId()
                        )
        );
    }

    public BenchmarkResult runReadTimeRange(
            List<TelemetryRecord> records,
            BenchmarkRunContext context
    ) {

        Instant min =
                records.stream()
                        .map(TelemetryRecord::eventTime)
                        .min(Instant::compareTo)
                        .orElseThrow();

        Instant max =
                records.stream()
                        .map(TelemetryRecord::eventTime)
                        .max(Instant::compareTo)
                        .orElseThrow();

        return runConcurrentRead(
                records,
                context,
                record ->
                        repository.readTimeRangeAsync(
                                record.deviceId(),
                                min,
                                max
                        )
        );
    }

    private BenchmarkResult runConcurrentRead(
            List<TelemetryRecord> records,
            BenchmarkRunContext context,
            ReadOperation operationFunction
    ) {

        BenchmarkMetrics metrics =
                new BenchmarkMetrics();
        BenchmarkProgressReporter progress = new BenchmarkProgressReporter(
                context,
                records.size(),
                benchmarkProperties.getProgressReportSeconds()
        );

        ExecutorService executor =
                Executors.newFixedThreadPool(
                        context.concurrency()
                );

        AtomicInteger nextIndex =
                new AtomicInteger(0);

        metrics.start();

        for (int worker = 0;
             worker < context.concurrency();
             worker++) {

            executor.submit(() -> {

                while (true) {

                    int index =
                            nextIndex.getAndIncrement();

                    if (index >= records.size()) {
                        break;
                    }

                    TelemetryRecord record =
                            records.get(index);

                    long start =
                            System.nanoTime();

                    try {

                        AsyncResultSet result =
                                operationFunction
                                        .execute(record)
                                        .toCompletableFuture()
                                        .join();

                        for (var row : result.currentPage()) {
                        }

                        metrics.recordSuccess(
                                System.nanoTime() - start
                        );
                        progress.recordSuccess();

                    } catch (Exception e) {

                        metrics.recordFailure(
                                System.nanoTime() - start,
                                index,
                                e
                        );
                        progress.recordFailure();
                    }
                }
            });
        }

        executor.shutdown();

        try {

            if (!executor.awaitTermination(
                    30,
                    TimeUnit.MINUTES
            )) {

                executor.shutdownNow();

                throw new IllegalStateException(
                        "Read benchmark did not finish."
                );
            }

        } catch (InterruptedException e) {

            executor.shutdownNow();

            Thread.currentThread().interrupt();

            throw new IllegalStateException(
                    "Read benchmark interrupted.",
                    e
            );
        }

        metrics.stop();
        progress.finish();

        metrics.printResults(
                context.operation(),
                records.size(),
                context.concurrency()
        );

        BenchmarkResult result = BenchmarkResult.from(context, metrics);

        resultWriter.write(result, metrics.getFailures());
        qualityGate.requireSuccessfulOperations(result);

        return result;
    }

    @FunctionalInterface
    private interface ReadOperation {
        java.util.concurrent.CompletionStage<AsyncResultSet> execute(
                TelemetryRecord record
        );
    }
}
