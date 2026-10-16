package dev.toka.worlds;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerJoinEvent;
import cn.nukkit.item.Item;
import cn.nukkit.item.RuntimeItemMapping;
import cn.nukkit.item.RuntimeItems;
import cn.nukkit.utils.ConfigSection;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Items saved by an older core keep numbers the current core does not know any more (the log shows
 * "Unknown legacy2Runtime mapping"). When a player joins, the items whose number is listed under
 * {@code legacy-items} in config.yml are replaced by the current item. Every change is written to
 * {@code legacy-items.log} so that it can be undone by hand.
 */
final class LegacyItems implements Listener {
    private record Rule(String to, int perItem) {
        boolean remove() {
            return to == null || to.isBlank() || "remove".equalsIgnoreCase(to);
        }
    }

    private final Main plugin;
    private final Map<Integer, Rule> rules = new HashMap<>();
    private final Path logFile;

    LegacyItems(Main plugin) {
        this.plugin = plugin;
        this.logFile = plugin.getDataFolder().toPath().resolve("legacy-items.log");
        // YAML reads an unquoted 903 as a number, so the keys are not necessarily strings
        Map<?, ?> configured = plugin.getConfig().getSection("legacy-items").getAllMap();
        for (Map.Entry<?, ?> raw : configured.entrySet()) {
            String key = String.valueOf(raw.getKey());
            try {
                int id = Integer.parseInt(key.trim());
                ConfigSection entry = raw.getValue() instanceof ConfigSection section
                        ? section : new ConfigSection(castMap(raw.getValue()));
                String to = entry.getString("to", "");
                int per = Math.max(1, entry.getInt("per-item", 1));
                this.rules.put(id, new Rule(to, per));
            } catch (RuntimeException error) {
                plugin.getLogger().warning("legacy-items 的 " + key + " 設定無法讀取，已略過：" + error.getMessage());
            }
        }
        if (!this.rules.isEmpty()) {
            plugin.getLogger().info("舊編號物品轉換：" + this.rules.keySet());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        if (this.rules.isEmpty()) {
            return;
        }
        Player player = event.getPlayer();
        try {
            this.convert(player);
        } catch (RuntimeException error) {
            this.plugin.getLogger().warning("轉換 " + player.getName() + " 的舊編號物品失敗：" + error);
        }
    }

    private void convert(Player player) {
        List<String> notes = new ArrayList<>();
        List<Item> extra = new ArrayList<>();
        for (Map.Entry<Integer, Item> slot : new HashMap<>(player.getInventory().getContents()).entrySet()) {
            Item old = slot.getValue();
            Rule rule = old == null ? null : this.rules.get(old.getId());
            if (rule == null) {
                continue;
            }
            int total = old.getCount() * rule.perItem();
            if (rule.remove()) {
                player.getInventory().clear(slot.getKey(), false);
                notes.add("slot " + slot.getKey() + " id=" + old.getId() + " damage=" + old.getDamage() + " count=" + old.getCount() + " -> 移除");
                continue;
            }
            Item current = resolve(rule.to());
            if (current == null || current.getId() == 0) {
                this.plugin.getLogger().warning("legacy-items：找不到物品 " + rule.to() + "，沒有轉換 " + old.getId());
                continue;
            }
            int stack = Math.min(total, Math.max(1, current.getMaxStackSize()));
            Item first = current.clone();
            first.setCount(stack);
            player.getInventory().setItem(slot.getKey(), first, false);
            int rest = total - stack;
            while (rest > 0) {
                Item more = current.clone();
                int part = Math.min(rest, Math.max(1, current.getMaxStackSize()));
                more.setCount(part);
                extra.add(more);
                rest -= part;
            }
            notes.add("slot " + slot.getKey() + " id=" + old.getId() + " damage=" + old.getDamage() + " count=" + old.getCount()
                    + " -> " + rule.to() + " x" + total);
        }
        if (notes.isEmpty()) {
            return;
        }
        for (Item leftover : player.getInventory().addItem(extra.toArray(new Item[0]))) {
            player.getLevel().dropItem(player, leftover);
            notes.add("背包已滿，掉落 " + leftover.getName() + " x" + leftover.getCount());
        }
        player.getInventory().sendContents(player);
        this.record(player, notes);
        player.sendMessage("§e你背包裡有舊版留下的物品，已換成現在的版本，請查看背包。");
    }

    /** The item for an identifier such as minecraft:cobblestone_slab, looked up in the core's own item table. */
    private static Item resolve(String identifier) {
        String key = identifier.contains(":") ? identifier : "minecraft:" + identifier;
        RuntimeItemMapping.LegacyEntry entry = RuntimeItems.getMapping().fromIdentifier(key);
        if (entry != null) {
            return Item.get(entry.getLegacyId(), entry.getDamage(), 1);
        }
        return Item.fromString(identifier);
    }

    private void record(Player player, List<String> notes) {
        StringBuilder text = new StringBuilder();
        for (String note : notes) {
            this.plugin.getLogger().info("舊編號物品 " + player.getName() + "：" + note);
            text.append(Instant.now()).append(' ').append(player.getUniqueId()).append(' ').append(player.getName()).append(' ').append(note).append('\n');
        }
        try {
            Files.createDirectories(this.logFile.getParent());
            Files.writeString(this.logFile, text.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException error) {
            this.plugin.getLogger().warning("無法寫入 legacy-items.log：" + error.getMessage());
        }
    }
}
