package dev.toka.worlds;

import cn.nukkit.Player;
import cn.nukkit.command.*;
import cn.nukkit.event.*;
import cn.nukkit.event.player.*;
import cn.nukkit.form.element.*;
import cn.nukkit.form.response.*;
import cn.nukkit.form.window.*;
import cn.nukkit.level.Level;
import cn.nukkit.level.Location;
import cn.nukkit.event.block.BlockBreakEvent;
import cn.nukkit.event.block.BlockPlaceEvent;
import cn.nukkit.event.entity.EntityDamageEvent;
import cn.nukkit.event.entity.EntityDamageByEntityEvent;
import cn.nukkit.plugin.PluginBase;
import com.google.gson.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import dev.toka.storage.*;

public final class Main extends PluginBase implements Listener {
    public record Operation(String type,String source,String target,boolean link) {}
    private record Dialog(int id,String kind,List<String> names) {}
    private final Map<String,List<Operation>> plans=new HashMap<>();
    private final Set<String> checked=new HashSet<>();
    private final Map<UUID,Dialog> dialogs=new HashMap<>();
    private final Map<UUID,Boolean> frozen=new HashMap<>();
    private final Set<String> maintenance=new HashSet<>();
    private final Map<String,ReturnPoint> returns=new LinkedHashMap<>();
    public record ReturnPoint(String uuid,String world,double x,double y,double z,double yaw,double pitch) {
        ReturnPoint in(String next){return new ReturnPoint(uuid,next,x,y,z,yaw,pitch);}
    }
    private boolean internalTeleport;
    private WorldPaths paths;
    private boolean busy;
    private boolean executing;
    private cn.nukkit.scheduler.TaskHandler scheduled;
    private String recovery;
    private DocumentStore store;
    private JsonObject state;
    private boolean storageHealthy;
    private final Map<String,Boolean> mounts=new LinkedHashMap<>();
    @Override public void onEnable() {
        saveDefaultConfig();
        try {
            paths=new WorldPaths(Path.of(getServer().getDataPath(),"worlds"));
            getLogger().info("載入世界共 "+paths.names().size()+" 個");
            Path data=getDataFolder().toPath();
            Backend backend=Backend.valueOf(getConfig().getString("storage.backend","sqlite").toUpperCase(Locale.ROOT));
            Path active=storagePath(data,backend);
            if(!Files.exists(active)) {
                if(WorldState.hasLegacy(data,getConfig().getStringList("mounted-worlds"),getConfig().getStringList("unmounted-worlds")))
                    throw new IllegalStateException("偵測到舊資料，請停服執行 StorageCli import-legacy；不會自動匯入。");
                for(Backend other:Backend.values())if(other!=backend&&Files.exists(storagePath(data,other)))
                    throw new IllegalStateException("其他儲存格式已有資料，請先離線 migrate，不能只更改設定。");
            }
            store=DocumentStore.open(new StoreConfig(backend,active,1));
            Optional<JsonObject> loaded=store.load("state");
            if(loaded.isEmpty()&&WorldState.hasLegacy(data,getConfig().getStringList("mounted-worlds"),getConfig().getStringList("unmounted-worlds")))
                throw new IllegalStateException("新儲存未含舊資料，請先離線匯入。");
            if(loaded.isEmpty())for(Backend other:Backend.values())if(other!=backend&&Files.exists(storagePath(data,other)))
                throw new IllegalStateException("其他格式已有資料但目前格式為空，停止初始化；請離線遷移。");
            state=WorldState.validate(loaded.orElseGet(WorldState::empty));
            for(var entry:state.getAsJsonObject("plans").entrySet()) {
                Operation[] saved=Links.JSON.fromJson(entry.getValue(),Operation[].class);
                plans.put(entry.getKey(),new ArrayList<>(Arrays.asList(saved)));
            }
            for(ReturnPoint point:Links.JSON.fromJson(state.get("returns"),ReturnPoint[].class))returns.put(point.uuid(),point);
            for(var entry:state.getAsJsonObject("mounts").entrySet())mounts.put(entry.getKey(),entry.getValue().getAsBoolean());
            for(var entry:state.getAsJsonObject("journals").entrySet())
                if(!List.of("complete","rolled-back").contains(entry.getValue().getAsJsonObject().get("stage").getAsString()))recovery="journal:"+entry.getKey();
            storageHealthy=true;
            if(loaded.isEmpty())saveState();
            if(recovery!=null) getLogger().error("有未完成的世界工作，停止修改。請檢查紀錄："+recovery);
            getServer().getPluginManager().registerEvents(this,this);getServer().getPluginManager().registerEvents(new LegacyItems(this),this);
            getServer().getScheduler().scheduleDelayedTask(this,()-> {
                if(recovery!=null)return;
                for(String name:new ArrayList<>(mounts.keySet()))if(Boolean.TRUE.equals(mounts.get(name))) try {mount(name);}catch(Exception e){getLogger().warning("掛載失敗 "+name+"："+e.getMessage());}
                for(String name:new ArrayList<>(mounts.keySet()))if(Boolean.FALSE.equals(mounts.get(name))) try {unmount(name);}catch(Exception e){getLogger().warning("卸載失敗 "+name+"："+e.getMessage());}
            },1);
        } catch(Exception error) {getLogger().error("世界管理初始化失敗",error);getServer().getPluginManager().disablePlugin(this);}
    }
    private String owner(CommandSender sender){return sender instanceof Player p?p.getUniqueId().toString():"console";}
    private List<Operation> plan(CommandSender sender){return plans.computeIfAbsent(owner(sender),key->new ArrayList<>());}
    private void mutable(){if(!storageHealthy)throw new IllegalStateException("儲存未就緒，停止世界管理");if(busy)throw new IllegalStateException("已有世界工作排程／執行中");if(recovery!=null)throw new IllegalStateException("未完成工作需先人工檢查："+recovery);}
    static Path storagePath(Path data,Backend backend) {
        return data.resolve("storage").resolve(backend.name().toLowerCase(Locale.ROOT));
    }
    private void saveState() throws Exception {
        if(!storageHealthy)throw new IllegalStateException("儲存已失效，停止世界修改");
        JsonObject next=state.deepCopy();
        next.add("plans",Links.JSON.toJsonTree(plans));next.add("returns",Links.JSON.toJsonTree(returns.values()));next.add("mounts",Links.JSON.toJsonTree(mounts));
        try {WorldState.validate(next);store.save("state",next);state=next;}
        catch(Exception error){storageHealthy=false;recovery="storage-write-failed";throw error;}
    }
    private void remember(CommandSender sender) throws Exception {
        saveState();checked.remove(owner(sender));
    }
    private void mark(String name,boolean mounted) throws Exception {
        Boolean old=mounts.put(name,mounted);
        try {saveState();}catch(Exception e){if(old==null)mounts.remove(name);else mounts.put(name,old);throw e;}
        Links.mounted(this,name,mounted);
    }
    private void forget(String name) throws Exception {
        Boolean old=mounts.remove(name);
        try {saveState();}catch(Exception e){if(old!=null)mounts.put(name,old);throw e;}
    }
    private void mount(String name) throws Exception {
        if(!storageHealthy)throw new IllegalStateException("儲存未就緒，停止掛載");
        paths.existing(name);
        if(!getServer().loadLevel(name)) throw new IllegalStateException("核心未能掛載："+name);
        mark(name,true);
    }
    private Level fallback(Level source) {
        String name=getConfig().getString("evacuation-world","");
        Level target=name.isBlank()?getServer().getDefaultLevel():getServer().getLevelByName(name);
        if(target==null || target==source)throw new IllegalStateException("需要另一張已載入世界供玩家移出");return target;
    }
    private void unmount(String name) throws Exception {
        if(!storageHealthy)throw new IllegalStateException("儲存未就緒，停止卸載");
        Level level=getServer().getLevelByName(name);
        if(level!=null) {
            if(level==getServer().getDefaultLevel())throw new IllegalStateException("預設世界不能卸載／更名；請先在 server.properties 換預設世界並重啟。");
            if(!level.getPlayers().isEmpty()) {
                Level target=fallback(level);
                for(Player player:new ArrayList<>(level.getPlayers().values()))
                    if(!player.teleport(target.getSafeSpawn()))throw new IllegalStateException("玩家無法移出："+player.getName());
                if(!level.getPlayers().isEmpty())throw new IllegalStateException("世界仍有玩家，停止工作");
            }
            if(!level.save(true))throw new IllegalStateException("世界儲存失敗："+name);
            if(!getServer().unloadLevel(level,false))throw new IllegalStateException("卸載被核心或其他插件拒絕："+name);
        }
        mark(name,false);
    }
    private void validate(List<Operation> operations) throws Exception {
        if(operations.isEmpty())throw new IllegalArgumentException("待辦列表是空的");
        Set<String> used=new HashSet<>();
        for(Operation op:operations) {
            paths.existing(op.source());
            if(!used.add(op.source().toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("同一世界不可重複排入列表："+op.source());
            if(!List.of("mount","unmount","rename").contains(op.type()))throw new IllegalArgumentException("未知操作");
            Level source=getServer().getLevelByName(op.source());
            if(!op.type().equals("mount") && source!=null) {
                if(source==getServer().getDefaultLevel())throw new IllegalStateException("預設世界不能卸載／更名："+op.source());
                if(!source.getPlayers().isEmpty())fallback(source);
            }
            if(op.type().equals("rename")) {
                paths.available(op.target());
                if(!used.add(op.target().toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("目標名稱重複或串接更名："+op.target());
                // Confirm adapter support before any world is touched. This only writes reference backups.
                Links.prepare(this,op.source(),op.target(),op.link());
            }
        }
        Set<String> removing=new HashSet<>();for(Operation op:operations)if(!op.type().equals("mount"))removing.add(op.source());
        String evacuation=getConfig().getString("evacuation-world","");
        if(!evacuation.isBlank() && removing.contains(evacuation))throw new IllegalArgumentException("移出玩家用的世界不能同時排入卸載／更名");
    }
    private String describe(List<Operation> operations) {
        StringBuilder result=new StringBuilder();int i=0;
        for(Operation op:operations) {Level level=getServer().getLevelByName(op.source());result.append(++i).append(". ").append(op.type()).append(" ").append(op.source());
            if(op.type().equals("rename"))result.append(" → ").append(op.target()).append(op.link()?"［連動開啟］":"［不連動］");
            result.append("；線上玩家 ").append(level==null?0:level.getPlayers().size()).append('\n');}
        return result.append("玩家會先移到其他世界並暫停操作，完成更名後返回原座標；每項完成即生效。失敗時停止剩餘項目，已完成項目保留。\n連動範圍：zero 已知世界欄位、tokaPortal 2.0.0 住家；不改其他插件與離線玩家檔案。\n目的資料夾存在時不覆蓋。這不是完整地圖備份。 ").toString();
    }
    private void journal(String id,JsonObject value,String stage) throws Exception {
        JsonObject next=value.deepCopy();next.addProperty("stage",stage);
        JsonObject old=state;state=state.deepCopy();state.getAsJsonObject("journals").add(id,next);
        try {saveState();value.addProperty("stage",stage);}catch(Exception e){state=old;recovery="journal:"+id;throw e;}
    }
    private void rename(Operation op) throws Exception {
        paths.existing(op.source());paths.available(op.target());
        List<Links.Change> changes=Links.prepare(this,op.source(),op.target(),op.link());
        boolean mounted=getServer().getLevelByName(op.source())!=null;
        Path source=paths.world(op.source()),target=paths.world(op.target());
        String log=UUID.randomUUID().toString();
        JsonObject record=new JsonObject();record.addProperty("source",op.source());record.addProperty("target",op.target());record.addProperty("was-mounted",mounted);
        JsonArray receipts=new JsonArray();for(Links.Change change:changes){JsonObject receipt=new JsonObject();receipt.addProperty("description",change.description());receipt.addProperty("backup",change.token());receipts.add(receipt);}record.add("receipts",receipts);
        byte[] metadata=Files.readAllBytes(source.resolve("level.dat"));record.addProperty("level.dat-before",Base64.getEncoder().encodeToString(metadata));
        journal(log,record,"prepared");boolean moved=false;List<Links.Change> attempted=new ArrayList<>();
        try {
            journal(log,record,"unloading");unmount(op.source());journal(log,record,"unloaded");
            journal(log,record,"renaming");paths.available(op.target());Files.move(source,target);moved=true;journal(log,record,"renamed");
            journal(log,record,"loading-target");
            if(!getServer().loadLevel(op.target()))throw new IllegalStateException("更名後世界無法載入");
            Level renamed=getServer().getLevelByName(op.target());
            renamed.getProvider().updateLevelName(op.target());renamed.getProvider().saveLevelData();
            for(Links.Change change:changes){journal(log,record,"linking-"+attempted.size());attempted.add(change);change.commit();journal(log,record,"linked-"+attempted.size());}
            journal(log,record,"linked");
            journal(log,record,"saving-mounts");
            forget(op.source());Links.mounted(this,op.source(),true);mark(op.target(),true);
            if(!mounted)unmount(op.target());
            // A completed journal must include the persisted new destination for returning players.
            finishReturns(op);
            journal(log,record,"complete");
        } catch(Exception error) {
            if(!storageHealthy){recovery="journal:"+log;getLogger().error("工作紀錄無法保存，停止自動還原；請停服核對世界與引用備份。",error);throw error;}
            try {
                journal(log,record,"rolling-back");
                if(getServer().getLevelByName(op.target())!=null)unmount(op.target());
                Collections.reverse(attempted);for(Links.Change change:attempted)change.rollback();
                if(moved){paths.available(op.source());Files.move(target,source);Files.write(source.resolve("level.dat"),metadata);}
                forget(op.target());mark(op.source(),false);if(mounted)mount(op.source());
                journal(log,record,"rolled-back");
            } catch(Exception rollback) {error.addSuppressed(rollback);recovery="journal:"+log;getLogger().error("自動還原未完成，停止所有世界修改："+recovery,rollback);}
            throw error;
        }
    }
    private void execute(CommandSender sender,int delay) throws Exception {
        mutable();if(!checked.contains(owner(sender)))throw new IllegalStateException("請先執行 /worlds check 檢查全部項目");
        if(delay<0 || delay>3600)throw new IllegalArgumentException("延遲秒數範圍為 0～3600");
        List<Operation> jobs=List.copyOf(plan(sender));validate(jobs);busy=true;checked.remove(owner(sender));
        sender.sendMessage("已排程 "+jobs.size()+" 項，"+delay+" 秒後執行。");
        scheduled=getServer().getScheduler().scheduleDelayedTask(this,()-> {
            executing=true;
            try {
                if(!sender.hasPermission("tokaWorlds.admin"))throw new IllegalStateException("執行權限已移除");
                validate(jobs);evacuate(jobs);
                for(Operation op:jobs) {
                    if(op.type().equals("mount"))mount(op.source());else if(op.type().equals("unmount"))unmount(op.source());else rename(op);
                    finishReturns(op);plan(sender).remove(op);remember(sender);getLogger().info("WORLD_OPERATION_PASS "+op.type()+" "+op.source()+" → "+op.target());
                }
                sender.sendMessage("世界工作全部完成。");
            } catch(Exception error) {sender.sendMessage("工作停止："+error.getMessage());getLogger().error("世界工作失敗",error);}
            finally {releasePlayers();maintenance.clear();busy=false;executing=false;scheduled=null;}
        },Math.max(1,delay*20));
    }
    private void saveReturns() throws Exception {saveState();}
    private boolean teleport(Player player,Location location) {
        internalTeleport=true;try{return player.teleport(location);}finally{internalTeleport=false;}
    }
    private void evacuate(List<Operation> jobs) throws Exception {
        for(Operation op:jobs)if(!op.type().equals("mount"))maintenance.add(op.source());
        for(Operation op:jobs)if(!op.type().equals("mount")) {
            Level source=getServer().getLevelByName(op.source());if(source==null)continue;
            for(Player player:new ArrayList<>(source.getPlayers().values())) {
                Location location=player.getLocation();String id=player.getUniqueId().toString();
                if(returns.containsKey(id))throw new IllegalStateException("玩家還有未完成的返回紀錄："+player.getName());
                returns.put(id,new ReturnPoint(id,op.source(),location.x,location.y,location.z,location.yaw,location.pitch));saveReturns();
                frozen.put(player.getUniqueId(),player.isImmobile());player.setImmobile(true);
                player.sendMessage("世界維護開始，暫停移動與互動；完成後會自動返回。");
                if(!teleport(player,fallback(source).getSafeSpawn().getLocation()))throw new IllegalStateException("玩家未能移出："+player.getName());
            }
        }
    }
    private void finishReturns(Operation op) throws Exception {
        if(op.type().equals("rename")) {
            for(var entry:new ArrayList<>(returns.entrySet()))if(entry.getValue().world().equals(op.source()))returns.put(entry.getKey(),entry.getValue().in(op.target()));
        } else if(op.type().equals("unmount"))returns.values().removeIf(point->point.world().equals(op.source()));
        saveReturns();
    }
    private void returnPlayer(Player player) {
        String id=player.getUniqueId().toString();ReturnPoint point=returns.get(id);if(point==null)return;
        Level target=getServer().getLevelByName(point.world());
        if(target==null || recovery!=null || !storageHealthy){player.sendMessage("原世界尚未恢復，返回紀錄已保留；掛載後可使用 /worlds return。");return;}
        Location location=new Location(point.x(),point.y(),point.z(),point.yaw(),point.pitch(),target);
        if(teleport(player,location)) {
            returns.remove(id);
            try {saveReturns();player.sendMessage("世界維護完成，已返回原位置。");}
            catch(Exception error){returns.put(id,point);getLogger().error("返回紀錄儲存失敗",error);}
        } else player.sendMessage("返回傳送被拒絕，紀錄已保留；可稍後使用 /worlds return。");
    }
    private void releasePlayers() {
        for(var entry:new ArrayList<>(frozen.entrySet())) {
            Player player=getServer().getPlayer(entry.getKey()).orElse(null);
            if(player!=null && player.isOnline()){returnPlayer(player);player.setImmobile(entry.getValue());}
        }
        frozen.clear();
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void move(PlayerMoveEvent event){if(frozen.containsKey(event.getPlayer().getUniqueId())&&!internalTeleport)event.setCancelled();}
    @EventHandler(priority=EventPriority.HIGHEST) public void teleportEvent(PlayerTeleportEvent event){
        if(!internalTeleport && (frozen.containsKey(event.getPlayer().getUniqueId()) || event.getTo().getLevel()!=null && maintenance.contains(event.getTo().getLevel().getFolderName())))event.setCancelled();
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void interact(PlayerInteractEvent event){if(frozen.containsKey(event.getPlayer().getUniqueId()))event.setCancelled();}
    @EventHandler(priority=EventPriority.HIGHEST) public void breaking(BlockBreakEvent event){if(frozen.containsKey(event.getPlayer().getUniqueId()))event.setCancelled();}
    @EventHandler(priority=EventPriority.HIGHEST) public void placing(BlockPlaceEvent event){if(frozen.containsKey(event.getPlayer().getUniqueId()))event.setCancelled();}
    @EventHandler(priority=EventPriority.HIGHEST) public void damage(EntityDamageEvent event){
        if(event.getEntity() instanceof Player player && frozen.containsKey(player.getUniqueId()))event.setCancelled();
        if(event instanceof EntityDamageByEntityEvent by && by.getDamager() instanceof Player player && frozen.containsKey(player.getUniqueId()))event.setCancelled();
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void playerCommand(PlayerCommandPreprocessEvent event){if(frozen.containsKey(event.getPlayer().getUniqueId())){event.setCancelled();event.getPlayer().sendMessage("世界維護中，請稍候。");}}
    @EventHandler public void join(PlayerJoinEvent event){getServer().getScheduler().scheduleDelayedTask(this,()->{if(event.getPlayer().isOnline()&&!busy)returnPlayer(event.getPlayer());},20);}
    @Override public void onDisable(){if(scheduled!=null)scheduled.cancel();releasePlayers();maintenance.clear();busy=false;if(store!=null)try{store.close();}catch(Exception error){getLogger().error("關閉儲存失敗",error);}storageHealthy=false;}
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if(args.length==1 && args[0].equals("return") && sender instanceof Player player){if(!busy)returnPlayer(player);return true;}
        if(!sender.hasPermission("tokaWorlds.admin"))return false;
        try {
            if(args.length==0 && sender instanceof Player player){showMenu(player);return true;}
            String sub=args.length==0?"help":args[0].toLowerCase(Locale.ROOT);
            switch(sub) {
                case "cancel" -> {if(executing)throw new IllegalStateException("工作已開始，無法中途取消");if(scheduled!=null)scheduled.cancel();scheduled=null;busy=false;checked.remove(owner(sender));sender.sendMessage("排程已取消，待辦保留。");}
                case "list" -> {for(String name:paths.names()){Level level=getServer().getLevelByName(name);sender.sendMessage(name+"："+(level==null?"未掛載":"掛載中，玩家 "+level.getPlayers().size()));}}
                case "mount","unmount" -> {mutable();if(args.length!=2)throw new IllegalArgumentException("用法：/worlds "+sub+" 世界名稱");plan(sender).add(new Operation(sub,args[1],"",false));remember(sender);sender.sendMessage("已加入待辦；/worlds check → /worlds confirm");}
                case "rename" -> {mutable();if(args.length<3 || args.length>4)throw new IllegalArgumentException("用法：/worlds rename A B [--link|--no-link]");boolean link=getConfig().getBoolean("link-on-rename",true);if(args.length==4){if(args[3].equals("--no-link"))link=false;else if(args[3].equals("--link"))link=true;else throw new IllegalArgumentException("未知連動選項");}plan(sender).add(new Operation("rename",args[1],args[2],link));remember(sender);sender.sendMessage("已加入待辦；/worlds check → /worlds confirm");}
                case "plan" -> sender.sendMessage(describe(plan(sender)));
                case "clear" -> {mutable();plan(sender).clear();remember(sender);sender.sendMessage("待辦已清空");}
                case "check" -> {mutable();validate(plan(sender));checked.add(owner(sender));sender.sendMessage(describe(plan(sender))+"\n檢查通過；/worlds confirm [延遲秒數]");}
                case "confirm" -> {if(args.length>2)throw new IllegalArgumentException("用法：/worlds confirm [延遲秒數]");execute(sender,args.length==2?Integer.parseInt(args[1]):0);}
                case "resend" -> {
                    if(!(sender instanceof Player self))throw new IllegalArgumentException("只能在遊戲內使用");
                    int radius=args.length>=2?Math.max(1,Math.min(48,Integer.parseInt(args[1]))):16;
                    var level=self.getLevel();var list=new ArrayList<cn.nukkit.math.Vector3>();
                    int cx=self.getFloorX(),cy=self.getFloorY(),cz=self.getFloorZ();
                    for(int x=cx-radius;x<=cx+radius;x++)for(int z=cz-radius;z<=cz+radius;z++){
                        if(level.getChunkIfLoaded(x>>4,z>>4)==null)continue;
                        for(int y=Math.max(-64,cy-radius);y<=Math.min(319,cy+radius);y++){
                            var block=level.getBlock(x,y,z);
                            if(block instanceof cn.nukkit.block.BlockStairs||block instanceof cn.nukkit.block.ConnectionBlock||block instanceof cn.nukkit.block.BlockWall||block instanceof cn.nukkit.block.BlockDoor||block instanceof cn.nukkit.block.BlockFenceGate)list.add(new cn.nukkit.math.Vector3(x,y,z));
                        }
                    }
                    level.sendBlocks(new Player[]{self},list.toArray(new cn.nukkit.math.Vector3[0]),cn.nukkit.network.protocol.UpdateBlockPacket.FLAG_ALL_PRIORITY);
                    sender.sendMessage("已重新送出周圍 "+radius+" 格內的樓梯、柵欄、玻璃片、牆、門與柵欄門，共 "+list.size()+" 個方塊，只影響你自己的畫面");
                }
                default -> sender.sendMessage("/worlds list | mount A | unmount A | rename A B [--link|--no-link] | plan | check | confirm [秒] | clear | cancel | resend [半徑]\n遊戲內輸入 /worlds 可開啟管理視窗。");
            }
        } catch(Exception error) {sender.sendMessage("§e"+error.getMessage());}
        return true;
    }
    private void showMenu(Player player) throws Exception {
        FormWindowSimple form=new FormWindowSimple("世界管理",describe(plan(player)));
        for(String button:List.of("新增世界更名","新增掛載／卸載","檢查全部並排程","清空待辦","列出世界"))form.addButton(new ElementButton(button));
        dialogs.put(player.getUniqueId(),new Dialog(player.showFormWindow(form),"menu",List.of()));
    }
    private void showAdd(Player player,boolean rename) throws Exception {
        List<String> names=paths.names();if(names.isEmpty())throw new IllegalStateException("沒有可管理的世界");
        FormWindowCustom form=new FormWindowCustom(rename?"新增世界更名":"新增掛載／卸載");
        form.addElement(new ElementDropdown("選擇世界 A",names));
        if(rename){form.addElement(new ElementInput("新名稱 B"));form.addElement(new ElementToggle("連動 zero／tokaPortal 世界引用",getConfig().getBoolean("link-on-rename",true)));}
        else form.addElement(new ElementDropdown("操作",List.of("掛載","卸載")));
        dialogs.put(player.getUniqueId(),new Dialog(player.showFormWindow(form),rename?"rename":"mount",names));
    }
    @EventHandler public void response(PlayerFormRespondedEvent event) {
        Player player=event.getPlayer();Dialog dialog=dialogs.get(player.getUniqueId());
        if(dialog==null || dialog.id()!=event.getFormID())return;
        dialogs.remove(player.getUniqueId());if(event.wasClosed() || !player.hasPermission("tokaWorlds.admin"))return;
        try {
            mutable();
            if(dialog.kind().equals("menu")) {
                switch(((FormResponseSimple)event.getResponse()).getClickedButtonId()) {
                    case 0 -> showAdd(player,true);
                    case 1 -> showAdd(player,false);
                    case 2 -> {validate(plan(player));checked.add(owner(player));FormWindowCustom form=new FormWindowCustom("排程確認");form.addElement(new ElementLabel(describe(plan(player))));form.addElement(new ElementInput("延遲秒數（0～3600）","例如 30","30"));form.addElement(new ElementToggle("確認上述全部項目並執行",false));dialogs.put(player.getUniqueId(),new Dialog(player.showFormWindow(form),"confirm",List.of()));}
                    case 3 -> {plan(player).clear();remember(player);showMenu(player);}
                    default -> onCommand(player,null,"worlds",new String[]{"list"});
                }
            } else if(dialog.kind().equals("confirm")) {
                FormResponseCustom response=(FormResponseCustom)event.getResponse();if(response.getToggleResponse(2))execute(player,Integer.parseInt(response.getInputResponse(1)));else showMenu(player);
            } else {
                FormResponseCustom response=(FormResponseCustom)event.getResponse();String source=dialog.names().get(response.getDropdownResponse(0).getElementID());
                Operation op=dialog.kind().equals("rename")?new Operation("rename",source,response.getInputResponse(1),response.getToggleResponse(2)):new Operation(response.getDropdownResponse(1).getElementID()==0?"mount":"unmount",source,"",false);
                paths.existing(op.source());if(op.type().equals("rename"))paths.world(op.target());plan(player).add(op);remember(player);showMenu(player);
            }
        } catch(Exception error) {player.sendMessage("§e"+error.getMessage());}
    }
    @EventHandler public void quit(PlayerQuitEvent event){dialogs.remove(event.getPlayer().getUniqueId());Boolean was=frozen.remove(event.getPlayer().getUniqueId());if(was!=null)event.getPlayer().setImmobile(was);}
}
