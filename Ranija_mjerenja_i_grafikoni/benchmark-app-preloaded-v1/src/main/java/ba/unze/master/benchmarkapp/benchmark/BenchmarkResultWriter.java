package ba.unze.master.benchmarkapp.benchmark;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

@Component
public class BenchmarkResultWriter {

    private static final Path RESULT_FILE =
            Path.of("results", "benchmark-results-v2.csv");

    private static final String HEADER =
            "experiment_id,experiment_started_at_utc,database,keyspace," +
            "consistency_level,replication_factor,cluster_node_count,dataset_path," +
            "configured_write_dataset_size,configured_read_dataset_size," +
            "configured_concurrency,configured_warmup_runs," +
            "configured_measurement_runs,operation,workload_profile," +
            "requested_dataset_size,concurrency,write_percentage," +
            "read_percentage,target_ops_sec,planned_duration_seconds," +
            "run_type,run_number,attempted_operations," +
            "successful_operations,failed_operations,total_time_ms," +
            "throughput_ops_sec,average_latency_ms,min_latency_ms,max_latency_ms," +
            "p50_ms,p95_ms,p99_ms,failure_summary";

    private final BenchmarkFailureWriter failureWriter;

    public BenchmarkResultWriter(
            BenchmarkFailureWriter failureWriter
    ) {
        this.failureWriter = failureWriter;
        initialize();
    }

    private void initialize() {

        try {
            Files.createDirectories(
                    RESULT_FILE.getParent()
            );

            if (!Files.exists(RESULT_FILE)
                    || Files.size(RESULT_FILE) == 0) {

                Files.writeString(
                        RESULT_FILE,
                        HEADER + System.lineSeparator(),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                );
            }

        } catch (IOException e) {
            throw new IllegalStateException(
                    "Cannot initialize benchmark result file: "
                            + RESULT_FILE.toAbsolutePath(),
                    e
            );
        }
    }

    public synchronized void write(
            BenchmarkResult result,
            List<FailureObservation> failures
    ) {

        BenchmarkRunContext run = result.context();
        ExperimentContext experiment = run.experiment();

        String line = String.join(
                ",",
                csv(experiment.experimentId()),
                csv(experiment.startedAtUtc()),
                csv(experiment.database()),
                csv(experiment.keyspace()),
                csv(experiment.consistencyLevel()),
                Integer.toString(experiment.replicationFactor()),
                Integer.toString(experiment.clusterNodeCount()),
                csv(experiment.datasetPath()),
                Long.toString(experiment.writeDatasetSize()),
                Integer.toString(experiment.readDatasetSize()),
                Integer.toString(experiment.configuredConcurrency()),
                Integer.toString(experiment.configuredWarmupRuns()),
                Integer.toString(experiment.configuredMeasurementRuns()),
                csv(run.operation()),
                csv(run.workloadProfile()),
                Long.toString(run.requestedDatasetSize()),
                Integer.toString(run.concurrency()),
                Integer.toString(run.writePercentage()),
                Integer.toString(run.readPercentage()),
                Integer.toString(run.targetOpsPerSecond()),
                Integer.toString(run.plannedDurationSeconds()),
                csv(run.runType()),
                Integer.toString(run.runNumber()),
                Long.toString(result.attemptedOperations()),
                Long.toString(result.successfulOperations()),
                Long.toString(result.failedOperations()),
                Double.toString(result.totalTimeMs()),
                Double.toString(result.throughputOpsSec()),
                Double.toString(result.averageLatencyMs()),
                Double.toString(result.minLatencyMs()),
                Double.toString(result.maxLatencyMs()),
                Double.toString(result.p50Ms()),
                Double.toString(result.p95Ms()),
                Double.toString(result.p99Ms()),
                csv(result.failureSummary())
        );

        try {
            Files.writeString(
                    RESULT_FILE,
                    line + System.lineSeparator(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );

        } catch (IOException e) {
            throw new IllegalStateException(
                    "Cannot write benchmark result.",
                    e
            );
        }

        failureWriter.writeAll(result, failures);
    }

    private String csv(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
