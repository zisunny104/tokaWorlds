package dev.toka.worlds;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class AutoloadConfigTest {
    @Test
    public void rewritesOnlyTheOneLineAndKeepsComments() {
        String source = "# top\nlanguage: zh-TW\n\n# about autoload\nautoload-worlds: [] # tail\nreserved-names:\n  - help\n";
        String out = AutoloadConfig.edit(source, List.of("a", "b-c"));
        assertEquals("# top\nlanguage: zh-TW\n\n# about autoload\nautoload-worlds: ['a', 'b-c'] # tail\nreserved-names:\n  - help\n", out);
    }

    @Test
    public void foldsABlockListAndQuotesNames() {
        String source = "autoload-worlds:\n  - old\n  - older\nnext: 1\n";
        assertEquals("autoload-worlds: ['123']\nnext: 1\n", AutoloadConfig.edit(source, List.of("123")));
    }

    @Test
    public void addsTheKeyWhenMissing() {
        String out = AutoloadConfig.edit("a: 1\n", List.of("w"));
        assertTrue(out.contains("autoload-worlds: ['w']"));
        assertTrue(out.startsWith("a: 1\n"));
    }

    @Test
    public void emptyListWritesEmptyBrackets() {
        assertEquals("autoload-worlds: []\n", AutoloadConfig.edit("autoload-worlds: ['x']\n", List.of()));
    }
}
