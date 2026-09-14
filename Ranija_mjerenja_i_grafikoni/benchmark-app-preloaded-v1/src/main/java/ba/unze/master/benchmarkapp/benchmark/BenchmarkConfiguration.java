package ba.unze.master.benchmarkapp.benchmark;

public record BenchmarkConfiguration(
        long datasetSize,
        int concurrency,
        int warmupRuns,
        int measurementRuns
) {

    public BenchmarkConfiguration {

        if (datasetSize <= 0) {
            throw new IllegalArgumentException(
                    "Dataset size must be greater than zero."
            );
        }

        if (concurrency <= 0) {
            throw new IllegalArgumentException(
                    "Concurrency must be greater than zero."
            );
        }

        if (warmupRuns < 0) {
            throw new IllegalArgumentException(
                    "Warm-up runs cannot be negative."
            );
        }

        if (measurementRuns <= 0) {
            throw new IllegalArgumentException(
                    "Measurement runs must be greater than zero."
            );
        }
    }
}