package ba.unze.research;

import com.datastax.oss.driver.api.core.addresstranslation.AddressTranslator;
import com.datastax.oss.driver.api.core.context.DriverContext;
import java.net.InetSocketAddress;

public final class DockerTranslator implements AddressTranslator {
    public DockerTranslator(DriverContext ignored) {}
    public InetSocketAddress translate(InetSocketAddress address) {
        String ip = address.getAddress().getHostAddress();
        String prefix = System.getProperty("research.network.prefix", "");
        if (!prefix.isEmpty() && ip.startsWith(prefix)) {
            int node = Integer.parseInt(ip.substring(prefix.length())) - 2;
            if (node >= 0 && node < 3)
                return new InetSocketAddress("127.0.0.1", 19042 + node);
        }
        return address;
    }
    public void close() {}
}
