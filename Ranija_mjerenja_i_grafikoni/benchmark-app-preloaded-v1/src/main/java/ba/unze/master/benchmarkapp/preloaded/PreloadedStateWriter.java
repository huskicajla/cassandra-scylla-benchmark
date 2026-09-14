package ba.unze.master.benchmarkapp.preloaded;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

@Component
public class PreloadedStateWriter {

    private static final Path FILE = Path.of("results", "preloaded-state-v1.csv");
    private static final String HEADER =
            "recorded_at_utc,experiment_id,database,baseline_id,event_type,"
                    + "baseline_table,workload_table,baseline_record_count,"
                    + "workload_planned_operations,detail";

    public PreloadedStateWriter() {
        try {
            Files.createDirectories(FILE.getParent());
            if (!Files.exists(FILE) || Files.size(FILE) == 0) {
                Files.writeString(FILE, HEADER + System.lineSeparator(),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot initialize preloaded state file.", e);
        }
    }

    public synchronized void write(
            String experimentId,
            String database,
            String baselineId,
            String eventType,
            String baselineTable,
            String workloadTable,
            long baselineRecordCount,
            long workloadPlannedOperations,
            String detail
    ) {
        String line = String.join(",",
                csv(Instant.now().toString()),
                csv(experimentId),
                csv(database),
                csv(baselineId),
                csv(eventType),
                csv(baselineTable),
                csv(workloadTable),
                Long.toString(baselineRecordCount),
                Long.toString(workloadPlannedOperations),
                csv(detail));
        try {
            Files.writeString(FILE, line + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write preloaded state.", e);
        }
    }

    private String csv(String value) {
        return "\"" + (value == null ? "" : value.replace("\"", "\"\"")) + "\"";
    }
}
