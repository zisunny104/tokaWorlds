package dev.toka.worlds;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Repairs chunks whose biomes are one unset value; core access sits behind small interfaces so it can be tested. */
final class BiomeRepair {
    static final int OCEAN = 0, PLAINS = 1, DESERT = 2, ICE = 12, UNSET = 255;
    enum Surface { WATER, SAND, SNOW, OTHER }

    /** One chunk as the job sees it: 256 biome bytes indexed (x<<4)|z, and the surface kind of every column. */
    interface ChunkView {
        byte[] biomes();
        Surface[] surfaces();
        void setBiomes(byte[] biomes);
    }
    interface Chunks {
        /** Null when the chunk does not exist. */
        ChunkView load(int x, int z);
        void save(int x, int z, ChunkView chunk);
        void release(int x, int z);
    }

    /** A chunk needs repair only when all its columns share one value and that value is ocean or unset. */
    static boolean needsRepair(byte[] biomes) {
        if (biomes.length != 256) return false;
        int first = biomes[0] & 0xFF;
        if (first != OCEAN && first != UNSET) return false;
        for (byte value : biomes) if (value != biomes[0]) return false;
        return true;
    }

    /** Biome per column from the surface, then each column takes the most common value of its 3x3 neighbourhood. */
    static byte[] infer(Surface[] surfaces, Map<Surface, Integer> mapping) {
        int[] raw = new int[256];
        for (int i = 0; i < 256; i++) raw[i] = mapping.getOrDefault(surfaces[i], PLAINS);
        byte[] out = new byte[256];
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            Map<Integer, Integer> votes = new HashMap<>();
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                int nx = x + dx, nz = z + dz;
                if (nx < 0 || nz < 0 || nx > 15 || nz > 15) continue;
                votes.merge(raw[(nx << 4) | nz], 1, Integer::sum);
            }
            int own = raw[(x << 4) | z], best = own, count = votes.get(own);
            for (var vote : votes.entrySet()) if (vote.getValue() > count) { best = vote.getKey(); count = vote.getValue(); }
            out[(x << 4) | z] = (byte) best;
        }
        return out;
    }

    /** Old biome bytes before a change: records of x, z, 256 bytes. The file is deleted once it is older than the given days. */
    static final class Backup {
        private final Path file;
        Backup(Path file) { this.file = file; }
        void append(int x, int z, byte[] old) throws IOException {
            Files.createDirectories(file.getParent());
            ByteBuffer record = ByteBuffer.allocate(264).putInt(x).putInt(z).put(old);
            Files.write(file, record.array(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        interface Sink { void accept(int x, int z, byte[] biomes) throws IOException; }
        /** Replays newest first so the oldest record of a chunk is written last and wins. */
        void restore(Sink sink) throws IOException {
            if (!Files.exists(file)) return;
            byte[] all = Files.readAllBytes(file);
            for (int at = all.length - all.length % 264 - 264; at >= 0; at -= 264) {
                ByteBuffer record = ByteBuffer.wrap(all, at, 264);
                int x = record.getInt(), z = record.getInt();
                byte[] biomes = new byte[256];
                record.get(biomes);
                sink.accept(x, z, biomes);
            }
        }
        boolean expire(long nowMillis, int days) throws IOException {
            if (!Files.exists(file) || nowMillis - Files.getLastModifiedTime(file).toMillis() < days * 86_400_000L) return false;
            Files.delete(file);
            return true;
        }
        Path file() { return file; }
    }

    /** Chunk coordinate list kept in a file: the scan is done once and the job resumes from an index. */
    static void writeList(Path file, long[] coords) throws IOException {
        Files.createDirectories(file.getParent());
        ByteBuffer buffer = ByteBuffer.allocate(coords.length * 8);
        for (long c : coords) buffer.putLong(c);
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(temp, buffer.array());
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
    }
    static long[] readList(Path file) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(Files.readAllBytes(file));
        long[] coords = new long[buffer.remaining() / 8];
        for (int i = 0; i < coords.length; i++) coords[i] = buffer.getLong();
        return coords;
    }
    static long pack(int x, int z) { return ((long) x << 32) | (z & 0xFFFFFFFFL); }
    static int unpackX(long c) { return (int) (c >> 32); }
    static int unpackZ(long c) { return (int) c; }

    /** Progress that survives restarts. */
    record Progress(int index, int repaired, int skipped) {
        static Progress start() { return new Progress(0, 0, 0); }
    }
    interface ProgressStore { void save(Progress progress) throws IOException; }

    /** Walks the list a few chunks per call; stops at once while players are online. */
    static final class Job {
        private final long[] list;
        private final Chunks chunks;
        private final Backup backup;
        private final ProgressStore store;
        private final Map<Surface, Integer> mapping;
        private final int saveEvery;
        private Progress progress;
        Job(long[] list, Progress progress, Chunks chunks, Backup backup, ProgressStore store, Map<Surface, Integer> mapping, int saveEvery) {
            this.list = list; this.progress = progress; this.chunks = chunks; this.backup = backup; this.store = store; this.mapping = mapping; this.saveEvery = saveEvery;
        }
        Progress progress() { return progress; }
        boolean done() { return progress.index() >= list.length; }

        /** Returns the number of chunks examined. */
        int step(int budget, BooleanSupplier playersOnline) throws IOException {
            int examined = 0;
            while (examined < budget && !done() && !playersOnline.getAsBoolean()) {
                long c = list[progress.index()];
                int x = unpackX(c), z = unpackZ(c);
                int repaired = progress.repaired(), skipped = progress.skipped();
                ChunkView view = chunks.load(x, z);
                try {
                    if (view != null && needsRepair(view.biomes())) {
                        backup.append(x, z, view.biomes().clone());
                        view.setBiomes(infer(view.surfaces(), mapping));
                        chunks.save(x, z, view);
                        repaired++;
                    } else skipped++;
                } finally {
                    if (view != null) chunks.release(x, z);
                }
                progress = new Progress(progress.index() + 1, repaired, skipped);
                examined++;
                if (progress.index() % saveEvery == 0) store.save(progress);
            }
            if (examined > 0) store.save(progress);
            return examined;
        }
    }
}
