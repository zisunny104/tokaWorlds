package dev.toka.worlds;

import cn.nukkit.Server;
import cn.nukkit.level.Level;
import cn.nukkit.level.format.FullChunk;
import cn.nukkit.level.format.LevelProvider;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.scheduler.TaskHandler;
import com.google.gson.JsonObject;
import dev.toka.i18n.Messages;
import dev.toka.storage.DocumentStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Full scan: loads each chunk of the configured worlds while nobody is online so the core's read-time palette tidy gets written back. Needs a core that tidies on read. */
final class PaletteService {
    private final PluginBase plugin;
    private final DocumentStore store;
    private final Messages m;
    private final Consumer<String> info, warn;
    private final Map<String, PaletteRepair.Job> jobs = new HashMap<>();
    private final Map<String, Long> sizeBefore = new HashMap<>();
    private final Set<String> scanning = new HashSet<>();
    private final Set<String> finished = new HashSet<>();
    private TaskHandler task;
    private int emptyTicks;
    private boolean paused;
    private final Map<String, Long> elapsedMs = new HashMap<>();
    private int budget;

    PaletteService(PluginBase plugin, DocumentStore store, Messages m, Consumer<String> info, Consumer<String> warn) {
        this.plugin = plugin; this.store = store; this.m = m; this.info = info; this.warn = warn;
    }

    private Path dir(String world) { return plugin.getDataFolder().toPath().resolve("palette-repair").resolve(world); }
    private Path list(String world) { return dir(world).resolve("chunks.list"); }
    private Path rewrittenLog(String world) { return dir(world).resolve("rewritten.log"); }
    private String key(String world) { return "palette-" + world; }
    private int config(String name, int fallback) { return plugin.getConfig().getInt("palette-repair." + name, fallback); }
    private List<String> worlds() { return plugin.getConfig().getStringList("palette-repair.worlds"); }

    void start() {
        if (!plugin.getConfig().getBoolean("palette-repair.enabled", true)) return;
        task = plugin.getServer().getScheduler().scheduleRepeatingTask(plugin, this::tick, 20);
    }
    void stop() { if (task != null) task.cancel(); task = null; }
    void pause(boolean value) { paused = value; }

    /** Every second: count how long the server has been empty, then give each configured world one step. */
    private void tick() {
        Server server = plugin.getServer();
        if (!server.getOnlinePlayers().isEmpty()) { emptyTicks = 0; return; }
        if (paused) return;
        emptyTicks++;
        if (emptyTicks < config("idle-seconds", 10)) return;
        if (server.getTicksPerSecond() < config("min-tps", 18)) return;
        // worlds go in the configured order, one at a time; a world that is not loaded is skipped
        for (String world : worlds()) {
            if (finished.contains(world)) continue;
            Level level = server.getLevelByName(world);
            if (level == null) continue;
            try { work(world, level); } catch (Exception error) { finished.add(world); warn.accept(m.text("log.palette.error", world, String.valueOf(error))); }
            return;
        }
    }

    private void work(String world, Level level) throws IOException {
        PaletteRepair.Job job = jobs.get(world);
        if (job == null) {
            Optional<JsonObject> saved = store.load(key(world));
            if (saved.isPresent() && saved.get().has("done")) { finished.add(world); return; }
            if (!Files.exists(list(world))) { startScan(world, level); return; }
            PaletteRepair.Progress progress = saved.map(o -> new PaletteRepair.Progress(o.get("index").getAsInt(), o.get("rewritten").getAsInt(), o.get("unchanged").getAsInt(), o.get("missing").getAsInt())).orElse(PaletteRepair.Progress.start());
            long before = saved.isPresent() && saved.get().has("sizeBefore") ? saved.get().get("sizeBefore").getAsLong() : size(level);
            sizeBefore.put(world, before);
            elapsedMs.put(world, saved.isPresent() && saved.get().has("elapsedMs") ? saved.get().get("elapsedMs").getAsLong() : 0L);
            job = new PaletteRepair.Job(BiomeRepair.readList(list(world)), progress, new Access(level), (x, z) -> logRewritten(world, x, z), p -> save(world, p, null), 64);
            jobs.put(world, job);
            info.accept(m.text("log.palette.resumed", world, progress.index(), before));
        }
        if (job.done()) { finish(world, level, job); return; }
        int max = Math.max(1, config("chunks-per-second", 32));
        if (budget < 1 || budget > max) budget = max;
        long start = System.nanoTime();
        job.step(budget, () -> !plugin.getServer().getOnlinePlayers().isEmpty());
        long took = (System.nanoTime() - start) / 1_000_000;
        elapsedMs.merge(world, took, Long::sum);
        // keep one step under the tick budget: halve when slow, creep back up when fast
        if (took > config("max-step-ms", 30)) budget = Math.max(1, budget / 2);
        else if (took < config("max-step-ms", 30) / 2) budget = Math.min(max, budget + 1);
        if (job.done()) finish(world, level, job);
    }

