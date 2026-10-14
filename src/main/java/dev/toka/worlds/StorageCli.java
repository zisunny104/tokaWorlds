package dev.toka.worlds;

import com.google.gson.*;
import dev.toka.storage.*;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.LoaderOptions;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Offline only: server must be fully stopped before invoking this entry point. */
public final class StorageCli {
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    public static void main(String[] args) throws Exception {
        if(args.length==0)throw new IllegalArgumentException("先完整停服。import-legacy <舊插件資料夾> <sqlite|json|yaml> <新儲存資料夾> <備份資料夾>；migrate <來源格式> <來源儲存資料夾> <目標格式> <新儲存資料夾> <備份資料夾>；inspect <格式> <儲存資料夾>");
        switch(args[0]) {
            case "import-legacy" -> {
                if(args.length!=5)throw new IllegalArgumentException("import-legacy 需要來源、格式、新目的地與備份資料夾");
                Path source=Path.of(args[1]).toRealPath(),target=Path.of(args[3]).toAbsolutePath().normalize(),backup=Path.of(args[4]).toAbsolutePath().normalize();
                if(!Files.isDirectory(source)||Files.isSymbolicLink(Path.of(args[1])))throw new IllegalArgumentException("來源必須是一般插件資料夾");
                if(Files.exists(target,LinkOption.NOFOLLOW_LINKS))throw new FileAlreadyExistsException(target.toString());
                if(target.equals(source)||target.startsWith(source))throw new IllegalArgumentException("目的地必須在來源資料夾外，驗證後才人工移入 storage");
                if(backup.startsWith(source)||backup.startsWith(target))throw new IllegalArgumentException("備份必須在來源與目的地外");
                SortedMap<String,String> before=digests(source);
                Map<?,?> config=readConfig(source.resolve("config.yml"));
                JsonObject expected=WorldState.importLegacy(source,list(config,"mounted-worlds"),list(config,"unmounted-worlds"));
                Files.createDirectories(backup);Path receipt=backup.resolve("worlds-legacy-"+UUID.randomUUID()+".json");
                JsonObject export=new JsonObject();export.add("source-sha256",JSON.toJsonTree(before));export.add("state",expected);
                Files.writeString(receipt,JSON.toJson(export),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
                JsonObject reread=JsonParser.parseString(Files.readString(receipt,StandardCharsets.UTF_8)).getAsJsonObject();
                if(!reread.equals(export))throw new IllegalStateException("來源備份驗證失敗");
                Backend backend=backend(args[2]);
                Path stage=target.resolveSibling("."+target.getFileName()+".import-"+UUID.randomUUID());
                try(DocumentStore store=DocumentStore.open(new StoreConfig(backend,stage,1))) {store.save("state",expected);}
                try(DocumentStore store=DocumentStore.open(new StoreConfig(backend,stage,1))) {
                    JsonObject actual=WorldState.validate(store.load("state").orElseThrow());
                    if(!expected.equals(actual)||!store.keys().equals(Set.of("state")))throw new IllegalStateException("匯入讀回驗證失敗");
                }
                if(!before.equals(digests(source)))throw new IllegalStateException("來源資料在匯入期間改變，停止啟用目的地");
                Files.move(stage,target);
                System.out.println("IMPORT_PASS SourceUnchanged=true Destination="+target+" Backup="+receipt);
            }
            case "migrate" -> {
                if(args.length!=6)throw new IllegalArgumentException("migrate 需要來源格式／路徑、目標格式／新路徑、備份資料夾");
                Path source=Path.of(args[2]).toRealPath();
                requireData(source,backend(args[1]));
                try(DocumentStore store=DocumentStore.open(new StoreConfig(backend(args[1]),source,1))) {WorldState.validate(store.load("state").orElseThrow());}
                Migration.Result result=Migration.migrate(new StoreConfig(backend(args[1]),source,1),new StoreConfig(backend(args[3]),Path.of(args[4]),1),Path.of(args[5]));
                System.out.println("MIGRATE_PASS Destination="+result.destination()+" Backup="+result.backupFile());
            }
            case "inspect" -> {
                if(args.length!=3)throw new IllegalArgumentException("inspect 需要格式與路徑");
                Path source=Path.of(args[2]).toRealPath();
                requireData(source,backend(args[1]));
                try(DocumentStore store=DocumentStore.open(new StoreConfig(backend(args[1]),source,1))) {
                    JsonObject state=WorldState.validate(store.load("state").orElseThrow());
                    System.out.println("STATE_PASS Plans="+state.getAsJsonObject("plans").size()+" Returns="+state.getAsJsonArray("returns").size()+" Journals="+state.getAsJsonObject("journals").size()+" Mounts="+state.getAsJsonObject("mounts").size());
                }
            }
            default -> throw new IllegalArgumentException("未知離線指令");
        }
    }
    private static Backend backend(String value){return Backend.valueOf(value.toUpperCase(Locale.ROOT));}
    private static void requireData(Path directory,Backend backend) {
        Path file=directory.resolve(switch(backend){case SQLITE->"documents.sqlite";case JSON->"documents.json";case YAML->"documents.yml";});
        if(!Files.isRegularFile(file)||Files.isSymbolicLink(file))throw new IllegalArgumentException("來源儲存檔案不存在或為連結："+file);
    }
    private static Map<?,?> readConfig(Path file) throws Exception {
        if(!Files.isRegularFile(file)||Files.isSymbolicLink(file))throw new IllegalArgumentException("缺少一般 config.yml 來源");
        LoaderOptions options=new LoaderOptions();options.setAllowDuplicateKeys(false);
        Object parsed=new Yaml(new SafeConstructor(options)).load(Files.readString(file,StandardCharsets.UTF_8));
        if(!(parsed instanceof Map<?,?> map))throw new IllegalArgumentException("無效 config.yml");return map;
    }
    private static List<String> list(Map<?,?> config,String key) {
        Object value=config.get(key);if(value==null)return List.of();
        if(!(value instanceof List<?> entries)||entries.stream().anyMatch(x->!(x instanceof String)))throw new IllegalArgumentException("無效設定列表："+key);
        return entries.stream().map(String.class::cast).toList();
    }
    private static SortedMap<String,String> digests(Path source) throws Exception {
        SortedMap<String,String> result=new TreeMap<>();
        List<Path> files=new ArrayList<>();files.add(source.resolve("config.yml"));
        Path returns=source.resolve("pending-returns.json");if(Files.exists(returns))files.add(returns);
        for(String section:List.of("plans","operations")) {
            Path dir=source.resolve(section);if(!Files.isDirectory(dir))continue;
            if(Files.isSymbolicLink(dir))throw new IllegalArgumentException("拒絕來源連結");
            try(var stream=Files.list(dir)){files.addAll(stream.filter(p->p.getFileName().toString().endsWith(".json")).toList());}
        }
        for(Path file:files) {
            if(!Files.isRegularFile(file)||Files.isSymbolicLink(file))throw new IllegalArgumentException("拒絕非一般來源檔案："+file);
            result.put(source.relativize(file).toString(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
        }
        return result;
    }
}
