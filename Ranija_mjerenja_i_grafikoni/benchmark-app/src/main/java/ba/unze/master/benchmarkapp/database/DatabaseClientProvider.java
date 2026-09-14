package ba.unze.master.benchmarkapp.database;

import ba.unze.master.benchmarkapp.config.DatabaseProperties;
import ba.unze.master.benchmarkapp.database.cassandra.CassandraDatabaseClient;
import ba.unze.master.benchmarkapp.database.scylla.ScyllaDatabaseClient;
import org.springframework.stereotype.Component;

@Component
public class DatabaseClientProvider implements DatabaseClient {

    private final DatabaseClient delegate;

    public DatabaseClientProvider(
            DatabaseProperties properties
    ) {

        String type =
                properties.getType()
                        .trim()
                        .toLowerCase();

        switch (type) {

            case "cassandra" ->

                    delegate =
                            new CassandraDatabaseClient(
                                    properties
                            );

            case "scylla",
                 "scylladb" ->

                    delegate =
                            new ScyllaDatabaseClient(
                                    properties
                            );

            default ->

                    throw new IllegalArgumentException(
                            "Unsupported database type: "
                                    + properties.getType()
                                    + ". Supported values: "
                                    + "cassandra, scylla"
                    );
        }

        System.out.println();
        System.out.println(
                "========================================"
        );
        System.out.println(
                "DATABASE PROVIDER"
        );
        System.out.println(
                "Selected database: "
                        + type
        );
        System.out.println(
                "========================================"
        );
    }

    @Override
    public void connect() {
        delegate.connect();
    }

    @Override
    public void close() {
        delegate.close();
    }

    @Override
    public boolean isConnected() {
        return delegate.isConnected();
    }

    @Override
    public com.datastax.oss.driver.api.core.CqlSession getSession() {
        return delegate.getSession();
    }
}