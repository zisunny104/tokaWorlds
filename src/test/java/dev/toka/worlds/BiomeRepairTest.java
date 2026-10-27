package dev.toka.worlds;

import static org.junit.Assert.*;
import dev.toka.worlds.BiomeRepair.*;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;

public class BiomeRepairTest {
    private static final Map<Surface, Integer> MAP = Map.of(Surface.WATER, 0, Surface.SAND, 2, Surface.SNOW, 12, Surface.OTHER, 1);

    private static byte[] filled(int v) { byte[] b = new byte[256]; Arrays.fill(b, (byte) v); return b; }
    private static Surface[] all(Surface s) { Surface[] a = new Surface[256]; Arrays.fill(a, s); return a; }

    @Test public void detectsOnlyUniformOceanOrUnset() {
        assertTrue(BiomeRepair.needsRepair(filled(0)));
        assertTrue(BiomeRepair.needsRepair(filled(255)));
        assertFalse(BiomeRepair.needsRepair(filled(1)));
        byte[] mixed = filled(0); mixed[5] = 1;
        assertFalse(BiomeRepair.needsRepair(mixed));
    }

    @Test public void infersFromSurfaceAndSmoothsLoneColumns() {
        Surface[] s = all(Surface.SAND);
        s[0] = Surface.OTHER;
        byte[] out = BiomeRepair.infer(s, MAP);
        assertEquals(2, out[0]);
        assertEquals(2, out[255]);
        assertEquals(12, BiomeRepair.infer(all(Surface.SNOW), MAP)[17]);
    }

    @Test public void listAndPackRoundTrip() throws Exception {
        Path dir = Files.createTempDirectory("biome");
        long[] coords = {BiomeRepair.pack(-3, 7), BiomeRepair.pack(100, -200)};
        BiomeRepair.writeList(dir.resolve("a.list"), coords);
        long[] back = BiomeRepair.readList(dir.resolve("a.list"));
        assertArrayEquals(coords, back);
        assertEquals(-3, BiomeRepair.unpackX(back[0]));
        assertEquals(-200, BiomeRepair.unpackZ(back[1]));
    }

    static final class Fake implements Chunks {
        final Map<Long, byte[]> data = new HashMap<>();
        final Set<Long> open = new HashSet<>();
        int saves;
        public ChunkView load(int x, int z) {
            byte[] b = data.get(BiomeRepair.pack(x, z));
            if (b == null) return null;
            open.add(BiomeRepair.pack(x, z));
            return new ChunkView() {
                public byte[] biomes() { return b; }
                public Surface[] surfaces() { return all(Surface.SAND); }
                public void setBiomes(byte[] n) { System.arraycopy(n, 0, b, 0, 256); }
            };
        }
        public void save(int x, int z, ChunkView c) { saves++; }
        public void release(int x, int z) { open.remove(BiomeRepair.pack(x, z)); }
    }

    @Test public void jobRepairsBacksUpResumesAndPausesForPlayers() throws Exception {
        Path dir = Files.createTempDirectory("biome");
        Fake fake = new Fake();
        long[] list = new long[6];
        for (int i = 0; i < 6; i++) { list[i] = BiomeRepair.pack(i, 0); fake.data.put(list[i], filled(i == 3 ? 1 : 0)); }
        fake.data.remove(list[5]);
        List<Progress> saved = new ArrayList<>();
        Backup backup = new Backup(dir.resolve("b.bak"));
        boolean[] players = {false};
        BiomeRepair.Job job = new BiomeRepair.Job(list, Progress.start(), fake, backup, saved::add, MAP, 2);
        assertEquals(2, job.step(2, () -> players[0]));
        players[0] = true;
        assertEquals(0, job.step(10, () -> players[0]));
        assertEquals(2, job.progress().index());
        Progress last = saved.get(saved.size() - 1);
        players[0] = false;
        BiomeRepair.Job resumed = new BiomeRepair.Job(list, last, fake, backup, saved::add, MAP, 2);
        resumed.step(10, () -> players[0]);
        assertTrue(resumed.done());
        assertEquals(4, resumed.progress().repaired());
        assertEquals(2, resumed.progress().skipped());
        assertEquals(4, fake.saves);
        assertTrue(fake.open.isEmpty());
        assertEquals(2, fake.data.get(list[0])[0]);
        assertEquals(1, fake.data.get(list[3])[0]);
    }

    @Test public void backupRestoresOldestStateAndExpires() throws Exception {
        Path dir = Files.createTempDirectory("biome");
        Backup backup = new Backup(dir.resolve("x/b.bak"));
        backup.append(1, 2, filled(0));
        backup.append(1, 2, filled(7));
        Map<Long, Integer> restored = new HashMap<>();
        backup.restore((x, z, b) -> restored.put(BiomeRepair.pack(x, z), (int) b[0]));
        assertEquals(Integer.valueOf(0), restored.get(BiomeRepair.pack(1, 2)));
        long now = System.currentTimeMillis();
        assertFalse(backup.expire(now, 7));
        assertTrue(backup.expire(now + 8 * 86_400_000L, 7));
        assertFalse(Files.exists(backup.file()));
    }
}
