package ba.unze.master.benchmarkapp.benchmark;

import ba.unze.master.benchmarkapp.config.BenchmarkProperties;
import org.springframework.stereotype.Component;

@Component
public class BenchmarkQualityGate {

    private final BenchmarkProperties properties;

    public BenchmarkQualityGate(BenchmarkProperties properties) {
        this.properties = properties;
    }

    public void requireSuccessfulOperations(BenchmarkResult result) {
        if (properties.isAbortOnOperationFailure()
                && result.failedOperations() > 0) {
            throw new IllegalStateException(
                    "Quality gate stopped " + result.context().operation()
                            + " because " + result.failedOperations()
                            + " operations failed. Raw evidence was saved."
            );
        }
    }
}
