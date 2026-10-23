package dev.toka.worlds;
import cn.nukkit.plugin.Plugin;
import cn.nukkit.utils.ConfigSection;
import com.google.gson.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.util.*;
import dev.toka.i18n.Messages;

/** Only verified adapters are used. No global text replacement of plugin files. */
final class Links {
    static final Gson JSON=new GsonBuilder().serializeNulls().setPrettyPrinting().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create();
    interface Change {String code(); String[] args(); void commit() throws Exception; void rollback() throws Exception; String token();}
    static Object call(Object target,String method,Class<?>[] types,Object... args) throws Exception {
        try {return target.getClass().getMethod(method,types).invoke(target,args);}
        catch(InvocationTargetException e) {Throwable t=e.getCause();if(t instanceof Exception ex) throw ex;throw new IllegalStateException(t);}
    }
    static Field field(Object obj,String name) throws Exception {Field f=obj.getClass().getDeclaredField(name);f.setAccessible(true);return f;}
    static void write(Path file,String text) throws Exception {
        Files.createDirectories(file.getParent());
        Path temp=file.resolveSibling(file.getFileName()+".tmp");
        Files.writeString(temp,text,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        try(FileChannel channel=FileChannel.open(temp,StandardOpenOption.WRITE)) {channel.force(true);}
        Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE);
    }
    static List<Change> prepare(Main main,String oldName,String newName,boolean link) throws Exception {
        Messages msg=main.messages();
        List<Change> changes=new ArrayList<>(); if(!link) return changes;
        Plugin portal=main.getServer().getPluginManager().getPlugin("tokaPortal");
        if(portal!=null) {
            if(!portal.isEnabled()) throw new IllegalStateException(msg.text("err.portalProvider"));
            Object provider=portal.getClass().getMethod("getProvider").invoke(null);
            if(provider==null || !provider.getClass().getName().equals("dev.toka.pl.tokaPortal.provider.BaseDataProvider")) throw new IllegalStateException(msg.text("err.portalProvider"));
            // No version list: what the rename needs from tokaPortal is checked directly, and anything unexpected stops it before a change is made.
            try {requirePortalShape(provider);} catch(Exception shape) {throw new IllegalStateException(msg.text("err.portalProvider"),shape);}
            ConfigSection document=(ConfigSection)field(provider,"document").get(provider);
            String before=JSON.toJson(document);
            JsonObject next=JsonParser.parseString(before).getAsJsonObject();int homes=0;
            for(JsonElement item:next.getAsJsonArray("homes")) {JsonObject loc=item.getAsJsonObject().getAsJsonObject("loc");if(oldName.equals(loc.get("level").getAsString())) {loc.addProperty("level",newName);homes++;}}
            String after=JSON.toJson(next);Object storage=field(provider,"storage").get(provider);
            Method save=storage.getClass().getMethod("save",ConfigSection.class);
            Path token=main.getDataFolder().toPath().resolve("link-backups").resolve(UUID.randomUUID()+"-homes.json");write(token,before);
            final int changed=homes;
            changes.add(new Change(){public String code(){return Receipts.PORTAL;}public String[] args(){return new String[]{String.valueOf(changed)};}public String token(){return token.toAbsolutePath().toString();}
                private String current() throws Exception {return JSON.toJson(field(provider,"document").get(provider));}
                private void persist(String value) throws Exception {
                    @SuppressWarnings("unchecked") Map<String,Object> map=JSON.fromJson(value,Map.class);
                    try {save.invoke(storage,new ConfigSection(new LinkedHashMap<>(map)));} catch(InvocationTargetException e) {throw new IllegalStateException(msg.text("err.homeSaveFailed"),e.getCause());}
                    call(provider,"reload",new Class[]{boolean.class},false);
                }
                public void commit() throws Exception {if(!current().equals(before)) throw new IllegalStateException(msg.text("err.homesChanged"));persist(after);}
                public void rollback() throws Exception {if(current().equals(before))return;if(!current().equals(after))throw new IllegalStateException(msg.text("err.homesModified"));persist(before);}
            });
        }
        Plugin land=main.getServer().getPluginManager().getPlugin("tokaLand");
        if(land!=null) {
            if(!land.isEnabled()) throw new IllegalStateException(msg.text("err.landDisabled"));
            int count=(Integer)call(land,"countClaimsInWorld",new Class[]{String.class},oldName);
            changes.add(new Change(){
                private boolean committed;
                public String code(){return Receipts.LAND;}
                public String[] args(){return new String[]{String.valueOf(count)};}
                public String token(){return "land:"+oldName+"->"+newName;}
                /** tokaLand refuses a new name that already holds claims, so a failed commit changes nothing. */
                public void commit() throws Exception {rename(land,oldName,newName);committed=true;}
                /** Only undoes what this change did; an uncommitted change must never move someone else's claims. */
                public void rollback() throws Exception {if(committed){rename(land,newName,oldName);committed=false;}}
            });
        }
        return changes;
    }
    /** The home data must have the parts the rename reads and writes: a document with a "homes" list, a storage that saves it, and reload(boolean). */
    private static void requirePortalShape(Object provider) throws Exception {
        Object document=field(provider,"document").get(provider);
        if(!(document instanceof ConfigSection)||JsonParser.parseString(JSON.toJson(document)).getAsJsonObject().getAsJsonArray("homes")==null) throw new IllegalStateException("homes");
        Object storage=field(provider,"storage").get(provider);
        storage.getClass().getMethod("save",ConfigSection.class);
        provider.getClass().getMethod("reload",boolean.class);
    }
    private static void rename(Plugin land,String from,String to) throws Exception {
        @SuppressWarnings("unchecked") java.util.concurrent.CompletableFuture<Integer> work=
            (java.util.concurrent.CompletableFuture<Integer>)call(land,"renameWorld",new Class[]{String.class,String.class},from,to);
        try {work.get(30,java.util.concurrent.TimeUnit.SECONDS);}
        catch(java.util.concurrent.ExecutionException e) {throw e.getCause() instanceof Exception ex?ex:new IllegalStateException(e.getCause());}
    }
}
