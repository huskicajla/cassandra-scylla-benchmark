package ba.unze.master.benchmarkapp.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class BenchmarkResultsController {

    private static final Path RESULTS_FILE =
            Path.of("results", "benchmark-results-v2.csv");

    private static final Path METADATA_FILE =
            Path.of("results", "experiment-metadata-v2.csv");

    private static final Path EVENTS_FILE =
            Path.of("results", "experiment-events-v1.csv");

    private static final Path FAILURES_FILE =
            Path.of("results", "benchmark-failures-v2.csv");

    @GetMapping("/api/experiments")
    public List<Map<String, String>> experiments() {
        Map<String, Map<String, String>> latestEventByExperiment = new LinkedHashMap<>();
        for (Map<String, String> event : readCsv(EVENTS_FILE)) {
            latestEventByExperiment.put(event.get("experiment_id"), event);
        }

        List<Map<String, String>> experiments = new ArrayList<>();
        for (Map<String, String> metadata : readCsv(METADATA_FILE)) {
            Map<String, String> row = new LinkedHashMap<>(metadata);
            Map<String, String> event = latestEventByExperiment.get(
                    metadata.get("experiment_id")
            );
            row.put("test_series", event == null
                    ? "MAIN_3_NODE_PILOT"
                    : event.getOrDefault("test_series", ""));
            row.put("lifecycle_status", event == null
                    ? "COMPLETED"
                    : event.getOrDefault("event_type", ""));
            experiments.add(row);
        }

        experiments.sort(Comparator.comparing(
                row -> row.getOrDefault("started_at_utc", ""),
                Comparator.reverseOrder()
        ));
        return experiments;
    }

    @GetMapping("/api/results")
    public List<Map<String, String>> results(
            @RequestParam(required = false) String experimentId
    ) {
        return readCsv(RESULTS_FILE).stream()
                .filter(row -> experimentId == null
                        || experimentId.isBlank()
                        || experimentId.equals(row.get("experiment_id")))
                .toList();
    }

    @GetMapping("/api/events")
    public List<Map<String, String>> events() {
        return readCsv(EVENTS_FILE);
    }

    @GetMapping("/api/failures")
    public List<Map<String, String>> failures(
            @RequestParam(required = false) String experimentId
    ) {
        return readCsv(FAILURES_FILE).stream()
                .filter(row -> experimentId == null
                        || experimentId.isBlank()
                        || experimentId.equals(row.get("experiment_id")))
                .toList();
    }

    private List<Map<String, String>> readCsv(Path file) {
        if (!Files.exists(file)) {
            return List.of();
        }

        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.isEmpty()) {
                return List.of();
            }

            List<String> header = parseCsvLine(lines.getFirst());
            List<Map<String, String>> rows = new ArrayList<>();

            for (int lineNumber = 1; lineNumber < lines.size(); lineNumber++) {
                String line = lines.get(lineNumber);
                if (line.isBlank()) {
                    continue;
                }

                List<String> values = parseCsvLine(line);
                Map<String, String> row = new LinkedHashMap<>();
                for (int column = 0; column < header.size(); column++) {
                    row.put(
                            header.get(column),
                            column < values.size() ? values.get(column) : ""
                    );
                }
                rows.add(row);
            }
            return rows;

        } catch (IOException e) {
            throw new IllegalStateException(
                    "Cannot read benchmark evidence file: " + file.toAbsolutePath(),
                    e
            );
        }
    }

    private List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;

        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character == '"') {
                if (quoted && index + 1 < line.length()
                        && line.charAt(index + 1) == '"') {
                    value.append('"');
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (character == ',' && !quoted) {
                values.add(value.toString());
                value.setLength(0);
            } else {
                value.append(character);
            }
        }
        values.add(value.toString());
        return values;
    }
}
