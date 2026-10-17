package dev.toka.worlds;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.toka.i18n.Messages;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.Test;
import org.yaml.snakeyaml.Yaml;

/** Checks that lang/zh-TW.yml can be read and that every text key used by the source exists in it. */
public class LangFileTest {
    private static final Path SOURCE = Path.of("src", "main", "java");

    private static void flatten(String prefix, Map<?, ?> map, TreeSet<String> out) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = prefix.isEmpty() ? String.valueOf(entry.getKey()) : prefix + "." + entry.getKey();
            if (entry.getValue() instanceof Map<?, ?> child) {
                flatten(key, child, out);
            } else {
                out.add(key);
            }
        }
    }

    private static TreeSet<String> fileKeys() throws IOException {
        TreeSet<String> keys = new TreeSet<>();
        try (InputStream stream = LangFileTest.class.getClassLoader().getResourceAsStream("lang/zh-TW.yml")) {
            assertTrue("lang/zh-TW.yml is missing", stream != null);
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                Object root = new Yaml().load(reader);
                assertTrue(root instanceof Map<?, ?>);
                flatten("", (Map<?, ?>) root, keys);
            }
        }
        return keys;
    }

    private static List<Path> sources() throws IOException {
        try (Stream<Path> files = Files.walk(SOURCE)) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    @Test
    public void everyKeyInTheFileCanBeRead() throws IOException {
        Messages messages = new Messages(getClass().getClassLoader(), Path.of("target", "no-lang-folder"), "zh-TW", null);
        TreeSet<String> keys = fileKeys();
        assertFalse(keys.isEmpty());
        for (String key : keys) {
            assertTrue("cannot read " + key, messages.has(key));
            assertFalse("empty text " + key, messages.lines(key).isEmpty());
        }
    }

    @Test
    public void everyKeyUsedByTheSourceExists() throws IOException {
        Pattern use = Pattern.compile("\\b(?:t|text|lines)\\(\"([a-z][A-Za-z0-9_.]*)\"");
        TreeSet<String> keys = fileKeys();
        List<String> missing = new ArrayList<>();
        int found = 0;
        for (Path file : sources()) {
            Matcher matcher = use.matcher(Files.readString(file, StandardCharsets.UTF_8));
            while (matcher.find()) {
                found++;
                if (!keys.contains(matcher.group(1))) {
                    missing.add(file.getFileName() + ": " + matcher.group(1));
                }
            }
        }
        assertTrue("the scan found no keys", found > 20);
        assertEquals("keys used but not in zh-TW.yml: " + missing, 0, missing.size());
    }

    @Test
    public void dynamicKeysExist() throws IOException {
        TreeSet<String> keys = fileKeys();
        for (String key : List.of("status.op.mount", "status.op.unmount", "status.op.rename", "status.link.on", "status.link.off")) {
            assertTrue(key, keys.contains(key));
        }
    }

    @Test
    public void sourceHasNoChineseLiteralsOutsideComments() throws IOException {
        Pattern han = Pattern.compile("\\p{IsHan}");
        List<String> found = new ArrayList<>();
        for (Path file : sources()) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) {
                    continue;
                }
                int comment = line.indexOf("//");
                String code = comment >= 0 ? line.substring(0, comment) : line;
                if (han.matcher(code).find()) {
                    found.add(file.getFileName() + ":" + (i + 1));
                }
            }
        }
        assertEquals("Chinese text in code: " + found, 0, found.size());
    }
}
