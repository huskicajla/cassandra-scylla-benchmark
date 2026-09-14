package ba.unze.master.benchmarkapp.benchmark;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class ExperimentEventWriter {

    private static final Path EVENT_FILE =
            Path.of("results", "experiment-events-v1.csv");

    private static final String HEADER =
            "experiment_id,event_at_utc,database,test_series,event_type," +
            "cluster_node_count,consistency_level,replication_factor,detail";

    private final AtomicReference<ActiveExperiment> activeExperiment =
            new AtomicReference<>();

    public ExperimentEventWriter() {
        initialize();
    }

    public void writeStarted(ExperimentContext experiment, String testSeries) {
        activeExperiment.set(new ActiveExperiment(experiment, testSeries));
        write(experiment, testSeries, "STARTED", "");
    }

    public void writeCompleted(ExperimentContext experiment, String testSeries) {
        write(experiment, testSeries, "COMPLETED", "");
        clearActiveExperiment(experiment);
    }

    public void writeAborted(
            ExperimentContext experiment,
            String testSeries,
            Throwable failure
    ) {
        write(experiment, testSeries, "ABORTED", failureSummary(failure));
        clearActiveExperiment(experiment);
    }

    public void writeSetupAborted(
            String database,
            String testSeries,
            int clusterNodeCount,
            String consistencyLevel,
            int replicationFactor,
            Throwable failure
    ) {
        Instant now = Instant.now();
        ExperimentContext setupAttempt = new ExperimentContext(
                "SETUP-" + now.toString().replaceAll("[:.-]", ""),
                now.toString(),
                database,
                "",
                consistencyLevel,
                replicationFactor,
                clusterNodeCount,
                "",
                0,
                0,
                0,
                0,
                0
        );
        write(setupAttempt, testSeries, "ABORTED", failureSummary(failure));
    }

    @PreDestroy
    public void recordInterruptedExperiment() {
        ActiveExperiment interrupted = activeExperiment.getAndSet(null);
        if (interrupted != null) {
            write(
                    interrupted.experiment(),
                    interrupted.testSeries(),
                    "ABORTED",
                    "Application stopped before experiment completion " +
                            "(for example a user Ctrl+C or system shutdown)."
            );
        }
    }

    private void clearActiveExperiment(ExperimentContext experiment) {
        ActiveExperiment active = activeExperiment.get();
        if (active != null && active.experiment().experimentId().equals(
                experiment.experimentId()
        )) {
            activeExperiment.compareAndSet(active, null);
        }
    }

    private void initialize() {
        try {
            Files.createDirectories(EVENT_FILE.getParent());

            if (!Files.exists(EVENT_FILE) || Files.size(EVENT_FILE) == 0) {
                Files.writeString(
                        EVENT_FILE,
                        HEADER + System.lineSeparator(),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                );
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Cannot initialize experiment event file: "
                            + EVENT_FILE.toAbsolutePath(),
                    e
            );
        }
    }

    private synchronized void write(
            ExperimentContext experiment,
            String testSeries,
            String eventType,
            String detail
    ) {
        String line = String.join(
                ",",
                csv(experiment.experimentId()),
                csv(Instant.now().toString()),
                csv(experiment.database()),
                csv(testSeries),
                csv(eventType),
                Integer.toString(experiment.clusterNodeCount()),
                csv(experiment.consistencyLevel()),
                Integer.toString(experiment.replicationFactor()),
                csv(detail)
        );

        try {
            Files.writeString(
                    EVENT_FILE,
                    line + System.lineSeparator(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write experiment event.", e);
        }
    }

    private String failureSummary(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }

        String message = root.getMessage() == null ? "" : root.getMessage();
        return root.getClass().getName() + ": " + message;
    }

    private String csv(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private record ActiveExperiment(
            ExperimentContext experiment,
            String testSeries
    ) {
    }
}
