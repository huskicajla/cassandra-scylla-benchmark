package ba.unze.master.benchmarkapp.benchmark;

import ba.unze.master.benchmarkapp.config.BenchmarkProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

@Component
public class ExperimentProtocolWriter {

    private static final Path PROTOCOL_FILE =
            Path.of("results", "experiment-protocol-v1.csv");

    private static final String HEADER =
            "experiment_id,test_series,sequential_write_operations," +
            "concurrent_write_operations,read_population_size," +
            "read_operations_per_run,mixed_operations_per_run," +
            "sustained_duration_seconds,sustained_target_ops_per_second," +
            "progress_report_seconds";

    public ExperimentProtocolWriter() {
        initialize();
    }

    public synchronized void write(
            ExperimentContext experiment,
            BenchmarkProperties properties
    ) {
        String line = String.join(
                ",",
                csv(experiment.experimentId()),
                csv(properties.getTestSeries()),
                Long.toString(properties.getSequentialWriteSize()),
                Long.toString(properties.getConcurrentWriteSize()),
                Integer.toString(properties.getReadDatasetSize()),
                Integer.toString(properties.getReadOperationCount()),
                Integer.toString(properties.getMixedOperationCount()),
                Integer.toString(properties.getSustainedDurationSeconds()),
                Integer.toString(properties.getSustainedTargetOpsPerSecond()),
                Integer.toString(properties.getProgressReportSeconds())
        );

        try {
            Files.writeString(
                    PROTOCOL_FILE,
                    line + System.lineSeparator(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Cannot write experiment protocol.",
                    e
            );
        }
    }

    private void initialize() {
        try {
            Files.createDirectories(PROTOCOL_FILE.getParent());
            if (!Files.exists(PROTOCOL_FILE) || Files.size(PROTOCOL_FILE) == 0) {
                Files.writeString(
                        PROTOCOL_FILE,
                        HEADER + System.lineSeparator(),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                );
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Cannot initialize experiment protocol file.",
                    e
            );
        }
    }

    private String csv(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
