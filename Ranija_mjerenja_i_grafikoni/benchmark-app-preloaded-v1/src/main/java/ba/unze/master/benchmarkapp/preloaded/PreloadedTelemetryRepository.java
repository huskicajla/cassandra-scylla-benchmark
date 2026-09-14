package ba.unze.master.benchmarkapp.preloaded;

import ba.unze.master.benchmarkapp.config.DatabaseProperties;
import ba.unze.master.benchmarkapp.data.TelemetryRecord;
import ba.unze.master.benchmarkapp.database.DatabaseClient;
import com.datastax.oss.driver.api.core.ConsistencyLevel;
import com.datastax.oss.driver.api.core.DefaultConsistencyLevel;
import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

@Component
public class PreloadedTelemetryRepository {

    private final DatabaseClient databaseClient;
    private final ConsistencyLevel consistencyLevel;
    private final String baselineTable;
    private final String workloadTable;
    private final String registryTable;

    private PreparedStatement baselineInsert;
    private PreparedStatement workloadInsert;
    private PreparedStatement readSingle;
    private PreparedStatement readPartition;
    private PreparedStatement readRange;
    private PreparedStatement readLatest;
    private PreparedStatement registrySelect;
    private PreparedStatement registryInsert;

    public PreloadedTelemetryRepository(DatabaseClient databaseClient, DatabaseProperties properties) {
        this.databaseClient = databaseClient;
        this.consistencyLevel = parseConsistency(properties.getConsistencyLevel());
        String keyspace = identifier(properties.getKeyspace());
        this.baselineTable = keyspace + ".telemetry_baseline";
        this.workloadTable = keyspace + ".telemetry_workload";
        this.registryTable = keyspace + ".preloaded_baseline_registry";
    }

    public void prepareStatements() {
        baselineInsert = databaseClient.getSession().prepare(insertCql(baselineTable));
        workloadInsert = databaseClient.getSession().prepare(insertCql(workloadTable));
        readSingle = databaseClient.getSession().prepare("""
                SELECT device_id, event_time, id, metric_type, value FROM %s
                WHERE device_id = ? AND event_time = ? AND id = ?
                """.formatted(baselineTable));
        readPartition = databaseClient.getSession().prepare("""
                SELECT device_id, event_time, id, metric_type, value FROM %s
                WHERE device_id = ? LIMIT 100
                """.formatted(baselineTable));
        readRange = databaseClient.getSession().prepare("""
                SELECT device_id, event_time, id, metric_type, value FROM %s
                WHERE device_id = ? AND event_time >= ? AND event_time <= ? LIMIT 100
                """.formatted(baselineTable));
        readLatest = databaseClient.getSession().prepare("""
                SELECT device_id, event_time, id, metric_type, value FROM %s
                WHERE device_id = ? ORDER BY event_time DESC LIMIT 1
                """.formatted(baselineTable));
        registrySelect = databaseClient.getSession().prepare(
                "SELECT baseline_id, record_count, prepared_at FROM " + registryTable
                        + " WHERE baseline_id = ?");
        registryInsert = databaseClient.getSession().prepare("""
                INSERT INTO %s (baseline_id, record_count, dataset_path, prepared_at)
                VALUES (?, ?, ?, ?)
                """.formatted(registryTable));
    }

    public CompletionStage<AsyncResultSet> insertBaselineAsync(TelemetryRecord record) {
        return databaseClient.getSession().executeAsync(bind(baselineInsert, record));
    }

    public CompletionStage<AsyncResultSet> insertWorkloadAsync(TelemetryRecord record) {
        return databaseClient.getSession().executeAsync(bind(workloadInsert, record));
    }

    public CompletionStage<AsyncResultSet> readSingleAsync(String deviceId, Instant eventTime, UUID id) {
        return databaseClient.getSession().executeAsync(readSingle.boundStatementBuilder()
                .setString(0, deviceId).setInstant(1, eventTime).setUuid(2, id)
                .setConsistencyLevel(consistencyLevel).build());
    }

    public CompletionStage<AsyncResultSet> readPartitionAsync(String deviceId) {
        return databaseClient.getSession().executeAsync(readPartition.boundStatementBuilder()
                .setString(0, deviceId).setConsistencyLevel(consistencyLevel).build());
    }

    public CompletionStage<AsyncResultSet> readTimeRangeAsync(String deviceId, Instant from, Instant to) {
        return databaseClient.getSession().executeAsync(readRange.boundStatementBuilder()
                .setString(0, deviceId).setInstant(1, from).setInstant(2, to)
                .setConsistencyLevel(consistencyLevel).build());
    }

    public CompletionStage<AsyncResultSet> readLatestAsync(String deviceId) {
        return databaseClient.getSession().executeAsync(readLatest.boundStatementBuilder()
                .setString(0, deviceId).setConsistencyLevel(consistencyLevel).build());
    }

    public Optional<BaselineMarker> findBaseline(String baselineId) {
        Row row = databaseClient.getSession().execute(registrySelect.boundStatementBuilder()
                .setString(0, baselineId).setConsistencyLevel(consistencyLevel).build()).one();
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(new BaselineMarker(row.getString("baseline_id"),
                row.getLong("record_count"), row.getInstant("prepared_at")));
    }

    public void markBaselineReady(String baselineId, long recordCount, String datasetPath) {
        databaseClient.getSession().execute(registryInsert.boundStatementBuilder()
                .setString(0, baselineId).setLong(1, recordCount).setString(2, datasetPath)
                .setInstant(3, Instant.now()).setConsistencyLevel(consistencyLevel).build());
    }

    public void clearBaseline() {
        databaseClient.getSession().execute("TRUNCATE " + baselineTable);
        databaseClient.getSession().execute("TRUNCATE " + registryTable);
    }

    public void clearWorkload() {
        databaseClient.getSession().execute("TRUNCATE " + workloadTable);
    }

    public String baselineTable() {
        return baselineTable;
    }

    public String workloadTable() {
        return workloadTable;
    }

    private BoundStatement bind(PreparedStatement statement, TelemetryRecord record) {
        return statement.boundStatementBuilder()
                .setString(0, record.deviceId())
                .setInstant(1, record.eventTime())
                .setUuid(2, record.id())
                .setString(3, record.metricType())
                .setDouble(4, record.value())
                .setConsistencyLevel(consistencyLevel)
                .build();
    }

    private String insertCql(String table) {
        return "INSERT INTO " + table
                + " (device_id, event_time, id, metric_type, value) VALUES (?, ?, ?, ?, ?)";
    }

    private ConsistencyLevel parseConsistency(String value) {
        try {
            return DefaultConsistencyLevel.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            throw new IllegalArgumentException("Unsupported consistency level: " + value, e);
        }
    }

    private String identifier(String value) {
        if (!value.matches("[A-Za-z][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid CQL identifier: " + value);
        }
        return value;
    }

    public record BaselineMarker(String baselineId, long recordCount, Instant preparedAt) {
    }
}
