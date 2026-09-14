package ba.unze.master.benchmarkapp.database;

import ba.unze.master.benchmarkapp.config.DatabaseProperties;
import com.datastax.oss.driver.api.core.cql.Row;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class DatabaseSchema {

    private final DatabaseClient databaseClient;
    private final DatabaseProperties properties;

    public DatabaseSchema(
            DatabaseClient databaseClient,
            DatabaseProperties properties
    ) {
        this.databaseClient = databaseClient;
        this.properties = properties;
    }

    public void createSchema() {

        String keyspace = cqlIdentifier(properties.getKeyspace());
        String datacenter = cqlString(properties.getLocalDatacenter());
        int replicationFactor = properties.getReplicationFactor();

        if (replicationFactor <= 0) {
            throw new IllegalArgumentException(
                    "Replication factor must be greater than zero."
            );
        }

        String replication = replication(datacenter, replicationFactor);

        databaseClient.getSession().execute(
                "CREATE KEYSPACE IF NOT EXISTS " + keyspace
                        + " WITH replication = " + replication
        );

        alterReplicationSafely(keyspace, datacenter, replicationFactor);

        databaseClient.getSession().execute("""
            CREATE TABLE IF NOT EXISTS %s.telemetry_events (
                device_id text,
                event_time timestamp,
                id uuid,
                metric_type text,
                value double,
                PRIMARY KEY ((device_id), event_time, id)
            )
            """.formatted(keyspace));

        System.out.println();
        System.out.println("========================================");
        System.out.println("DATABASE SCHEMA CREATED");
        System.out.println("Keyspace: " + keyspace);
        System.out.println("Table: " + keyspace + ".telemetry_events");
        System.out.println("Replication: " + replication);
        System.out.println("========================================");
    }

    private String cqlIdentifier(String value) {
        if (!value.matches("[A-Za-z][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException(
                    "Invalid CQL identifier: " + value
            );
        }

        return value;
    }

    private String cqlString(String value) {
        return value.replace("'", "''");
    }

    private void alterReplicationSafely(
            String keyspace,
            String datacenter,
            int targetReplicationFactor
    ) {
        Map<String, String> currentReplication = currentReplication(keyspace);
        String currentClass = currentReplication.get("class");
        String currentRfValue = currentReplication.get(datacenter);

        if (!isNetworkTopologyStrategy(currentClass)
                || currentRfValue == null) {
            alterKeyspace(keyspace, replication(datacenter, targetReplicationFactor));
            return;
        }

        int currentReplicationFactor;
        try {
            currentReplicationFactor = Integer.parseInt(currentRfValue);
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "Invalid existing replication factor for " + datacenter
                            + ": " + currentRfValue,
                    e
            );
        }

        while (currentReplicationFactor != targetReplicationFactor) {
            currentReplicationFactor += Integer.compare(
                    targetReplicationFactor,
                    currentReplicationFactor
            );
            alterKeyspace(
                    keyspace,
                    replication(datacenter, currentReplicationFactor)
            );
        }
    }

    private Map<String, String> currentReplication(String keyspace) {
        Row row = databaseClient.getSession().execute(
                "SELECT replication FROM system_schema.keyspaces "
                        + "WHERE keyspace_name = '" + keyspace + "'"
        ).one();

        if (row == null) {
            throw new IllegalStateException(
                    "Keyspace was not found after CREATE KEYSPACE: " + keyspace
            );
        }

        Map<String, String> replication = row.getMap(
                "replication",
                String.class,
                String.class
        );

        if (replication == null) {
            throw new IllegalStateException(
                    "Keyspace replication metadata is missing: " + keyspace
            );
        }

        return replication;
    }

    private void alterKeyspace(String keyspace, String replication) {
        databaseClient.getSession().execute(
                "ALTER KEYSPACE " + keyspace
                        + " WITH replication = " + replication
        );
    }

    private String replication(String datacenter, int replicationFactor) {
        return "{'class': 'NetworkTopologyStrategy', '"
                + datacenter
                + "': "
                + replicationFactor
                + "}";
    }

    private boolean isNetworkTopologyStrategy(String strategyClass) {
        return "NetworkTopologyStrategy".equals(strategyClass)
                || "org.apache.cassandra.locator.NetworkTopologyStrategy"
                .equals(strategyClass);
    }
}
