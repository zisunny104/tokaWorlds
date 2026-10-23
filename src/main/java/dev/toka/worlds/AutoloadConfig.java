package dev.toka.worlds;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** Edits the autoload-worlds line of config.yml as text, so the comments around it survive (Config.save would drop them). */
final class AutoloadConfig {
    static final String KEY = "autoload-worlds";
    private static final Pattern LINE = Pattern.compile("^" + KEY + "\\s*:.*$");
    private static final Pattern ITEM = Pattern.compile("^\\s+-.*$");
    private static final Pattern COMMENT = Pattern.compile("\\s+#.*$");

    private AutoloadConfig() {
    }

    /** Returns the source with the one autoload-worlds line rewritten as a flow list; a block list under it is folded into that line. */
    static String edit(String source, List<String> names) {
        List<String> lines = new ArrayList<>(Arrays.asList(source.split("\\R", -1)));
        StringBuilder value = new StringBuilder("[");
        for (String name : names) {
            if (value.length() > 1) {
                value.append(", ");
            }
            value.append('\'').append(name.replace("'", "''")).append('\'');
        }
        value.append(']');
        int at = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (LINE.matcher(lines.get(i)).matches()) {
                at = i;
                break;
            }
        }
        if (at < 0) {
            if (!lines.getLast().isEmpty()) {
                lines.add("");
            }
            lines.add(lines.size() - 1, KEY + ": " + value);
            return String.join("\n", lines);
        }
        String old = lines.get(at);
        String rest = old.substring(old.indexOf(':') + 1);
        var comment = COMMENT.matcher(rest);
        String tail = comment.find() ? comment.group() : "";
        boolean block = rest.replaceAll("\\s*#.*$", "").isBlank();
        lines.set(at, KEY + ": " + value + tail);
        if (block) {
            while (at + 1 < lines.size() && ITEM.matcher(lines.get(at + 1)).matches()) {
                lines.remove(at + 1);
            }
        }
        return String.join("\n", lines);
    }

    /** Writes through a temporary file so a crash never leaves half a config. */
    static void write(Path file, String content) throws IOException {
        Path temporary = file.resolveSibling("." + file.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
