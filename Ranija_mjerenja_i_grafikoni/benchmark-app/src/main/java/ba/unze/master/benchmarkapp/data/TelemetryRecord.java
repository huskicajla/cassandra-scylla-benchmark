package ba.unze.master.benchmarkapp.data;

import java.time.Instant;
import java.util.UUID;

public record TelemetryRecord(
        String deviceId,
        Instant eventTime,
        UUID id,
        String metricType,
        double value
) {
}