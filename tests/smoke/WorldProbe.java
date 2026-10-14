import cn.nukkit.plugin.PluginBase;
import cn.nukkit.command.CommandSender;
import cn.nukkit.level.*;
import cn.nukkit.level.generator.Generator;
import cn.nukkit.block.Block;
import cn.nukkit.math.Vector3;
import com.j256.ormlite.dao.Dao;
import prj.toka.zero.runtime.LegacyDatabase;
import prj.toka.zero.runtime.WorldLinks;
import prj.toka.zero.runtime.PresenceMessages;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

public class WorldProbe extends PluginBase {
    private prj.toka.zero.Main zero;
    private Object provider;
    private Object originalStorage;
    private Path worlds;
    private void check(boolean ok,String message){if(!ok)throw new IllegalStateException(message);}
    private void run(String command){getServer().dispatchCommand(getServer().getConsoleSender(),command);}
    private void later(Runnable task){getServer().getScheduler().scheduleDelayedTask(this,()->{try{task.run();}catch(Throwable e){fail(e);}},6);}
    private void fail(Throwable error){getLogger().error("WORLD_SMOKE_FAIL",error);getServer().shutdown();}
    private void queue(String a,String b,boolean link){run("worlds rename "+a+" "+b+(link?" --link":" --no-link"));run("worlds check");run("worlds confirm 0");}
    private void world(String name){check(getServer().generateLevel(name,123L,Generator.getGenerator("void")),"generate "+name);check(getServer().loadLevel(name),"load "+name);}
    private Field field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f;}
    private Object seed(Class<?> type,String name,String world)throws Exception{
        Constructor<?> ctor=type.getDeclaredConstructor();ctor.setAccessible(true);Object row=ctor.newInstance();
        for(Field f:type.getDeclaredFields()){f.setAccessible(true);if(f.getType()==String.class)f.set(row,"");}
        for(String col:List.of("name","owner","creator"))try{field(row,col).set(row,name);}catch(NoSuchFieldException ignored){}
        try{field(row,"type").set(row,"region");}catch(NoSuchFieldException ignored){}
        try{field(row,"quanxian").set(row,"protect");}catch(NoSuchFieldException ignored){}
        try{field(row,"stringLevel").set(row,world);}catch(NoSuchFieldException e){field(row,"level").set(row,world);}
        return row;
    }
    private String firstWorld(Dao<?,?> dao,String col)throws Exception {Object row=dao.queryForAll().get(0);return (String)field(row,col).get(row);}
    @Override public void onEnable(){getServer().getScheduler().scheduleDelayedTask(this,()->{try{start();}catch(Throwable e){fail(e);}},12);}
    private void start() throws Exception {
        zero=(prj.toka.zero.Main)getServer().getPluginManager().getPlugin("zero");
        check(zero!=null && zero.isModuleActive("land"),"SQL fixture missing");
        check(getServer().getPluginManager().getPlugin("tokaWorlds").isEnabled(),"world plugin missing");
        worlds=Path.of(getServer().getDataPath(),"worlds");
        dev.toka.worlds.WorldPaths layout=new dev.toka.worlds.WorldPaths(worlds);
        for(String bad:List.of("../escape","A/B","A\\B","NUL","CON","", "a b")) {boolean rejected=false;try{layout.world(bad);}catch(IllegalArgumentException e){rejected=true;}check(rejected,"unsafe world name "+bad);}
        layout.world("中文世界_1");
        check(PresenceMessages.quitReason(null).equals("離開幻月服"),"null quit reason");
        check(PresenceMessages.quitReason("Disconnected from Server").contains("毅然"),"quit presentation lost");
        world("Source_A");world("Conflict_A");world("Conflict_B");world("Rollback_A");world("NoLink_A");
        Level seedWorld=getServer().getLevelByName("Source_A");
        var seedChunk=seedWorld.getChunk(0,0,true);seedChunk.setGenerated(true);seedChunk.setPopulated(true);
        check(seedWorld.setBlock(new Vector3(2,64,2),Block.get(1),true,true),"seed block write failed");
        check(seedWorld.getBlock(2,64,2).getId()==1,"seed block not present");
        LegacyDatabase.AreaTableDao.create((prj.toka.zero.ser.area.AreaTable)seed(prj.toka.zero.ser.area.AreaTable.class,"area","Source_A"));
        LegacyDatabase.RegionTableDao.create((prj.toka.zero.ser.region.RegionTable)seed(prj.toka.zero.ser.region.RegionTable.class,"region","Source_A"));
        LegacyDatabase.LandTableDao.create((prj.toka.zero.ser.land.LandTable)seed(prj.toka.zero.ser.land.LandTable.class,"land","Source_A"));
        WorldLinks.refresh();check(zero.worldProtectionProblem()==null,"initial references not ready: "+zero.worldProtectionProblem());
        Object portal=getServer().getPluginManager().getPlugin("tokaPortal");provider=portal.getClass().getMethod("getProvider").invoke(null);
        provider.getClass().getMethod("addHomePoint",String.class,Object.class,Location.class).invoke(provider,"probe-home","probe",new Location(2,65,2,0,0,getServer().getLevelByName("Source_A")));
        queue("Source_A","Renamed_A",true);later(this::renamed);
    }
    private void renamed() {
        try {
            check(!Files.exists(worlds.resolve("Source_A"))&&Files.exists(worlds.resolve("Renamed_A/db")),"world move failed");
            check(getServer().getLevelByName("Renamed_A")!=null,"renamed world not mounted");
            check(getServer().getLevelByName("Renamed_A").getBlock(2,64,2).getId()==1,"block data changed: "+getServer().getLevelByName("Renamed_A").getBlock(2,64,2).getId());
            check(firstWorld(LegacyDatabase.AreaTableDao,"stringLevel").equals("Renamed_A"),"area references not renamed");
            check(firstWorld(LegacyDatabase.LandTableDao,"stringLevel").equals("Renamed_A"),"land references not renamed");
            check(firstWorld(LegacyDatabase.RegionTableDao,"level").equals("Renamed_A"),"region references not renamed");
            Object home=provider.getClass().getMethod("getHomePoint",String.class).invoke(provider,"probe-home");
            Object raw=home.getClass().getMethod("getRawData").invoke(home);
            check(raw.toString().contains("Renamed_A"),"home not renamed");
            check(zero.worldProtectionProblem()==null,"new references not ready: "+zero.worldProtectionProblem());
            getLogger().info("WORLD_RENAME_LINK_PASS");
            queue("Conflict_A","Conflict_B",true);later(this::collision);
        } catch(Throwable e){fail(e);}
    }
    private void collision(){try {
        check(Files.exists(worlds.resolve("Conflict_A"))&&Files.exists(worlds.resolve("Conflict_B")),"collision overwrote world");run("worlds clear");
        run("worlds unmount Renamed_A");run("worlds check");run("worlds confirm");later(this::unmounted);
    }catch(Throwable e){fail(e);}}
    private void unmounted(){try{
        check(getServer().getLevelByName("Renamed_A")==null,"unmount failed");check(Files.exists(worlds.resolve("Renamed_A")),"unmount deleted world");
        check(zero.worldProtectionProblem()==null,"intentional unmount blocked all worlds");
        run("worlds mount Renamed_A");run("worlds check");run("worlds confirm");later(this::mounted);
    }catch(Throwable e){fail(e);}}
    private void mounted(){try {
        check(getServer().getLevelByName("Renamed_A")!=null,"mount failed");
        queue("NoLink_A","NoLink_B",false);later(this::noLink);
    }catch(Throwable e){fail(e);}}
    private void noLink(){try {
        check(getServer().getLevelByName("NoLink_B")!=null,"no-link rename failed");
        check(firstWorld(LegacyDatabase.LandTableDao,"stringLevel").equals("Renamed_A"),"no-link modified data");
        // Seed a protected world with missing loaded target: must fail closed.
        LegacyDatabase.AreaTableDao.create((prj.toka.zero.ser.area.AreaTable)seed(prj.toka.zero.ser.area.AreaTable.class,"missing","Missing_Map"));
        WorldLinks.refresh();check(zero.worldProtectionProblem()!=null && zero.worldProtectionProblem().contains("Missing_Map"),"unresolved protection world ignored");
        Object last=LegacyDatabase.AreaTableDao.queryForAll().stream().filter(row->row.stringLevel.equals("Missing_Map")).findFirst().orElseThrow();
        LegacyDatabase.AreaTableDao.delete((prj.toka.zero.ser.area.AreaTable)last);WorldLinks.refresh();
        // Force a late home write failure after zero's SQL transaction committed.
        provider.getClass().getMethod("addHomePoint",String.class,Object.class,Location.class).invoke(provider,"rollback-home","probe",new Location(2,65,2,0,0,getServer().getLevelByName("Rollback_A")));
        LegacyDatabase.LandTableDao.create((prj.toka.zero.ser.land.LandTable)seed(prj.toka.zero.ser.land.LandTable.class,"rollback-land","Rollback_A"));WorldLinks.refresh();
        Field storage=field(provider,"storage");originalStorage=storage.get(provider);
        Class<?> api=Class.forName("dev.toka.pl.tokaPortal.provider.storage.HomeStorage");
        Object broken=Proxy.newProxyInstance(api.getClassLoader(),new Class[]{api},(proxy,method,args)->{if(method.getName().equals("save"))throw new java.io.IOException("forced-test-failure");try{return method.invoke(originalStorage,args);}catch(InvocationTargetException e){throw e.getCause();}});
        storage.set(provider,broken);queue("Rollback_A","Rollback_B",true);later(this::rolledBack);
    }catch(Throwable e){fail(e);}}
    private void rolledBack(){try {
        field(provider,"storage").set(provider,originalStorage);
        check(Files.exists(worlds.resolve("Rollback_A"))&&!Files.exists(worlds.resolve("Rollback_B")),"failed rename not rolled back");
        check(getServer().getLevelByName("Rollback_A")!=null,"rollback world not remounted");
        check(LegacyDatabase.LandTableDao.queryForAll().stream().anyMatch(row->row.name.equals("rollback-land")&&row.stringLevel.equals("Rollback_A")),"SQL rollback failed");
        check(zero.worldProtectionProblem()==null,"protection not restored: "+zero.worldProtectionProblem());run("worlds clear");
        run("worlds unmount test");run("worlds check");run("worlds confirm");
        check(getServer().getDefaultLevel()!=null && getServer().getDefaultLevel().getFolderName().equals("test"),"default world was unloaded");run("worlds clear");
        var modules=(prj.toka.zero.runtime.Modules)field(zero,"modules").get(zero);modules.stop("region",new IllegalStateException("forced-region-failure"));
        check(!zero.isModuleActive("chat")&&zero.isModuleActive("presence-messages"),"presence messages tied to protection");
        getLogger().info("WORLD_SMOKE_PASS rename mount unmount collision link no-link reference-guard rollback default-world");getServer().shutdown();
    }catch(Throwable e){fail(e);}}
}
