package ba.unze.master.benchmarkapp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "benchmark.dataset")
public class DatasetProperties {

    private String path =
            "../data-generator/output/telemetry-50m";

    private long size =
            1_000_000L;

    public String getPath() {
        return path;
    }

    public void setPath(
            String path
    ) {
        this.path = path;
    }

    public long getSize() {
        return size;
    }

    public void setSize(
            long size
    ) {
        this.size = size;
    }
}