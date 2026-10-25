package dev.toka.worlds;

import com.google.gson.*;
import dev.toka.storage.*;
import java.nio.file.*;
import java.util.*;
import dev.toka.i18n.Messages;

/** Offline only: server must be fully stopped before invoking this entry point. */
public final class StorageCli {
    private static Messages messages;
    private static String t(String key,Object... arguments){return messages.text(key,arguments);}
    public static void main(String[] args) throws Exception {
        messages=new Messages(StorageCli.class.getClassLoader(),Path.of(System.getProperty("java.io.tmpdir"),"tokaWorlds-cli"),"zh-TW",System.err::println);
        if(args.length==0)throw new IllegalArgumentException(t("cli.usage"));
        switch(args[0]) {
            case "migrate" -> {
                if(args.length!=6)throw new IllegalArgumentException(t("cli.migrateArgs"));
                Path source=Path.of(args[2]).toRealPath();
                requireData(source,backend(args[1]));
                try(DocumentStore store=DocumentStore.open(new StoreConfig(backend(args[1]),source,1))) {WorldState.validate(store.load("state").orElseThrow());}
                Migration.Result result=Migration.migrate(new StoreConfig(backend(args[1]),source,1),new StoreConfig(backend(args[3]),Path.of(args[4]),1),Path.of(args[5]));
                System.out.println("MIGRATE_PASS Destination="+result.destination()+" Backup="+result.backupFile());
            }
            case "inspect" -> {
                if(args.length!=3)throw new IllegalArgumentException(t("cli.inspectArgs"));
                Path source=Path.of(args[2]).toRealPath();
                requireData(source,backend(args[1]));
                try(DocumentStore store=DocumentStore.open(new StoreConfig(backend(args[1]),source,1))) {
                    JsonObject state=WorldState.validate(store.load("state").orElseThrow());
                    System.out.println("STATE_PASS Plans="+state.getAsJsonObject("plans").size()+" Returns="+state.getAsJsonArray("returns").size()+" Journals="+state.getAsJsonObject("journals").size()+" Mounts="+state.getAsJsonObject("mounts").size());
                }
            }
            default -> throw new IllegalArgumentException(t("cli.unknown"));
        }
    }
    private static Backend backend(String value){return Backend.valueOf(value.toUpperCase(Locale.ROOT));}
    private static void requireData(Path directory,Backend backend) {
        Path file=directory.resolve(switch(backend){case SQLITE->"documents.sqlite";case JSON->"documents.json";case YAML->"documents.yml";});
        if(!Files.isRegularFile(file)||Files.isSymbolicLink(file))throw new IllegalArgumentException(t("cli.noStoreFile",file));
    }
}
