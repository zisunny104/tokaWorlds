package dev.toka.worlds;

import cn.nukkit.Server;
import cn.nukkit.block.Block;
import cn.nukkit.level.DimensionData;
import cn.nukkit.level.Level;
import cn.nukkit.level.format.FullChunk;
import cn.nukkit.level.format.LevelProvider;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.scheduler.TaskHandler;
import com.google.gson.JsonObject;
import dev.toka.i18n.Messages;
import dev.toka.storage.DocumentStore;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Runs BiomeRepair on the configured worlds while nobody is online. Chunk work is on the main thread in small steps; only the one-off chunk listing runs on another thread. */
final class BiomeService {
    private final PluginBase plugin;
    private final DocumentStore store;
    private final Messages m;
    private final Consumer<String> info, warn;
    private final Map<String, BiomeRepair.Job> jobs = new HashMap<>();
    private final Set<String> scanning = new HashSet<>();
    private final Set<String> finished = new HashSet<>();
    private TaskHandler task;
    private int emptyTicks;
    private boolean paused;

    BiomeService(PluginBase plugin, DocumentStore store, Messages m, Consumer<String> info, Consumer<String> warn) {
        this.plugin = plugin; this.store = store; this.m = m; this.info = info; this.warn = warn;
    }

    private Path dir(String world) { return plugin.getDataFolder().toPath().resolve("biome-repair").resolve(world); }
    private Path list(String world) { return dir(world).resolve("chunks.list"); }
    private Path backupFile(String world) { return dir(world).resolve("biomes.bak"); }
    private String key(String world) { return "biome-" + world; }
    private int config(String name, int fallback) { return plugin.getConfig().getInt("biome-repair." + name, fallback); }

    void start() {
        if (!plugin.getConfig().getBoolean("biome-repair.enabled", false)) return;
        task = plugin.getServer().getScheduler().scheduleRepeatingTask(plugin, this::tick, 20);
    }
    void stop() { if (task != null) task.cancel(); task = null; }
    void pause(boolean value) { paused = value; }

    private Map<BiomeRepair.Surface, Integer> mapping() {
        Map<BiomeRepair.Surface, Integer> map = new EnumMap<>(BiomeRepair.Surface.class);
        map.put(BiomeRepair.Surface.WATER, config("biome-water", BiomeRepair.OCEAN));
        map.put(BiomeRepair.Surface.SAND, config("biome-sand", BiomeRepair.DESERT));
        map.put(BiomeRepair.Surface.SNOW, config("biome-snow", BiomeRepair.ICE));
        map.put(BiomeRepair.Surface.OTHER, config("biome-other", BiomeRepair.PLAINS));
        return map;
    }

    /** Every second: count how long the server has been empty, then give each configured world one step. */
    private void tick() {
        Server server = plugin.getServer();
        if (!server.getOnlinePlayers().isEmpty()) { emptyTicks = 0; return; }
        if (paused) return;
        emptyTicks++;
        if (emptyTicks < config("idle-seconds", 120)) return;
        for (String world : plugin.getConfig().getStringList("biome-repair.worlds")) {
            if (finished.contains(world)) continue;
            Level level = server.getLevelByName(world);
            if (level == null) continue;
            try { work(world, level); } catch (Exception error) { finished.add(world); warn.accept(m.text("log.biome.error", world, String.valueOf(error))); }
        }
    }

    private void work(String world, Level level) throws IOException {
        BiomeRepair.Backup backup = new BiomeRepair.Backup(backupFile(world));
        if (backup.expire(System.currentTimeMillis(), config("backup-days", 7))) info.accept(m.text("log.biome.expired", world));
        BiomeRepair.Job job = jobs.get(world);
        if (job == null) {
            if (!Files.exists(list(world))) { startScan(world, level); return; }
            Optional<JsonObject> saved = store.load(key(world));
            BiomeRepair.Progress progress = saved.map(o -> new BiomeRepair.Progress(o.get("index").getAsInt(), o.get("repaired").getAsInt(), o.get("skipped").getAsInt())).orElse(BiomeRepair.Progress.start());
            job = new BiomeRepair.Job(BiomeRepair.readList(list(world)), progress, new Access(level), backup, p -> save(world, p), mapping(), 64);
            jobs.put(world, job);
            info.accept(m.text("log.biome.resumed", world, progress.index()));
        }
        if (job.done()) { finish(world, job); return; }
        job.step(config("chunks-per-second", 4), () -> !plugin.getServer().getOnlinePlayers().isEmpty());
        if (job.done()) finish(world, job);
    }

