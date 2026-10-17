package dev.toka.worlds;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/** Step timer for world operations; the clock is injected so it can be tested. */
final class Timing {
    /** Used only when config.yml (older installs) has no slow-operation-ms. */
    static final long DEFAULT_SLOW_MS = 200;
    private final LongSupplier nanos;
    private final long start;
    private long last;
    private final List<String> steps = new ArrayList<>();

    Timing(LongSupplier nanos) {
        this.nanos = nanos;
        this.start = nanos.getAsLong();
        this.last = start;
    }

    /** Records the time since the previous mark under the given label. */
    void mark(String label) {
        long now = nanos.getAsLong();
        steps.add(label + " " + (now - last) / 1_000_000 + "ms");
        last = now;
    }

    long totalMs() {
        return (nanos.getAsLong() - start) / 1_000_000;
    }

    String detail() {
        return String.join(", ", steps);
    }

    /** Slow means strictly above the threshold. */
    static boolean slow(long ms, long thresholdMs) {
        return ms > thresholdMs;
    }
}
