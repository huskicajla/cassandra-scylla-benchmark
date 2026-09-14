package ba.unze.master.benchmarkapp.preloaded;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

@Component
public class BaselineSeedFailureWriter {

    private static final Path FILE = Path.of("results", "baseline-seed-failures-v1.csv");
    private static final String HEADER = "baseline_run_id,observed_at_utc,database,baseline_id,"
            + "record_index,attempt,max_attempts,error_type,error_message";

    public BaselineSeedFailureWriter() {
        try {
            Files.createDirectories(FILE.getParent());
            if (!Files.exists(FILE) || Files.size(FILE) == 0) {
                Files.writeString(FILE, HEADER + System.lineSeparator(),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot initialize baseline seed failure file.", e);
        }
    }

    public synchronized void write(String runId, String database, String baselineId,
                                   long recordIndex, int attempt, int maxAttempts, Throwable error) {
        String message = error.getMessage() == null ? "" : error.getMessage();
        String line = String.join(",", csv(runId), csv(Instant.now().toString()), csv(database),
                csv(baselineId), Long.toString(recordIndex), Integer.toString(attempt),
                Integer.toString(maxAttempts), csv(error.getClass().getName()), csv(message));
        try {
            Files.writeString(FILE, line + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write baseline seed failure.", e);
        }
    }

    private String csv(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
