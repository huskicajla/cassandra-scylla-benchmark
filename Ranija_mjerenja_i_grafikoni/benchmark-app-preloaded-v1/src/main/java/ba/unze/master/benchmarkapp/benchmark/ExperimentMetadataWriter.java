package ba.unze.master.benchmarkapp.benchmark;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

@Component
public class ExperimentMetadataWriter {

    private static final Path METADATA_FILE =
            Path.of("results", "experiment-metadata-v2.csv");

    private static final String HEADER =
            "experiment_id,started_at_utc,database,keyspace,consistency_level," +
            "replication_factor," +
            "cluster_node_count,dataset_path,write_dataset_size," +
            "read_dataset_size,concurrency,warmup_runs,measurement_runs," +
            "java_version,java_vendor,os_name,os_version,os_arch," +
            "available_processors,max_memory_bytes";

    public ExperimentMetadataWriter() {
        initialize();
    }

    private void initialize() {
        try {
            Files.createDirectories(METADATA_FILE.getParent());

            if (!Files.exists(METADATA_FILE)
                    || Files.size(METADATA_FILE) == 0) {

                Files.writeString(
                        METADATA_FILE,
                        HEADER + System.lineSeparator(),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                );
            }

        } catch (IOException e) {
            throw new IllegalStateException(
                    "Cannot initialize experiment metadata file: "
                            + METADATA_FILE.toAbsolutePath(),
                    e
            );
        }
    }

    public synchronized void write(ExperimentContext experiment) {

        Runtime runtime = Runtime.getRuntime();

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
                csv(System.getProperty("java.version", "")),
                csv(System.getProperty("java.vendor", "")),
                csv(System.getProperty("os.name", "")),
                csv(System.getProperty("os.version", "")),
                csv(System.getProperty("os.arch", "")),
                Integer.toString(runtime.availableProcessors()),
                Long.toString(runtime.maxMemory())
        );

        try {
            Files.writeString(
                    METADATA_FILE,
                    line + System.lineSeparator(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );

        } catch (IOException e) {
            throw new IllegalStateException(
                    "Cannot write experiment metadata.",
                    e
            );
        }
    }

    private String csv(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
