package ba.unze.master.benchmarkapp.config;

import com.datastax.oss.driver.api.core.addresstranslation.AddressTranslator;
import com.datastax.oss.driver.api.core.context.DriverContext;

import java.net.InetSocketAddress;

public class DockerAddressTranslator
        implements AddressTranslator {

    private static final java.util.Map<String, Integer> HOST_PORTS =
            java.util.Map.ofEntries(
                    java.util.Map.entry("172.18.0.2", 9042),
                    java.util.Map.entry("172.18.0.3", 9043),
                    java.util.Map.entry("172.18.0.4", 9044),

                    java.util.Map.entry("172.19.0.2", 9042),
                    java.util.Map.entry("172.19.0.3", 9043),
                    java.util.Map.entry("172.19.0.4", 9044),

                    java.util.Map.entry("172.20.0.2", 9042),
                    java.util.Map.entry("172.20.0.3", 9043),
                    java.util.Map.entry("172.20.0.4", 9044),

                    java.util.Map.entry("172.21.0.2", 9042),
                    java.util.Map.entry("172.22.0.2", 9042),
                    java.util.Map.entry("172.23.0.2", 9042),
                    java.util.Map.entry("172.23.0.3", 9043),
                    java.util.Map.entry("172.24.0.2", 9042),
                    java.util.Map.entry("172.24.0.3", 9043)
            );

    public DockerAddressTranslator(
            DriverContext context
    ) {
    }

    @Override
    public InetSocketAddress translate(
            InetSocketAddress address
    ) {

        String host =
                address.getAddress().getHostAddress();

        Integer hostPort = HOST_PORTS.get(host);

        if (hostPort == null) {
            return address;
        }

        return new InetSocketAddress("localhost", hostPort);
    }

    @Override
    public void close() {
    }
}
