package ba.unze.master.benchmarkapp.preloaded;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "benchmark.preloaded")
public class PreloadedBenchmarkProperties {

    private boolean enabled;
    private long baselineSize = 1_000_000L;
    private String baselineId = "telemetry-50m-seed42-first-1000000-v1";
    private boolean forceReseed;
    private int seedConcurrency = 12;
    private int seedInFlightPerWorker = 2;
    private int seedRetryAttempts = 3;
    private int seedRetryDelayMillis = 200;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getBaselineSize() {
        return baselineSize;
    }

    public void setBaselineSize(long baselineSize) {
        this.baselineSize = baselineSize;
    }

    public String getBaselineId() {
        return baselineId;
    }

    public void setBaselineId(String baselineId) {
        this.baselineId = baselineId;
    }

    public boolean isForceReseed() {
        return forceReseed;
    }

    public void setForceReseed(boolean forceReseed) {
        this.forceReseed = forceReseed;
    }

    public int getSeedConcurrency() {
        return seedConcurrency;
    }

    public void setSeedConcurrency(int seedConcurrency) {
        this.seedConcurrency = seedConcurrency;
    }

    public int getSeedInFlightPerWorker() {
        return seedInFlightPerWorker;
    }

    public void setSeedInFlightPerWorker(int seedInFlightPerWorker) {
        this.seedInFlightPerWorker = seedInFlightPerWorker;
    }

    public int getSeedRetryAttempts() {
        return seedRetryAttempts;
    }

    public void setSeedRetryAttempts(int seedRetryAttempts) {
        this.seedRetryAttempts = seedRetryAttempts;
    }

    public int getSeedRetryDelayMillis() {
        return seedRetryDelayMillis;
    }

    public void setSeedRetryDelayMillis(int seedRetryDelayMillis) {
        this.seedRetryDelayMillis = seedRetryDelayMillis;
    }
}
