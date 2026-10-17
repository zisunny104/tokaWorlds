package dev.toka.worlds;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ResendSliceTest {
    @Test
    public void slicesCoverTheRangeOnceInBatches() {
        int x = -10, last = 10, ticks = 0, covered = 0;
        while (x <= last) {
            int end = Main.sliceEnd(x, last);
            covered += end - x + 1;
            x = end + 1;
            ticks++;
        }
        assertEquals(21, covered);
        assertEquals((21 + Main.RESEND_SLICES - 1) / Main.RESEND_SLICES, ticks);
    }

    @Test
    public void lastBatchIsClamped() {
        assertEquals(5, Main.sliceEnd(5, 5));
    }
}