    private void finish(String world, Level level, PaletteRepair.Job job) throws IOException {
        finished.add(world);
        long before = sizeBefore.getOrDefault(world, 0L), after = size(level);
        PaletteRepair.Progress p = job.progress();
        save(world, p, after);
        info.accept(m.text("log.palette.done", world, p.rewritten(), p.unchanged(), p.missing(), before, after, elapsedMs.getOrDefault(world, 0L) / 1000));
    }

    private void save(String world, PaletteRepair.Progress p, Long after) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("index", p.index()); o.addProperty("rewritten", p.rewritten()); o.addProperty("unchanged", p.unchanged()); o.addProperty("missing", p.missing());
        o.addProperty("sizeBefore", sizeBefore.getOrDefault(world, 0L));
        o.addProperty("elapsedMs", elapsedMs.getOrDefault(world, 0L));
        if (after != null) { o.addProperty("sizeAfter", after); o.addProperty("done", true); }
        store.save(key(world), o);
    }

    /** Which chunks were rewritten, one "x z" line each; the only record, no data is backed up because only the palette order and size change. */
    private void logRewritten(String world, int x, int z) throws IOException {
        Files.createDirectories(dir(world));
        Files.writeString(rewrittenLog(world), x + " " + z + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Total bytes of the world folder; leveldb keeps old files until it compacts, so the number may only drop later. */
    private long size(Level level) {
        try (Stream<Path> files = Files.walk(Path.of(level.getProvider().getPath()))) {
            return files.filter(Files::isRegularFile).mapToLong(f -> { try { return Files.size(f); } catch (IOException e) { return 0; } }).sum();
        } catch (IOException e) { return 0; }
    }

    String status() {
        StringBuilder out = new StringBuilder();
        for (String world : worlds()) {
            PaletteRepair.Job job = jobs.get(world);
            out.append(world).append(": ");
            if (finished.contains(world)) out.append(m.text("palette.st.done"));
            else if (scanning.contains(world)) out.append(m.text("palette.st.listing"));
            else if (job != null) out.append(m.text("palette.st.progress", job.progress().index(), job.progress().rewritten()));
            else out.append(m.text("palette.st.waiting"));
            out.append('\n');
        }
        return out.toString().stripTrailing();
    }

    /** Lists the chunks of the world once, on its own thread, by reading the keys of the world database. */
    private void startScan(String world, Level level) {
        if (!scanning.add(world)) return;
        LevelProvider provider = level.getProvider();
        int dimension = level.getDimension();
        info.accept(m.text("log.palette.listing", world));
        Thread thread = new Thread(() -> {
            try {
                long[] coords = BiomeService.Scanner.keys(provider, dimension);
                BiomeRepair.writeList(list(world), coords);
                info.accept(m.text("log.palette.found", world, coords.length));
            } catch (Throwable error) {
                finished.add(world);
                Throwable cause = error instanceof java.lang.reflect.InvocationTargetException && error.getCause() != null ? error.getCause() : error;
                StringBuilder where = new StringBuilder(String.valueOf(cause));
                for (StackTraceElement frame : Arrays.copyOf(cause.getStackTrace(), Math.min(4, cause.getStackTrace().length))) where.append(" at ").append(frame);
                warn.accept(m.text("log.palette.scanFailed", world, where.toString()));
            } finally { scanning.remove(world); }
        }, "toka-palette-scan");
        thread.setDaemon(true);
        thread.start();
    }

    /** Core chunk access. A chunk that was not loaded is unloaded again after the check. */
    private static final class Access implements PaletteRepair.Chunks {
        private final Level level;
        Access(Level level) { this.level = level; }

        public PaletteRepair.Result process(int x, int z) {
            LevelProvider provider = level.getProvider();
            boolean was = provider.isChunkLoaded(x, z);
            FullChunk chunk = provider.getChunk(x, z, false);
            if (chunk == null) return PaletteRepair.Result.MISSING;
            try {
                if (!chunk.hasChanged()) return PaletteRepair.Result.UNCHANGED;
                provider.saveChunk(x, z, chunk);
                return PaletteRepair.Result.REWRITTEN;
            } finally {
                if (!was) provider.unloadChunk(x, z, true);
            }
        }
    }
}
