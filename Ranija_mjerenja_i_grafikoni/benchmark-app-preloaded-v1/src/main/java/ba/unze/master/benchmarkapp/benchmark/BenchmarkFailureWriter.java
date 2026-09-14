package ba.unze.master.benchmarkapp.benchmark;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

@Component
public class BenchmarkFailureWriter {

    private static final Path FAILURE_FILE =
            Path.of("results", "benchmark-failures-v2.csv");

    private static final String HEADER =
            "experiment_id,database,operation,workload_profile,run_type," +
            "run_number,occurred_at_utc,operation_index,latency_ms," +
            "exception_type,exception_message,root_cause_type," +
            "root_cause_message";

    public BenchmarkFailureWriter() {
        initialize();
    }

    private void initialize() {
        try {
            Files.createDirectories(FAILURE_FILE.getParent());

            if (!Files.exists(FAILURE_FILE)
                    || Files.size(FAILURE_FILE) == 0) {

                Files.writeString(
                        FAILURE_FILE,
                        HEADER + System.lineSeparator(),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                );
            }

        } catch (IOException e) {
            throw new IllegalStateException(
                    "Cannot initialize benchmark failure file: "
                            + FAILURE_FILE.toAbsolutePath(),
                    e
            );
        }
    }

    public synchronized void writeAll(
            BenchmarkResult result,
            List<FailureObservation> failures
    ) {

        if (failures.isEmpty()) {
            return;
        }

        BenchmarkRunContext run = result.context();

        StringBuilder lines = new StringBuilder();

        for (FailureObservation failure : failures) {
            lines.append(String.join(
                    ",",
                    csv(run.experiment().experimentId()),
                    csv(run.experiment().database()),
                    csv(run.operation()),
                    csv(run.workloadProfile()),
                    csv(run.runType()),
                    Integer.toString(run.runNumber()),
                    csv(failure.occurredAtUtc()),
                    Long.toString(failure.operationIndex()),
                    Double.toString(failure.latencyMs()),
                    csv(failure.exceptionType()),
                    csv(failure.exceptionMessage()),
                    csv(failure.rootCauseType()),
                    csv(failure.rootCauseMessage())
            ));
            lines.append(System.lineSeparator());
        }

        try {
            Files.writeString(
                    FAILURE_FILE,
                    lines.toString(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );

        } catch (IOException e) {
            throw new IllegalStateException(
                    "Cannot write benchmark failures.",
                    e
            );
        }
    }

    private String csv(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
