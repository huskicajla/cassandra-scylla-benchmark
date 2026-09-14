package ba.unze.master.benchmarkapp.database;

import com.datastax.oss.driver.api.core.CqlSession;

public interface DatabaseClient {

    void connect();

    void close();

    boolean isConnected();

    CqlSession getSession();
}