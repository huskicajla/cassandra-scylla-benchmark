package ba.unze.master.benchmarkapp.benchmark;

public record BenchmarkResult(
        BenchmarkRunContext context,
        long attemptedOperations,
        long successfulOperations,
        long failedOperations,
        double totalTimeMs,
        double throughputOpsSec,
        double averageLatencyMs,
        double minLatencyMs,
        double maxLatencyMs,
        double p50Ms,
        double p95Ms,
        double p99Ms,
        String failureSummary
) {

    public static BenchmarkResult from(
            BenchmarkRunContext context,
            BenchmarkMetrics metrics
    ) {

        return new BenchmarkResult(
                context,
                metrics.getTotalOperations(),
                metrics.getSuccessfulOperations(),
                metrics.getFailedOperations(),
                metrics.getTotalTimeMillis(),
                metrics.getThroughput(),
                metrics.getAverageLatencyMillis(),
                metrics.getMinLatencyMillis(),
                metrics.getMaxLatencyMillis(),
                metrics.getPercentileMillis(0.50),
                metrics.getPercentileMillis(0.95),
                metrics.getPercentileMillis(0.99),
                metrics.getFailureSummary()
        );
    }
}