    private void finish(String world, BiomeRepair.Job job) {
        finished.add(world);
        info.accept(m.text("log.biome.done", world, job.progress().repaired(), job.progress().skipped()));
    }

    private void save(String world, BiomeRepair.Progress p) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("index", p.index()); o.addProperty("repaired", p.repaired()); o.addProperty("skipped", p.skipped());
        store.save(key(world), o);
    }

    String status() {
        StringBuilder out = new StringBuilder();
        for (String world : plugin.getConfig().getStringList("biome-repair.worlds")) {
            BiomeRepair.Job job = jobs.get(world);
            out.append(world).append(": ");
            if (finished.contains(world)) out.append(m.text("biome.st.done"));
            else if (scanning.contains(world)) out.append(m.text("biome.st.listing"));
            else if (job != null) out.append(m.text("biome.st.progress", job.progress().index(), job.progress().repaired()));
            else out.append(m.text("biome.st.waiting"));
            out.append('\n');
        }
        return out.toString().stripTrailing();
    }

    /** Restores the old biomes from the backup of one world; chunks are written by the main thread. */
    int restore(String world) throws IOException {
        Level level = plugin.getServer().getLevelByName(world);
        if (level == null) throw new IllegalStateException(m.text("err.biome.notLoaded", world));
        int[] count = {0};
        Access access = new Access(level);
        new BiomeRepair.Backup(backupFile(world)).restore((x, z, biomes) -> {
            BiomeRepair.ChunkView view = access.load(x, z);
            if (view == null) return;
            try { view.setBiomes(biomes); access.save(x, z, view); count[0]++; } finally { access.release(x, z); }
        });
        store.delete(key(world));
        jobs.remove(world); finished.add(world);
        return count[0];
    }

    /** Lists the chunks of the world once, on its own thread, by reading the keys of the world database. */
    private void startScan(String world, Level level) {
        if (!scanning.add(world)) return;
        LevelProvider provider = level.getProvider();
        info.accept(m.text("log.biome.listing", world));
        Thread thread = new Thread(() -> {
            try {
                long[] coords = Scanner.keys(provider, level.getDimension());
                BiomeRepair.writeList(list(world), coords);
                info.accept(m.text("log.biome.found", world, coords.length));
            } catch (Throwable error) {
                finished.add(world);
                warn.accept(m.text("log.biome.scanFailed", world, String.valueOf(error)));
            } finally { scanning.remove(world); }
        }, "toka-biome-scan");
        thread.setDaemon(true);
        thread.start();
    }

    /** Reads chunk keys (x, z little-endian, then tag ',' or 'v') through the provider's database object. */
    static final class Scanner {
        static long[] keys(Object provider) throws Exception { return keys(provider, 0); }
        static long[] keys(Object provider, int dimension) throws Exception {
            var field = provider.getClass().getDeclaredField("db");
            field.setAccessible(true);
            Object db = field.get(provider);
            Method open = db.getClass().getMethod("iterator");
            open.setAccessible(true);
            Object iterator = open.invoke(db);
            Class<?> type = iterator.getClass();
            Method seek = find(type, "seekToFirst"), has = find(type, "hasNext"), next = find(type, "next"), close = find(type, "close");
            Set<Long> found = new TreeSet<>();
            try {
                seek.invoke(iterator);
                while ((boolean) has.invoke(iterator)) {
                    byte[] key = (byte[]) ((Map.Entry<?, ?>) next.invoke(iterator)).getKey();
                    long coord = chunkOf(key, dimension);
                    if (coord != NONE) found.add(coord);
                }
            } finally { close.invoke(iterator); }
            return found.stream().mapToLong(Long::longValue).toArray();
        }
        static final long NONE = Long.MIN_VALUE;
        static long chunkOf(byte[] key) { return chunkOf(key, 0); }
        /** Overworld keys are x, z, tag (9 bytes); other dimensions add the dimension as a little-endian int before the tag (13 bytes). */
        static long chunkOf(byte[] key, int dimension) {
            int tag = dimension == 0 ? 8 : 12;
            if (key.length != tag + 1 || key[tag] != ',' && key[tag] != 'v') return NONE;
            if (dimension != 0 && le(key, 8) != dimension) return NONE;
            return BiomeRepair.pack(le(key, 0), le(key, 4));
        }
        private static int le(byte[] key, int at) { return (key[at] & 0xff) | (key[at + 1] & 0xff) << 8 | (key[at + 2] & 0xff) << 16 | (key[at + 3] & 0xff) << 24; }
        private static Method find(Class<?> type, String name) throws NoSuchMethodException {
            for (Method method : type.getMethods()) if (method.getName().equals(name) && method.getParameterCount() == 0) { method.setAccessible(true); return method; }
            throw new NoSuchMethodException(name);
        }
    }

    /** Core chunk access. A chunk that was not loaded is unloaded again after saving. */
    private static final class Access implements BiomeRepair.Chunks {
        private final Level level;
        private final Set<Long> opened = new HashSet<>();
        Access(Level level) { this.level = level; }

        public BiomeRepair.ChunkView load(int x, int z) {
            LevelProvider provider = level.getProvider();
            boolean was = provider.isChunkLoaded(x, z);
            FullChunk chunk = provider.getChunk(x, z, false);
            if (chunk == null) return null;
            if (!was) opened.add(BiomeRepair.pack(x, z));
            return new View(level, chunk);
        }
        public void save(int x, int z, BiomeRepair.ChunkView view) {
            FullChunk chunk = ((View) view).chunk;
            chunk.setChanged();
            level.getProvider().saveChunk(x, z, chunk);
        }
        public void release(int x, int z) {
            if (opened.remove(BiomeRepair.pack(x, z))) level.getProvider().unloadChunk(x, z, true);
        }
    }

    private static final class View implements BiomeRepair.ChunkView {
        private final Level level;
        final FullChunk chunk;
        View(Level level, FullChunk chunk) { this.level = level; this.chunk = chunk; }

        /** For 3D chunks the columns are read from every section; a chunk with different sections reports mixed values so it is left alone. */
        public byte[] biomes() {
            byte[] flat = chunk.getBiomeIdArray().clone();
            if (!chunk.has3dBiomes()) return flat;
            DimensionData dimension = level.getDimensionData();
            int first = Integer.MIN_VALUE;
            for (int section = dimension.getMinHeight() >> 4; section < dimension.getMaxHeight() >> 4; section++) {
                var storage = chunk.getBiomeStorage(section);
                for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                    int value = storage.getBlock(x, 0, z);
                    if (first == Integer.MIN_VALUE) first = value;
                    if (value != first) { flat[0] = 0; flat[1] = 1; return flat; }
                }
            }
            Arrays.fill(flat, (byte) first);
            return flat;
        }
        public BiomeRepair.Surface[] surfaces() {
            BiomeRepair.Surface[] out = new BiomeRepair.Surface[256];
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                int y = chunk.getHighestBlockAt(x, z);
                int id = chunk.getBlockId(x, y, z);
                out[(x << 4) | z] = id == Block.WATER || id == Block.STILL_WATER ? BiomeRepair.Surface.WATER
                    : id == Block.SAND ? BiomeRepair.Surface.SAND
                    : id == Block.SNOW_LAYER || id == Block.ICE || id == Block.SNOW_BLOCK || id == Block.PACKED_ICE ? BiomeRepair.Surface.SNOW
                    : BiomeRepair.Surface.OTHER;
            }
            return out;
        }
        /** setBiomeIdArray also rebuilds the 3D biome storage of a 3D chunk from the column values. */
        public void setBiomes(byte[] biomes) { chunk.setBiomeIdArray(biomes.clone()); }
    }
}
