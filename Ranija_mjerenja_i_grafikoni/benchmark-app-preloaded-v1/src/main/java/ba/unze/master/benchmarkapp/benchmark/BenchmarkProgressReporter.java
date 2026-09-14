package ba.unze.master.benchmarkapp.benchmark;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class BenchmarkProgressReporter {

    private final String label;
    private final long plannedOperations;
    private final long intervalNanos;
    private final long startedNanos;
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong succeeded = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong nextReportNanos;

    public BenchmarkProgressReporter(
            BenchmarkRunContext context,
            long plannedOperations,
            int reportSeconds
    ) {
        this.label = context.operation() + " / " + context.workloadProfile()
                + " / " + context.runType() + " " + context.runNumber();
        this.plannedOperations = Math.max(plannedOperations, 1);
        this.intervalNanos = TimeUnit.SECONDS.toNanos(Math.max(reportSeconds, 1));
        this.startedNanos = System.nanoTime();
        this.nextReportNanos = new AtomicLong(startedNanos + intervalNanos);
        System.out.println("Progress: " + label + " started (0/"
                + this.plannedOperations + ").");
    }

    public void recordSuccess() {
        completed.incrementAndGet();
        succeeded.incrementAndGet();
        reportIfDue(false);
    }

    public void recordFailure() {
        completed.incrementAndGet();
        failed.incrementAndGet();
        reportIfDue(false);
    }

    public void finish() {
        reportIfDue(true);
    }

    private void reportIfDue(boolean force) {
        long now = System.nanoTime();
        long expected = nextReportNanos.get();

        if (!force && now < expected) {
            return;
        }

        if (!force && !nextReportNanos.compareAndSet(
                expected,
                now + intervalNanos
        )) {
            return;
        }

        long done = completed.get();
        long elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(now - startedNanos);
        double percentage = Math.min(100.0, done * 100.0 / plannedOperations);

        System.out.printf(
                "Progress: %s | %.1f%% (%d/%d) | success=%d | failed=%d | elapsed=%ds%n",
                label,
                percentage,
                done,
                plannedOperations,
                succeeded.get(),
                failed.get(),
                elapsedSeconds
        );
    }
}
