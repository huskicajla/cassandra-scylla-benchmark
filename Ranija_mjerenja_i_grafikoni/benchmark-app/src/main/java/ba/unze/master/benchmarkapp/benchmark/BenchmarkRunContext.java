package ba.unze.master.benchmarkapp.benchmark;

public record BenchmarkRunContext(
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

    public BenchmarkRunContext {
        if (requestedDatasetSize <= 0) {
            throw new IllegalArgumentException(
                    "Requested dataset size must be greater than zero."
            );
        }

        if (concurrency <= 0) {
            throw new IllegalArgumentException(
                    "Concurrency must be greater than zero."
            );
        }
    }
}
