package ba.unze.master.benchmarkapp.benchmark;

public record ExperimentContext(
        String experimentId,
        String startedAtUtc,
        String database,
        String keyspace,
        String consistencyLevel,
        int replicationFactor,
        int clusterNodeCount,
        String datasetPath,
        long writeDatasetSize,
        int readDatasetSize,
        int configuredConcurrency,
        int configuredWarmupRuns,
        int configuredMeasurementRuns
) {
}
