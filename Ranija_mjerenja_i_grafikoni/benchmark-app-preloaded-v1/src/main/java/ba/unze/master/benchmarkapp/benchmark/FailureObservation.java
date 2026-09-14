package ba.unze.master.benchmarkapp.benchmark;

public record FailureObservation(
        String occurredAtUtc,
        long operationIndex,
        double latencyMs,
        String exceptionType,
        String exceptionMessage,
        String rootCauseType,
        String rootCauseMessage
) {
}
