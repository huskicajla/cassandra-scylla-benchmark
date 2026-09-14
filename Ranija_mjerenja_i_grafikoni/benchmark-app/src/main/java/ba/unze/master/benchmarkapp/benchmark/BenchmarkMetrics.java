package ba.unze.master.benchmarkapp.benchmark;

import org.HdrHistogram.Histogram;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.TimeUnit;

public class BenchmarkMetrics {

    private final Histogram histogram =
            new Histogram(
                    TimeUnit.SECONDS.toNanos(60),
                    3
            );

    private long startNanos;
    private long endNanos;
    private long successfulOperations;
    private long failedOperations;

    private final ConcurrentLinkedQueue<FailureObservation> failures =
            new ConcurrentLinkedQueue<>();

    private final ConcurrentHashMap<String, LongAdder> failureCategories =
            new ConcurrentHashMap<>();

    public void start() {
        startNanos = System.nanoTime();
    }

    public void stop() {
        endNanos = System.nanoTime();
    }

    public synchronized void recordSuccess(long latencyNanos) {
        successfulOperations++;
        histogram.recordValue(
                Math.max(1L, latencyNanos)
        );
    }

    public synchronized void recordFailure() {
        failedOperations++;
    }

    public void recordFailure(
            long latencyNanos,
            long operationIndex,
            Throwable error
    ) {

        Throwable rootCause = rootCause(error);

        String category = rootCause.getClass().getName();

        failureCategories
                .computeIfAbsent(
                        category,
                        ignored -> new LongAdder()
                )
                .increment();

        failures.add(
                new FailureObservation(
                        Instant.now().toString(),
                        operationIndex,
                        latencyNanos / 1_000_000.0,
                        error.getClass().getName(),
                        safeMessage(error),
                        rootCause.getClass().getName(),
                        safeMessage(rootCause)
                )
        );

        recordFailure();
    }

    public long getSuccessfulOperations() {
        return successfulOperations;
    }

    public long getFailedOperations() {
        return failedOperations;
    }

    public long getTotalOperations() {
        return successfulOperations + failedOperations;
    }

    public List<FailureObservation> getFailures() {
        return new ArrayList<>(failures);
    }

    public String getFailureSummary() {

        if (failureCategories.isEmpty()) {
            return "";
        }

        Map<String, LongAdder> sorted =
                new TreeMap<>(failureCategories);

        return sorted.entrySet()
                .stream()
                .map(entry ->
                        entry.getKey()
                                + "="
                                + entry.getValue().sum()
                )
                .reduce((left, right) -> left + " | " + right)
                .orElse("");
    }

    public double getTotalTimeMillis() {
        return (endNanos - startNanos) / 1_000_000.0;
    }

    public double getThroughput() {
        double seconds = getTotalTimeMillis() / 1000.0;

        if (seconds <= 0.0) {
            return 0.0;
        }

        return successfulOperations / seconds;
    }

    public double getAverageLatencyMillis() {
        if (successfulOperations == 0) {
            return 0.0;
        }

        return histogram.getMean() / 1_000_000.0;
    }

    public double getMinLatencyMillis() {
        if (successfulOperations == 0) {
            return 0.0;
        }

        return histogram.getMinValue() / 1_000_000.0;
    }

    public double getMaxLatencyMillis() {
        if (successfulOperations == 0) {
            return 0.0;
        }

        return histogram.getMaxValue() / 1_000_000.0;
    }

    public double getPercentileMillis(double percentile) {
        if (successfulOperations == 0) {
            return 0.0;
        }

        return histogram.getValueAtPercentile(
                percentile * 100.0
        ) / 1_000_000.0;
    }

    public void printResults(
            String operation,
            int datasetSize,
            int concurrency
    ) {
        System.out.println();
        System.out.println("========================================");
        System.out.println("BENCHMARK RESULTS");
        System.out.println("========================================");
        System.out.println("Operation: " + operation);
        System.out.println("Dataset size: " + datasetSize);
        System.out.println("Concurrency: " + concurrency);
        System.out.println("Successful operations: " + successfulOperations);
        System.out.println("Failed operations: " + failedOperations);
        if (failedOperations > 0) {
            System.out.println(
                    "Failure summary: "
                            + getFailureSummary()
            );
        }
        System.out.println("Total time: " + getTotalTimeMillis() + " ms");
        System.out.println("Throughput: " + getThroughput() + " ops/sec");
        System.out.println("Average latency: " + getAverageLatencyMillis() + " ms");
        System.out.println("Min latency: " + getMinLatencyMillis() + " ms");
        System.out.println("Max latency: " + getMaxLatencyMillis() + " ms");
        System.out.println("P50 latency: " + getPercentileMillis(0.50) + " ms");
        System.out.println("P95 latency: " + getPercentileMillis(0.95) + " ms");
        System.out.println("P99 latency: " + getPercentileMillis(0.99) + " ms");
        System.out.println("========================================");
    }

    private Throwable rootCause(Throwable error) {

        Throwable current = error;

        while (current.getCause() != null
                && current.getCause() != current) {

            current = current.getCause();
        }

        return current;
    }

    private String safeMessage(Throwable error) {

        String message = error.getMessage();

        return message == null ? "" : message;
    }
}
