package dev.toka.worlds;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.toka.i18n.Messages;

/** Journal receipts store a stable code plus string arguments; text is built from lang on display. */
final class Receipts {
    static final String ZERO = "zero";
    static final String PORTAL = "portal";
    static final String LAND = "land";
    static final String KEY_PREFIX = "receipt.";

    private Receipts() {}

    /** New format: code and args, plus description (current language) so older jars can still read it. */
    static JsonObject write(String code, String[] args, String backup, Messages messages) {
        JsonObject receipt = new JsonObject();
        receipt.addProperty("code", code);
        JsonArray list = new JsonArray();
        for (String arg : args) list.add(arg);
        receipt.add("args", list);
        receipt.addProperty("description", text(code, args, messages));
        receipt.addProperty("backup", backup);
        return receipt;
    }

    /** New receipts are built from lang; old ones (description only) are shown as stored. */
    static String display(JsonObject receipt, Messages messages) {
        if (receipt.has("code") && receipt.get("code").isJsonPrimitive()) {
            return text(receipt.get("code").getAsString(), args(receipt), messages);
        }
        JsonElement old = receipt.get("description");
        return old != null && old.isJsonPrimitive() ? old.getAsString() : "";
    }

    static String[] args(JsonObject receipt) {
        JsonElement raw = receipt.get("args");
        if (raw == null || !raw.isJsonArray()) return new String[0];
        JsonArray list = raw.getAsJsonArray();
        String[] out = new String[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i).isJsonNull() ? "" : list.get(i).getAsString();
        return out;
    }

    /** Falls back to the code itself when lang has no receipt.<code>. */
    static String text(String code, String[] args, Messages messages) {
        String key = KEY_PREFIX + code;
        return messages.has(key) ? messages.text(key, (Object[]) args) : code;
    }
}
