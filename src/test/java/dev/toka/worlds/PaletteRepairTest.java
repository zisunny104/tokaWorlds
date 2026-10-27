package dev.toka.worlds;

import static org.junit.Assert.*;
import dev.toka.worlds.PaletteRepair.*;
import java.util.*;
import org.junit.Test;

public class PaletteRepairTest {
    @Test public void jobCountsLogsResumesAndPausesForPlayers() throws Exception {
        long[] list = new long[6];
        for (int i = 0; i < 6; i++) list[i] = BiomeRepair.pack(i, -i);
        Chunks chunks = (x, z) -> x == 5 ? Result.MISSING : x % 2 == 0 ? Result.REWRITTEN : Result.UNCHANGED;
        List<String> log = new ArrayList<>();
        List<Progress> saved = new ArrayList<>();
        boolean[] players = {false};
        Job job = new Job(list, Progress.start(), chunks, (x, z) -> log.add(x + "," + z), saved::add, 2);
        assertEquals(2, job.step(2, () -> players[0]));
        players[0] = true;
        assertEquals(0, job.step(10, () -> players[0]));
        assertEquals(2, job.progress().index());
        players[0] = false;
        Job resumed = new Job(list, saved.get(saved.size() - 1), chunks, (x, z) -> log.add(x + "," + z), saved::add, 2);
        resumed.step(10, () -> players[0]);
        assertTrue(resumed.done());
        assertEquals(new Progress(6, 3, 2, 1), resumed.progress());
        assertEquals(List.of("0,0", "2,-2", "4,-4"), log);
    }

    @Test public void scannerReadsKeysOfTheRightDimension() {
        byte[] overworld = {1, 0, 0, 0, 2, 0, 0, 0, ','};
        byte[] nether = {1, 0, 0, 0, 2, 0, 0, 0, 1, 0, 0, 0, ','};
        assertEquals(BiomeRepair.pack(1, 2), BiomeService.Scanner.chunkOf(overworld, 0));
        assertEquals(BiomeService.Scanner.NONE, BiomeService.Scanner.chunkOf(nether, 0));
        assertEquals(BiomeRepair.pack(1, 2), BiomeService.Scanner.chunkOf(nether, 1));
        assertEquals(BiomeService.Scanner.NONE, BiomeService.Scanner.chunkOf(nether, 2));
    }
}
