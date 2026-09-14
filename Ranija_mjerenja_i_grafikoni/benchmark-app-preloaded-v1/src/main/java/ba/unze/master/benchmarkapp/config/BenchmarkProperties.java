package ba.unze.master.benchmarkapp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "benchmark")
public class BenchmarkProperties {

    private int warmupRuns = 1;
    private int measurementRuns = 3;
    private int concurrency = 32;

    private long sequentialWriteSize = 10000;

    private long concurrentWriteSize = 10000;

    private int readDatasetSize = 10000;

    private int readOperationCount = 10000;

    private int mixedOperationCount = 10000;

    private int progressReportSeconds = 30;

    private boolean abortOnOperationFailure;

    private int sustainedDurationSeconds = 10;

    private int sustainedTargetOpsPerSecond = 1000;

    private int interRunCooldownSeconds;

    private String testSeries = "MAIN_3_NODE_PILOT";

    public int getWarmupRuns() {
        return warmupRuns;
    }

    public void setWarmupRuns(int warmupRuns) {
        this.warmupRuns = warmupRuns;
    }

    public int getMeasurementRuns() {
        return measurementRuns;
    }

    public void setMeasurementRuns(int measurementRuns) {
        this.measurementRuns = measurementRuns;
    }

    public int getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(int concurrency) {
        this.concurrency = concurrency;
    }

    public long getSequentialWriteSize() {
        return sequentialWriteSize;
    }

    public void setSequentialWriteSize(long sequentialWriteSize) {
        this.sequentialWriteSize = sequentialWriteSize;
    }

    public long getConcurrentWriteSize() {
        return concurrentWriteSize;
    }

    public void setConcurrentWriteSize(long concurrentWriteSize) {
        this.concurrentWriteSize = concurrentWriteSize;
    }

    public int getReadDatasetSize() {
        return readDatasetSize;
    }

    public void setReadDatasetSize(int readDatasetSize) {
        this.readDatasetSize = readDatasetSize;
    }

    public int getReadOperationCount() {
        return readOperationCount;
    }

    public void setReadOperationCount(int readOperationCount) {
        this.readOperationCount = readOperationCount;
    }

    public int getMixedOperationCount() {
        return mixedOperationCount;
    }

    public void setMixedOperationCount(int mixedOperationCount) {
        this.mixedOperationCount = mixedOperationCount;
    }

    public int getProgressReportSeconds() {
        return progressReportSeconds;
    }

    public void setProgressReportSeconds(int progressReportSeconds) {
        this.progressReportSeconds = progressReportSeconds;
    }

    public boolean isAbortOnOperationFailure() {
        return abortOnOperationFailure;
    }

    public void setAbortOnOperationFailure(boolean abortOnOperationFailure) {
        this.abortOnOperationFailure = abortOnOperationFailure;
    }

    public int getSustainedDurationSeconds() {
        return sustainedDurationSeconds;
    }

    public void setSustainedDurationSeconds(int sustainedDurationSeconds) {
        this.sustainedDurationSeconds = sustainedDurationSeconds;
    }

    public int getSustainedTargetOpsPerSecond() {
        return sustainedTargetOpsPerSecond;
    }

    public void setSustainedTargetOpsPerSecond(int sustainedTargetOpsPerSecond) {
        this.sustainedTargetOpsPerSecond = sustainedTargetOpsPerSecond;
    }

    public int getInterRunCooldownSeconds() {
        return interRunCooldownSeconds;
    }

    public void setInterRunCooldownSeconds(int interRunCooldownSeconds) {
        this.interRunCooldownSeconds = interRunCooldownSeconds;
    }

    public String getTestSeries() {
        return testSeries;
    }

    public void setTestSeries(String testSeries) {
        this.testSeries = testSeries;
    }
}
