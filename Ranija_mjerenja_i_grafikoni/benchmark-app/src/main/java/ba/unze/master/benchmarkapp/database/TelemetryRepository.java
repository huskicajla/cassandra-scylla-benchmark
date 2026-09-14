package ba.unze.master.benchmarkapp.database;

import ba.unze.master.benchmarkapp.data.TelemetryRecord;
import ba.unze.master.benchmarkapp.config.DatabaseProperties;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.ConsistencyLevel;
import com.datastax.oss.driver.api.core.DefaultConsistencyLevel;
import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.Locale;

@Component
public class TelemetryRepository {

    private final DatabaseClient databaseClient;
    private final ConsistencyLevel consistencyLevel;
    private final String tableName;

    private PreparedStatement insertStatement;
    private PreparedStatement readSingleStatement;
    private PreparedStatement readPartitionStatement;
    private PreparedStatement readTimeRangeStatement;
    private PreparedStatement readLatestStatement;

    public TelemetryRepository(
            DatabaseClient databaseClient,
            DatabaseProperties databaseProperties
    ) {
        this.databaseClient = databaseClient;
        this.consistencyLevel = parseConsistencyLevel(
                databaseProperties.getConsistencyLevel()
        );
        this.tableName = cqlIdentifier(databaseProperties.getKeyspace())
                + ".telemetry_events";
    }

    public void prepareStatements() {

        CqlSession session =
                databaseClient.getSession();

        insertStatement = session.prepare("""
            INSERT INTO %s
            (device_id, event_time, id, metric_type, value)
            VALUES (?, ?, ?, ?, ?)
            """.formatted(tableName));

        readSingleStatement = session.prepare("""
            SELECT device_id, event_time, id, metric_type, value
            FROM %s
            WHERE device_id = ?
              AND event_time = ?
              AND id = ?
            """.formatted(tableName));

        readPartitionStatement = session.prepare("""
            SELECT device_id, event_time, id, metric_type, value
            FROM %s
            WHERE device_id = ?
            LIMIT 100
            """.formatted(tableName));

        readTimeRangeStatement = session.prepare("""
            SELECT device_id, event_time, id, metric_type, value
            FROM %s
            WHERE device_id = ?
              AND event_time >= ?
              AND event_time <= ?
            LIMIT 100
            """.formatted(tableName));

        readLatestStatement = session.prepare("""
            SELECT device_id, event_time, id, metric_type, value
            FROM %s
            WHERE device_id = ?
            ORDER BY event_time DESC
            LIMIT 1
            """.formatted(tableName));

        System.out.println(
                "Prepared INSERT and READ statements created."
        );
    }

    public CompletionStage<AsyncResultSet> insertAsync(
            TelemetryRecord record
    ) {

        BoundStatement statement =
                insertStatement
                        .boundStatementBuilder()
                        .setString(0, record.deviceId())
                        .setInstant(1, record.eventTime())
                        .setUuid(2, record.id())
                        .setString(3, record.metricType())
                        .setDouble(4, record.value())
                        .setConsistencyLevel(consistencyLevel)
                        .build();

        return databaseClient
                .getSession()
                .executeAsync(statement);
    }

    public CompletionStage<AsyncResultSet> readSingleAsync(
            String deviceId,
            Instant eventTime,
            UUID id
    ) {

        BoundStatement statement =
                readSingleStatement
                        .boundStatementBuilder()
                        .setString(0, deviceId)
                        .setInstant(1, eventTime)
                        .setUuid(2, id)
                        .setConsistencyLevel(consistencyLevel)
                        .build();

        return databaseClient
                .getSession()
                .executeAsync(statement);
    }

    public CompletionStage<AsyncResultSet> readPartitionAsync(
            String deviceId
    ) {

        BoundStatement statement =
                readPartitionStatement
                        .boundStatementBuilder()
                        .setString(0, deviceId)
                        .setConsistencyLevel(consistencyLevel)
                        .build();

        return databaseClient
                .getSession()
                .executeAsync(statement);
    }

    public CompletionStage<AsyncResultSet> readTimeRangeAsync(
            String deviceId,
            Instant start,
            Instant end
    ) {

        BoundStatement statement =
                readTimeRangeStatement
                        .boundStatementBuilder()
                        .setString(0, deviceId)
                        .setInstant(1, start)
                        .setInstant(2, end)
                        .setConsistencyLevel(consistencyLevel)
                        .build();

        return databaseClient
                .getSession()
                .executeAsync(statement);
    }

    public CompletionStage<AsyncResultSet> readLatestAsync(
            String deviceId
    ) {

        BoundStatement statement =
                readLatestStatement
                        .boundStatementBuilder()
                        .setString(0, deviceId)
                        .setConsistencyLevel(consistencyLevel)
                        .build();

        return databaseClient
                .getSession()
                .executeAsync(statement);
    }

    public void clearData() {

        databaseClient
                .getSession()
                .execute(
                        "TRUNCATE " + tableName
                );

        System.out.println(
                "Benchmark table cleared."
        );
    }

    private ConsistencyLevel parseConsistencyLevel(
            String configuredLevel
    ) {

        try {
            return DefaultConsistencyLevel.valueOf(
                    configuredLevel
                            .trim()
                            .toUpperCase(Locale.ROOT)
            );

        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Unsupported consistency level: "
                            + configuredLevel,
                    e
            );
        }
    }

    private String cqlIdentifier(String value) {
        if (!value.matches("[A-Za-z][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException(
                    "Invalid CQL identifier: " + value
            );
        }

        return value;
    }
}
