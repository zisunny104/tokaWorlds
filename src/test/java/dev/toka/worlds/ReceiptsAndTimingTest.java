package dev.toka.worlds;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.toka.i18n.Messages;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public class ReceiptsAndTimingTest {
    private Messages messages() {
        return new Messages(getClass().getClassLoader(), Path.of("target", "no-lang-folder"), "zh-TW", null);
    }

    @Test
    public void newReceiptKeepsCodeArgsAndDescription() {
        JsonObject receipt = Receipts.write(Receipts.PORTAL, new String[] {"3"}, "backup.json", messages());
        assertEquals("portal", receipt.get("code").getAsString());
        assertEquals("3", receipt.getAsJsonArray("args").get(0).getAsString());
        assertEquals("backup.json", receipt.get("backup").getAsString());
        String text = messages().text("receipt.portal", "3");
        assertEquals(text, receipt.get("description").getAsString());
        assertEquals(text, Receipts.display(receipt, messages()));
        assertTrue(text.contains("3"));
    }

    @Test
    public void newReceiptRoundTripsThroughJson() {
        JsonObject written = Receipts.write(Receipts.ZERO, new String[] {"12"}, "t", messages());
        JsonObject reread = JsonParser.parseString(written.toString()).getAsJsonObject();
        assertEquals(messages().text("receipt.zero", "12"), Receipts.display(reread, messages()));
    }

    @Test
    public void oldReceiptWithOnlyDescriptionIsShownAsStored() {
        JsonObject old = JsonParser.parseString("{\"description\":\"舊文字 5 筆\",\"backup\":\"x\"}").getAsJsonObject();
        assertEquals("舊文字 5 筆", Receipts.display(old, messages()));
        assertEquals("", Receipts.display(new JsonObject(), messages()));
    }

    @Test
    public void unknownCodeShowsTheCode() {
        JsonObject receipt = Receipts.write("no-such-code", new String[] {"1"}, "b", messages());
        assertEquals("no-such-code", Receipts.display(receipt, messages()));
        assertEquals("no-such-code", receipt.get("description").getAsString());
    }

    @Test
    public void everyReceiptCodeHasLang() {
        for (String code : new String[] {Receipts.ZERO, Receipts.PORTAL}) {
            assertTrue(code, messages().has(Receipts.KEY_PREFIX + code));
        }
    }

    @Test
    public void slowIsStrictlyAboveThreshold() {
        assertFalse(Timing.slow(200, 200));
        assertTrue(Timing.slow(201, 200));
        assertFalse(Timing.slow(0, Timing.DEFAULT_SLOW_MS));
    }

    @Test
    public void timingRecordsStepsAndTotal() {
        AtomicLong clock = new AtomicLong(5_000_000_000L);
        Timing timing = new Timing(clock::get);
        clock.addAndGet(30_000_000L);
        timing.mark("a");
        clock.addAndGet(250_000_000L);
        timing.mark("b");
        assertEquals(280, timing.totalMs());
        assertEquals("a 30ms, b 250ms", timing.detail());
    }

    @Test
    public void timingLogKeysExistWithBothColours() {
        Messages m = messages();
        for (String key : new String[] {"log.timing.operation", "log.timing.total"}) {
            assertTrue(m.text(key, "x", "y", 1, "z").startsWith("§b"));
            assertTrue(m.text(key + "Slow", "x", "y", 1, "z").startsWith("§e"));
        }
        for (String step : new String[] {"validate", "evacuate", "prepare", "unload", "move", "load", "link", "mounts", "mount", "unmount", "finish"}) {
            assertTrue(step, m.has("log.timing." + step));
        }
    }
}
