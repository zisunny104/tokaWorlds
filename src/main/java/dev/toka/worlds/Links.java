package dev.toka.worlds;
import cn.nukkit.plugin.Plugin;
import cn.nukkit.utils.ConfigSection;
import com.google.gson.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.util.*;

/** Only verified adapters are used. No global text replacement of plugin files. */
final class Links {
    static final Gson JSON=new GsonBuilder().serializeNulls().setPrettyPrinting().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create();
    interface Change {String description(); void commit() throws Exception; void rollback() throws Exception; String token();}
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
        List<Change> changes=new ArrayList<>(); if(!link) return changes;
        Plugin zero=main.getServer().getPluginManager().getPlugin("zero");
        if(zero!=null) {
            if(!zero.isEnabled()) throw new IllegalStateException("zero 未啟用，無法驗證連動");
            String token=(String)call(zero,"prepareWorldRename",new Class[]{String.class,String.class},oldName,newName);
            int count=JsonParser.parseString(Files.readString(Path.of(token),StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("entries").size();
            changes.add(new Change(){public String description(){return "zero："+count+" 筆區域／領地／傳送點／告示牌／方塊紀錄";}
                public String token(){return token;}
                public void commit() throws Exception {call(zero,"commitWorldRename",new Class[]{String.class},token);}
                public void rollback() throws Exception {call(zero,"rollbackWorldRename",new Class[]{String.class},token);}});
        }
        Plugin portal=main.getServer().getPluginManager().getPlugin("tokaPortal");
        if(portal!=null) {
            if(!portal.isEnabled() || !Set.of("2.0.0","2.1.1").contains(portal.getDescription().getVersion())) throw new IllegalStateException("住家連動支援 tokaPortal 2.0.0／2.1.1；其他版本需驗證轉接器。");
            Object provider=portal.getClass().getMethod("getProvider").invoke(null);
            if(provider==null || !provider.getClass().getName().equals("dev.toka.pl.tokaPortal.provider.BaseDataProvider")) throw new IllegalStateException("不支援的住家資料提供者");
            ConfigSection document=(ConfigSection)field(provider,"document").get(provider);
            String before=JSON.toJson(document);
            JsonObject next=JsonParser.parseString(before).getAsJsonObject();int homes=0;
            for(JsonElement item:next.getAsJsonArray("homes")) {JsonObject loc=item.getAsJsonObject().getAsJsonObject("loc");if(oldName.equals(loc.get("level").getAsString())) {loc.addProperty("level",newName);homes++;}}
            String after=JSON.toJson(next);Object storage=field(provider,"storage").get(provider);
            Method save=storage.getClass().getMethod("save",ConfigSection.class);
            Path token=main.getDataFolder().toPath().resolve("link-backups").resolve(UUID.randomUUID()+"-homes.json");write(token,before);
            final int changed=homes;
            changes.add(new Change(){public String description(){return "tokaPortal："+changed+" 個住家";}public String token(){return token.toAbsolutePath().toString();}
                private String current() throws Exception {return JSON.toJson(field(provider,"document").get(provider));}
                private void persist(String value) throws Exception {
                    @SuppressWarnings("unchecked") Map<String,Object> map=JSON.fromJson(value,Map.class);
                    try {save.invoke(storage,new ConfigSection(new LinkedHashMap<>(map)));} catch(InvocationTargetException e) {throw new IllegalStateException("住家儲存失敗",e.getCause());}
                    call(provider,"reload",new Class[]{boolean.class},false);
                }
                public void commit() throws Exception {if(!current().equals(before)) throw new IllegalStateException("住家資料已變更，停止更名");persist(after);}
                public void rollback() throws Exception {if(current().equals(before))return;if(!current().equals(after))throw new IllegalStateException("住家資料被其他工作修改，需人工還原");persist(before);}
            });
        }
        return changes;
    }
    static void mounted(Main main,String name,boolean value) throws Exception {
        Plugin zero=main.getServer().getPluginManager().getPlugin("zero");
        if(zero!=null && zero.isEnabled()) {call(zero,"setWorldMountedState",new Class[]{String.class,boolean.class},name,value);call(zero,"refreshWorldReferences",new Class[0]);}
    }
}
