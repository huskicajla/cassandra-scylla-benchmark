package ba.unze.master.benchmarkapp.database.cassandra;

import ba.unze.master.benchmarkapp.config.DatabaseProperties;
import ba.unze.master.benchmarkapp.database.DatabaseClient;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.datastax.oss.driver.api.core.config.DefaultDriverOption;
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;
import com.datastax.oss.driver.api.core.metadata.Node;

import java.net.InetSocketAddress;
import java.time.Duration;

public class CassandraDatabaseClient implements DatabaseClient {

    private final DatabaseProperties properties;

    private CqlSession session;

    public CassandraDatabaseClient(
            DatabaseProperties properties
    ) {
        this.properties = properties;
    }

    @Override
    public void connect() {

        DriverConfigLoader configLoader =
                DriverConfigLoader
                        .programmaticBuilder()
                        .withDuration(
                                DefaultDriverOption.REQUEST_TIMEOUT,
                                Duration.ofSeconds(properties.getRequestTimeoutSeconds())
                        )
                        .build();

        CqlSessionBuilder builder =
                CqlSession.builder()
                        .addContactPoint(
                                new InetSocketAddress(
                                        properties.getHost(),
                                        properties.getPort()
                                )
                        )
                        .withLocalDatacenter(
                                properties.getLocalDatacenter()
                        )
                        .withConfigLoader(
                                configLoader
                        );

        session = builder.build();

        System.out.println();
        System.out.println("========================================");
        System.out.println("CASSANDRA SESSION CREATED");
        System.out.println("========================================");
        System.out.println("Request timeout: " + properties.getRequestTimeoutSeconds() + " seconds");

        printNodes();

        System.out.println("========================================");
    }

    private void printNodes() {

        for (Node node :
                session.getMetadata()
                        .getNodes()
                        .values()) {

            System.out.println(
                    "Node: "
                            + node.getEndPoint()
                            + " | State: "
                            + node.getState()
            );
        }
    }

    @Override
    public void close() {

        if (session != null) {
            session.close();
            session = null;
        }
    }

    @Override
    public boolean isConnected() {

        return session != null
                && !session.isClosed();
    }

    @Override
    public CqlSession getSession() {

        if (!isConnected()) {

            throw new IllegalStateException(
                    "Cassandra session is not connected."
            );
        }

        return session;
    }
}
