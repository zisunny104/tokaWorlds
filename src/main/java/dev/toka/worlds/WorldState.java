package dev.toka.worlds;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Versioned formal data. Configuration and reference-backup exchange files stay separate. */
final class WorldState {
    static JsonObject empty() {
        JsonObject state=new JsonObject();state.addProperty("schema",1);
        state.add("plans",new JsonObject());state.add("returns",new JsonArray());
        state.add("journals",new JsonObject());state.add("mounts",new JsonObject());return state;
    }
    static JsonObject validate(JsonObject state) {
        if(state==null || !state.has("schema") || !state.get("schema").isJsonPrimitive()
                || !state.get("schema").getAsJsonPrimitive().isNumber() || !state.get("schema").getAsString().equals("1"))
            throw new IllegalArgumentException("不支援的世界管理資料版本");
        for(String section:List.of("plans","journals","mounts"))
            if(!state.has(section)||!state.get(section).isJsonObject())throw new IllegalArgumentException("資料缺少 "+section);
        if(!state.has("returns")||!state.get("returns").isJsonArray())throw new IllegalArgumentException("資料缺少 returns");
        for(var owner:state.getAsJsonObject("plans").entrySet()) {
            if(!owner.getKey().equals("console"))UUID.fromString(owner.getKey());
            for(JsonElement row:owner.getValue().getAsJsonArray()) {
                JsonObject op=row.getAsJsonObject();String type=op.get("type").getAsString();
                if(!List.of("mount","unmount","rename").contains(type))throw new IllegalArgumentException("無效待辦操作");
                if(op.get("source").getAsString().isBlank())throw new IllegalArgumentException("空白來源世界");
                op.get("target").getAsString();op.get("link").getAsBoolean();
            }
        }
        Set<String> ids=new HashSet<>();
        for(JsonElement row:state.getAsJsonArray("returns")) {
            JsonObject point=row.getAsJsonObject();String id=point.get("uuid").getAsString();UUID.fromString(id);
            if(!ids.add(id))throw new IllegalArgumentException("重複玩家返回紀錄");
            if(point.get("world").getAsString().isBlank())throw new IllegalArgumentException("空白返回世界");
            for(String key:List.of("x","y","z","yaw","pitch"))
                if(!Double.isFinite(point.get(key).getAsDouble()))throw new IllegalArgumentException("非有限返回座標");
        }
        for(var journal:state.getAsJsonObject("journals").entrySet()) {
            UUID.fromString(journal.getKey());JsonObject record=journal.getValue().getAsJsonObject();
            if(record.get("stage").getAsString().isBlank())throw new IllegalArgumentException("空白工作階段");
            record.get("source").getAsString();record.get("target").getAsString();
        }
        for(var mount:state.getAsJsonObject("mounts").entrySet()) {
            if(mount.getKey().isBlank()||!mount.getValue().isJsonPrimitive()||!mount.getValue().getAsJsonPrimitive().isBoolean())
                throw new IllegalArgumentException("無效掛載狀態");
        }
        return state;
    }
    static boolean hasLegacy(Path data,Collection<String> mounted,Collection<String> unmounted) throws Exception {
        if(!mounted.isEmpty()||!unmounted.isEmpty()||Files.exists(data.resolve("pending-returns.json")))return true;
        for(String section:List.of("plans","operations")) {
            Path dir=data.resolve(section);if(Files.isDirectory(dir))try(var files=Files.list(dir)) {
                if(files.anyMatch(p->p.getFileName().toString().endsWith(".json")))return true;
            }
        }
        return false;
    }
    static JsonObject importLegacy(Path data,Collection<String> mounted,Collection<String> unmounted) throws Exception {
        JsonObject state=empty();
        for(String section:List.of("plans","operations")) {
            Path dir=data.resolve(section);if(!Files.isDirectory(dir))continue;
            if(Files.isSymbolicLink(dir))throw new IllegalArgumentException("拒絕連結來源資料夾："+dir);
            try(var files=Files.list(dir)) {
                for(Path p:files.filter(f->f.getFileName().toString().endsWith(".json")).sorted().toList()) {
                    if(Files.isSymbolicLink(p)||!Files.isRegularFile(p))throw new IllegalArgumentException("拒絕非一般來源檔案："+p);
                    String key=p.getFileName().toString().replaceFirst("\\.json$","");
                    state.getAsJsonObject(section.equals("plans")?"plans":"journals").add(key,JsonParser.parseString(Files.readString(p,StandardCharsets.UTF_8)));
                }
            }
        }
        Path returns=data.resolve("pending-returns.json");
        if(Files.exists(returns)) {
            if(Files.isSymbolicLink(returns)||!Files.isRegularFile(returns))throw new IllegalArgumentException("拒絕非一般返回來源檔案");
            state.add("returns",JsonParser.parseString(Files.readString(returns,StandardCharsets.UTF_8)));
        }
        for(String name:mounted)state.getAsJsonObject("mounts").addProperty(name,true);
        for(String name:unmounted) {
            if(state.getAsJsonObject("mounts").has(name))throw new IllegalArgumentException("舊掛載設定互相衝突："+name);
            state.getAsJsonObject("mounts").addProperty(name,false);
        }
        return validate(state);
    }
}
