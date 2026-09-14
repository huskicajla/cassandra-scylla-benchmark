package ba.unze.master.benchmarkapp.preloaded;

import ba.unze.master.benchmarkapp.config.DatabaseProperties;
import ba.unze.master.benchmarkapp.database.DatabaseClient;
import com.datastax.oss.driver.api.core.cql.Row;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class PreloadedDatabaseSchema {

    private final DatabaseClient databaseClient;
    private final DatabaseProperties properties;

    public PreloadedDatabaseSchema(DatabaseClient databaseClient, DatabaseProperties properties) {
        this.databaseClient = databaseClient;
        this.properties = properties;
    }

    public void createSchema() {
        String keyspace = identifier(properties.getKeyspace());
        String datacenter = properties.getLocalDatacenter().replace("'", "''");
        int rf = properties.getReplicationFactor();
        if (rf <= 0) {
            throw new IllegalArgumentException("Replication factor must be greater than zero.");
        }
        databaseClient.getSession().execute("CREATE KEYSPACE IF NOT EXISTS " + keyspace
                + " WITH replication = " + replication(datacenter, rf));
        alterReplicationSafely(keyspace, datacenter, rf);

        createTelemetryTable(keyspace, "telemetry_baseline");
        createTelemetryTable(keyspace, "telemetry_workload");
        databaseClient.getSession().execute("""
                CREATE TABLE IF NOT EXISTS %s.preloaded_baseline_registry (
                    baseline_id text PRIMARY KEY,
                    record_count bigint,
                    dataset_path text,
                    prepared_at timestamp
                )
                """.formatted(keyspace));

        System.out.println("Preloaded schema ready: " + keyspace
                + ".telemetry_baseline (persistent reads), " + keyspace
                + ".telemetry_workload (reset before each measured run).");
    }

    private void createTelemetryTable(String keyspace, String table) {
        databaseClient.getSession().execute("""
                CREATE TABLE IF NOT EXISTS %s.%s (
                    device_id text,
                    event_time timestamp,
                    id uuid,
                    metric_type text,
                    value double,
                    PRIMARY KEY ((device_id), event_time, id)
                )
                """.formatted(keyspace, table));
    }

    private void alterReplicationSafely(String keyspace, String dc, int targetRf) {
        Row row = databaseClient.getSession().execute(
                "SELECT replication FROM system_schema.keyspaces WHERE keyspace_name = '"
                        + keyspace + "'").one();
        if (row == null) {
            throw new IllegalStateException("Keyspace was not found after creation: " + keyspace);
        }
        Map<String, String> current = row.getMap("replication", String.class, String.class);
        String currentClass = current == null ? null : current.get("class");
        String currentRfText = current == null ? null : current.get(dc);
        if (!("NetworkTopologyStrategy".equals(currentClass)
                || "org.apache.cassandra.locator.NetworkTopologyStrategy".equals(currentClass))
                || currentRfText == null) {
            alter(keyspace, replication(dc, targetRf));
            return;
        }
        int currentRf;
        try {
            currentRf = Integer.parseInt(currentRfText);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Invalid existing replication factor: " + currentRfText, e);
        }
        while (currentRf != targetRf) {
            currentRf += Integer.compare(targetRf, currentRf);
            alter(keyspace, replication(dc, currentRf));
        }
    }

    private void alter(String keyspace, String replication) {
        databaseClient.getSession().execute("ALTER KEYSPACE " + keyspace
                + " WITH replication = " + replication);
    }

    private String replication(String dc, int rf) {
        return "{'class': 'NetworkTopologyStrategy', '" + dc + "': " + rf + "}";
    }

    private String identifier(String value) {
        if (!value.matches("[A-Za-z][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid CQL identifier: " + value);
        }
        return value;
    }
}
