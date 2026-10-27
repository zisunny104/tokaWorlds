package dev.toka.worlds;

import java.io.IOException;
import java.util.function.BooleanSupplier;

/** Loads every chunk of a world once and saves the ones the core marked as changed while reading (tidied palettes); core access sits behind a small interface so it can be tested. */
final class PaletteRepair {
    enum Result { MISSING, UNCHANGED, REWRITTEN }

    interface Chunks {
        /** Loads the chunk, saves it only when the core marked it changed by reading, then releases it. */
        Result process(int x, int z);
    }
    interface Store { void save(Progress progress) throws IOException; }
    interface Log { void rewritten(int x, int z) throws IOException; }

    /** Progress that survives restarts. */
    record Progress(int index, int rewritten, int unchanged, int missing) {
        static Progress start() { return new Progress(0, 0, 0, 0); }
    }

    /** Walks the chunk list a few chunks per call; stops at once while players are online. */
    static final class Job {
        private final long[] list;
        private final Chunks chunks;
        private final Log log;
        private final Store store;
        private final int saveEvery;
        private Progress progress;

        Job(long[] list, Progress progress, Chunks chunks, Log log, Store store, int saveEvery) {
            this.list = list; this.progress = progress; this.chunks = chunks; this.log = log; this.store = store; this.saveEvery = saveEvery;
        }
        Progress progress() { return progress; }
        boolean done() { return progress.index() >= list.length; }

        /** Returns the number of chunks examined. */
        int step(int budget, BooleanSupplier stop) throws IOException { return step(budget, Integer.MAX_VALUE, stop); }

        /**
         * Chunks that exist count against the budget; a position without a chunk costs one look-up only, so at most the given number of
         * look-ups is made in a call.
         */
        int step(int budget, int lookups, BooleanSupplier stop) throws IOException {
            int examined = 0, used = 0;
            while (used < budget && examined < lookups && !done() && !stop.getAsBoolean()) {
                long c = list[progress.index()];
                int x = BiomeRepair.unpackX(c), z = BiomeRepair.unpackZ(c);
                int rewritten = progress.rewritten(), unchanged = progress.unchanged(), missing = progress.missing();
                switch (chunks.process(x, z)) {
                    case REWRITTEN -> { log.rewritten(x, z); rewritten++; used++; }
                    case UNCHANGED -> { unchanged++; used++; }
                    case MISSING -> missing++;
                }
                progress = new Progress(progress.index() + 1, rewritten, unchanged, missing);
                examined++;
                if (progress.index() % saveEvery == 0) store.save(progress);
            }
            if (examined > 0) store.save(progress);
            return examined;
        }
    }
}
