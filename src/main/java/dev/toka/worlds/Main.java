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
import java.util.*;
import dev.toka.storage.*;
import dev.toka.i18n.Messages;

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
    private final Map<UUID,cn.nukkit.scheduler.TaskHandler> resends=new HashMap<>();
    private boolean internalTeleport;
    private WorldPaths paths;
    private boolean busy;
    private boolean executing;
    private cn.nukkit.scheduler.TaskHandler scheduled;
    private String recovery;
    private DocumentStore store;
    private JsonObject state;
    private boolean storageHealthy;
    private Messages messages;
    public Messages messages(){return messages;}
    private String t(String key,Object... arguments){return messages.text(key,arguments);}
    private final Map<String,Boolean> mounts=new LinkedHashMap<>();
    @Override public void onEnable() {
        saveDefaultConfig();
        messages=new Messages(getClass().getClassLoader(),getDataFolder().toPath(),getConfig().getString("language","zh-TW"),line->getLogger().warning(line));
        try {
            paths=new WorldPaths(Path.of(getServer().getDataPath(),"worlds"),messages);
            getLogger().info(t("log.loadedWorlds",paths.names().size()));
            Path data=getDataFolder().toPath();
            Backend backend=Backend.valueOf(getConfig().getString("storage.backend","sqlite").toUpperCase(Locale.ROOT));
            Path active=storagePath(data,backend);
            if(!Files.exists(active)) {
                if(WorldState.hasLegacy(data,getConfig().getStringList("mounted-worlds"),getConfig().getStringList("unmounted-worlds")))
                    throw new IllegalStateException(t("err.legacyDataFound"));
                for(Backend other:Backend.values())if(other!=backend&&Files.exists(storagePath(data,other)))
                    throw new IllegalStateException(t("err.otherFormat"));
            }
            store=DocumentStore.open(new StoreConfig(backend,active,1));
            Optional<JsonObject> loaded=store.load("state");
            if(loaded.isEmpty()&&WorldState.hasLegacy(data,getConfig().getStringList("mounted-worlds"),getConfig().getStringList("unmounted-worlds")))
                throw new IllegalStateException(t("err.legacyNotImported"));
            if(loaded.isEmpty())for(Backend other:Backend.values())if(other!=backend&&Files.exists(storagePath(data,other)))
                throw new IllegalStateException(t("err.otherFormatEmpty"));
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
            if(recovery!=null) getLogger().error(t("log.recoveryPending",recovery));
            getServer().getPluginManager().registerEvents(this,this);getServer().getPluginManager().registerEvents(new LegacyItems(this),this);
            getServer().getScheduler().scheduleDelayedTask(this,()-> {
                if(recovery!=null)return;
                for(String name:new ArrayList<>(mounts.keySet()))if(Boolean.TRUE.equals(mounts.get(name))) try {mount(name);}catch(Exception e){getLogger().warning(t("log.mountFailed",name,e.getMessage()));}
                for(String name:new ArrayList<>(mounts.keySet()))if(Boolean.FALSE.equals(mounts.get(name))) try {unmount(name);}catch(Exception e){getLogger().warning(t("log.unmountFailed",name,e.getMessage()));}
            },1);
        } catch(Exception error) {getLogger().error(t("log.initFailed"),error);getServer().getPluginManager().disablePlugin(this);}
    }
    private String owner(CommandSender sender){return sender instanceof Player p?p.getUniqueId().toString():"console";}
    private List<Operation> plan(CommandSender sender){return plans.computeIfAbsent(owner(sender),key->new ArrayList<>());}
    private List<Operation> planView(CommandSender sender){return plans.getOrDefault(owner(sender),List.of());}
    private void mutable(){if(!storageHealthy)throw new IllegalStateException(t("err.storageNotReady"));if(busy)throw new IllegalStateException(t("err.busy"));if(recovery!=null)throw new IllegalStateException(t("err.recoveryFirst",recovery));}
    static Path storagePath(Path data,Backend backend) {
        return data.resolve("storage").resolve(backend.name().toLowerCase(Locale.ROOT));
    }
    private void saveState() throws Exception {
        if(!storageHealthy)throw new IllegalStateException(t("err.storageFailed"));
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
    /** Rejects names that teleport commands read as keywords (reserved-names) and suggests alternatives. */
    private void checkName(String name) {
        java.util.List<String> words = getConfig().getStringList("reserved-names");
        if (words == null || words.isEmpty()) {
            words = java.util.List.of("help", "list", "set", "del", "edit");
        }
        String clean = name.strip();
        if (words.stream().anyMatch(word -> word.equalsIgnoreCase(clean))) {
            throw new IllegalArgumentException(t("err.reservedName", clean, clean + "_world", "my_" + clean));
        }
    }

    private void mount(String name) throws Exception {
        if(!storageHealthy)throw new IllegalStateException(t("err.storageNotReadyMount"));
        paths.existing(name);
        if(!getServer().loadLevel(name)) throw new IllegalStateException(t("err.coreMountFailed",name));
        mark(name,true);
    }
    private Level fallback(Level source) {
        String name=getConfig().getString("evacuation-world","");
        Level target=name.isBlank()?getServer().getDefaultLevel():getServer().getLevelByName(name);
        if(target==null || target==source)throw new IllegalStateException(t("err.needOtherWorld"));return target;
    }
    private void unmount(String name) throws Exception {
        if(!storageHealthy)throw new IllegalStateException(t("err.storageNotReadyUnmount"));
        Level level=getServer().getLevelByName(name);
        if(level!=null) {
            if(level==getServer().getDefaultLevel())throw new IllegalStateException(t("err.defaultWorldNoUnmount"));
            if(!level.getPlayers().isEmpty()) {
                Level target=fallback(level);
                for(Player player:new ArrayList<>(level.getPlayers().values()))
                    if(!player.teleport(target.getSafeSpawn()))throw new IllegalStateException(t("err.playerCannotMove",player.getName()));
                if(!level.getPlayers().isEmpty())throw new IllegalStateException(t("err.worldStillHasPlayers"));
            }
            if(!level.save(true))throw new IllegalStateException(t("err.worldSaveFailed",name));
            if(!getServer().unloadLevel(level,false))throw new IllegalStateException(t("err.unloadRefused",name));
        }
        mark(name,false);
    }
    private void validate(List<Operation> operations) throws Exception {
        if(operations.isEmpty())throw new IllegalArgumentException(t("err.planEmpty"));
        Set<String> used=new HashSet<>();
        for(Operation op:operations) {
            paths.existing(op.source());
            if(!used.add(op.source().toLowerCase(Locale.ROOT)))throw new IllegalArgumentException(t("err.duplicateWorld",op.source()));
            if(!List.of("mount","unmount","rename").contains(op.type()))throw new IllegalArgumentException(t("err.unknownOp"));
            Level source=getServer().getLevelByName(op.source());
            if(!op.type().equals("mount") && source!=null) {
                if(source==getServer().getDefaultLevel())throw new IllegalStateException(t("err.defaultWorldNamed",op.source()));
                if(!source.getPlayers().isEmpty())fallback(source);
            }
            if(op.type().equals("rename")) {
                paths.available(op.target());
                if(!used.add(op.target().toLowerCase(Locale.ROOT)))throw new IllegalArgumentException(t("err.targetDuplicate",op.target()));
                // Confirm adapter support before any world is touched. This only writes reference backups.
                Links.prepare(this,op.source(),op.target(),op.link());
            }
        }
        Set<String> removing=new HashSet<>();for(Operation op:operations)if(!op.type().equals("mount"))removing.add(op.source());
        String evacuation=getConfig().getString("evacuation-world","");
        if(!evacuation.isBlank() && removing.contains(evacuation))throw new IllegalArgumentException(t("err.evacuationConflict"));
    }
    private String describe(List<Operation> operations) {
        StringBuilder result=new StringBuilder();int i=0;
        for(Operation op:operations) {Level level=getServer().getLevelByName(op.source());int online=level==null?0:level.getPlayers().size();
            if(op.type().equals("rename"))result.append(t("msg.plan.rename",++i,opName(op.type()),op.source(),op.target(),op.link()?t("status.link.on"):t("status.link.off"),online));
            else result.append(t("msg.plan.item",++i,opName(op.type()),op.source(),online));
            result.append('\n');}
        return result.append(t("msg.plan.footer")).toString();
    }
    private String opName(String type){return switch(type){case "mount"->t("status.op.mount");case "unmount"->t("status.op.unmount");default->t("status.op.rename");};}
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
            if(!getServer().loadLevel(op.target()))throw new IllegalStateException(t("err.renameLoadFailed"));
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
            if(!storageHealthy){recovery="journal:"+log;getLogger().error(t("log.journalLost"),error);throw error;}
            try {
                journal(log,record,"rolling-back");
                if(getServer().getLevelByName(op.target())!=null)unmount(op.target());
                Collections.reverse(attempted);for(Links.Change change:attempted)change.rollback();
                if(moved){paths.available(op.source());Files.move(target,source);Files.write(source.resolve("level.dat"),metadata);}
                forget(op.target());mark(op.source(),false);if(mounted)mount(op.source());
                journal(log,record,"rolled-back");
            } catch(Exception rollback) {error.addSuppressed(rollback);recovery="journal:"+log;getLogger().error(t("log.rollbackFailed",recovery),rollback);}
            throw error;
        }
    }
    private void execute(CommandSender sender,int delay) throws Exception {
        mutable();if(!checked.contains(owner(sender)))throw new IllegalStateException(t("err.notChecked"));
        if(delay<0 || delay>3600)throw new IllegalArgumentException(t("err.delayRange"));
        List<Operation> jobs=List.copyOf(plan(sender));validate(jobs);busy=true;checked.remove(owner(sender));
        sender.sendMessage(t("msg.scheduled",jobs.size(),delay));
        scheduled=getServer().getScheduler().scheduleDelayedTask(this,()-> {
            executing=true;
            try {
                if(!sender.hasPermission("tokaWorlds.admin"))throw new IllegalStateException(t("err.permissionRemoved"));
                validate(jobs);evacuate(jobs);
                for(Operation op:jobs) {
                    if(op.type().equals("mount"))mount(op.source());else if(op.type().equals("unmount"))unmount(op.source());else rename(op);
                    finishReturns(op);plan(sender).remove(op);remember(sender);getLogger().info("WORLD_OPERATION_PASS "+op.type()+" "+op.source()+" → "+op.target());
                }
                sender.sendMessage(t("msg.allDone"));
            } catch(Exception error) {sender.sendMessage(t("msg.stopped",error.getMessage()));getLogger().error(t("log.jobFailed"),error);}
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
                if(returns.containsKey(id))throw new IllegalStateException(t("err.returnPending",player.getName()));
                returns.put(id,new ReturnPoint(id,op.source(),location.x,location.y,location.z,location.yaw,location.pitch));saveReturns();
                frozen.put(player.getUniqueId(),player.isImmobile());player.setImmobile(true);
                player.sendMessage(t("msg.maintenance.start"));
                if(!teleport(player,fallback(source).getSafeSpawn().getLocation()))throw new IllegalStateException(t("err.playerNotMoved",player.getName()));
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
        if(target==null || recovery!=null || !storageHealthy){player.sendMessage(t("msg.return.notRestored"));return;}
        Location location=new Location(point.x(),point.y(),point.z(),point.yaw(),point.pitch(),target);
        if(teleport(player,location)) {
            returns.remove(id);
            try {saveReturns();player.sendMessage(t("msg.return.done"));}
            catch(Exception error){returns.put(id,point);getLogger().error(t("log.returnSaveFailed"),error);}
        } else player.sendMessage(t("msg.return.rejected"));
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
    @EventHandler(priority=EventPriority.HIGHEST) public void playerCommand(PlayerCommandPreprocessEvent event){if(frozen.containsKey(event.getPlayer().getUniqueId())){event.setCancelled();event.getPlayer().sendMessage(t("msg.maintenance.busy"));}}
    @EventHandler public void join(PlayerJoinEvent event){getServer().getScheduler().scheduleDelayedTask(this,()->{if(event.getPlayer().isOnline()&&!busy)returnPlayer(event.getPlayer());},20);}
    @Override public void onDisable(){if(scheduled!=null)scheduled.cancel();resends.values().forEach(cn.nukkit.scheduler.TaskHandler::cancel);resends.clear();releasePlayers();maintenance.clear();busy=false;if(store!=null)try{store.close();}catch(Exception error){getLogger().error(t("log.closeFailed"),error);}storageHealthy=false;}
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if(args.length==1 && args[0].equals("return") && sender instanceof Player player){if(!busy)returnPlayer(player);return true;}
        if(!sender.hasPermission("tokaWorlds.admin"))return false;
        try {
            if(args.length==0 && sender instanceof Player player){showMenu(player);return true;}
            String sub=args.length==0?"help":args[0].toLowerCase(Locale.ROOT);
            switch(sub) {
                case "cancel" -> {if(executing)throw new IllegalStateException(t("err.alreadyStarted"));if(scheduled!=null)scheduled.cancel();scheduled=null;busy=false;checked.remove(owner(sender));sender.sendMessage(t("msg.cancelled"));}
                case "list" -> {for(String name:paths.names()){Level level=getServer().getLevelByName(name);sender.sendMessage(level==null?t("msg.list.unmounted",name):t("msg.list.mounted",name,level.getPlayers().size()));}}
                case "mount","unmount" -> {mutable();if(args.length!=2)throw new IllegalArgumentException(t("err.usage.mount",sub));if(sub.equals("mount"))checkName(args[1]);plan(sender).add(new Operation(sub,args[1],"",false));remember(sender);sender.sendMessage(t("msg.added"));}
                case "rename" -> {mutable();if(args.length<3 || args.length>4)throw new IllegalArgumentException(t("err.usage.rename"));checkName(args[2]);boolean link=getConfig().getBoolean("link-on-rename",true);if(args.length==4){if(args[3].equals("--no-link"))link=false;else if(args[3].equals("--link"))link=true;else throw new IllegalArgumentException(t("err.unknownLink"));}plan(sender).add(new Operation("rename",args[1],args[2],link));remember(sender);sender.sendMessage(t("msg.added"));}
                case "plan" -> sender.sendMessage(describe(planView(sender)));
                case "clear" -> {mutable();plan(sender).clear();remember(sender);sender.sendMessage(t("msg.cleared"));}
                case "check" -> {mutable();validate(plan(sender));checked.add(owner(sender));sender.sendMessage(describe(plan(sender))+"\n"+t("msg.checkPass"));}
                case "confirm" -> {if(args.length>2)throw new IllegalArgumentException(t("err.usage.confirm"));execute(sender,args.length==2?Integer.parseInt(args[1]):0);}
                case "resend" -> {
                    if(!(sender instanceof Player self))throw new IllegalArgumentException(t("err.playerOnly"));
                    int radius=args.length>=2?Math.max(1,Math.min(48,Integer.parseInt(args[1]))):16;
                    if(resends.containsKey(self.getUniqueId()))throw new IllegalStateException(t("err.resendRunning"));
                    Resend job=new Resend(self,radius);
                    resends.put(self.getUniqueId(),getServer().getScheduler().scheduleRepeatingTask(this,job,1));
                    sender.sendMessage(t("msg.resendStart",radius));
                }
                case "help" -> {for(String line:messages.lines("help.worlds"))sender.sendMessage(line);}
                default -> sender.sendMessage(t("cmd.worlds.usage"));
            }
        } catch(Exception error) {sender.sendMessage(t("err.show",error.getMessage()));}
        return true;
    }
    /** Resend scans two x-slices per tick; the running task is the only state. */
    static final int RESEND_SLICES=2;
    static int sliceEnd(int from,int last){return Math.min(last,from+RESEND_SLICES-1);}
    private void stopResend(UUID id){var task=resends.remove(id);if(task!=null)task.cancel();}
    private final class Resend implements Runnable {
        private final Player player;private final Level level;private final int radius,cx,cy,cz,last;private int x,total;
        Resend(Player player,int radius){this.player=player;level=player.getLevel();this.radius=radius;cx=player.getFloorX();cy=player.getFloorY();cz=player.getFloorZ();x=cx-radius;last=cx+radius;}
        @Override public void run() {
            UUID id=player.getUniqueId();
            if(!player.isOnline())stopResend(id);
            else if(player.getLevel()!=level||!getServer().getLevels().containsValue(level)){stopResend(id);player.sendMessage(t("msg.resendAborted"));}
            else try {
                var list=new ArrayList<cn.nukkit.math.Vector3>();
                int end=sliceEnd(x,last);
                for(;x<=end;x++)for(int z=cz-radius;z<=cz+radius;z++){
                    if(level.getChunkIfLoaded(x>>4,z>>4)==null)continue;
                    for(int y=Math.max(-64,cy-radius);y<=Math.min(319,cy+radius);y++){
                        var block=level.getBlock(x,y,z);
                        if(block instanceof cn.nukkit.block.BlockStairs||block instanceof cn.nukkit.block.ConnectionBlock||block instanceof cn.nukkit.block.BlockWall||block instanceof cn.nukkit.block.BlockDoor||block instanceof cn.nukkit.block.BlockFenceGate)list.add(new cn.nukkit.math.Vector3(x,y,z));
                    }
                }
                total+=list.size();
                level.sendBlocks(new Player[]{player},list.toArray(new cn.nukkit.math.Vector3[0]),cn.nukkit.network.protocol.UpdateBlockPacket.FLAG_ALL_PRIORITY);
                if(x>last){stopResend(id);player.sendMessage(t("msg.resend",radius,total));}
            } catch(Exception error) {stopResend(id);player.sendMessage(t("err.show",error.getMessage()));}
        }
    }
    private void showMenu(Player player) throws Exception {
        FormWindowSimple form=new FormWindowSimple(t("ui.menu.title"),describe(planView(player)));
        for(String button:List.of(t("ui.menu.rename"),t("ui.menu.mount"),t("ui.menu.check"),t("ui.menu.clear"),t("ui.menu.list"),t("ui.menu.close")))form.addButton(new ElementButton(button));
        dialogs.put(player.getUniqueId(),new Dialog(player.showFormWindow(form),"menu",List.of()));
    }
    private void showAdd(Player player,boolean rename) throws Exception {
        List<String> names=paths.names();if(names.isEmpty())throw new IllegalStateException(t("err.noWorlds"));
        FormWindowCustom form=new FormWindowCustom(rename?t("ui.menu.rename"):t("ui.menu.mount"));
        form.addElement(new ElementDropdown(t("ui.add.world"),names));
        if(rename){form.addElement(new ElementInput(t("ui.add.newName")));form.addElement(new ElementToggle(t("ui.add.link"),getConfig().getBoolean("link-on-rename",true)));}
        else form.addElement(new ElementDropdown(t("ui.add.action"),List.of(t("ui.add.mount"),t("ui.add.unmount"))));
        dialogs.put(player.getUniqueId(),new Dialog(player.showFormWindow(form),rename?"rename":"mount",names));
    }
    @EventHandler public void response(PlayerFormRespondedEvent event) {
        Player player=event.getPlayer();Dialog dialog=dialogs.get(player.getUniqueId());
        if(dialog==null || dialog.id()!=event.getFormID())return;
        dialogs.remove(player.getUniqueId());if(!player.hasPermission("tokaWorlds.admin"))return;
        if(event.wasClosed()){if(!dialog.kind().equals("menu"))try{showMenu(player);}catch(Exception error){player.sendMessage(t("err.show",error.getMessage()));}return;}
        try {
            mutable();
            if(dialog.kind().equals("menu")) {
                switch(((FormResponseSimple)event.getResponse()).getClickedButtonId()) {
                    case 0 -> showAdd(player,true);
                    case 1 -> showAdd(player,false);
                    case 2 -> {validate(plan(player));checked.add(owner(player));FormWindowCustom form=new FormWindowCustom(t("ui.confirm.title"));form.addElement(new ElementLabel(describe(planView(player))));form.addElement(new ElementInput(t("ui.confirm.delay"),t("ui.confirm.placeholder"),"30"));form.addElement(new ElementToggle(t("ui.confirm.toggle"),false));dialogs.put(player.getUniqueId(),new Dialog(player.showFormWindow(form),"confirm",List.of()));}
                    case 3 -> {plan(player).clear();remember(player);showMenu(player);}
                    case 4 -> onCommand(player,null,"worlds",new String[]{"list"});
                    default -> {}
                }
            } else if(dialog.kind().equals("confirm")) {
                FormResponseCustom response=(FormResponseCustom)event.getResponse();if(response.getToggleResponse(2))execute(player,Integer.parseInt(response.getInputResponse(1)));else showMenu(player);
            } else {
                FormResponseCustom response=(FormResponseCustom)event.getResponse();String source=dialog.names().get(response.getDropdownResponse(0).getElementID());
                Operation op=dialog.kind().equals("rename")?new Operation("rename",source,response.getInputResponse(1),response.getToggleResponse(2)):new Operation(response.getDropdownResponse(1).getElementID()==0?"mount":"unmount",source,"",false);
                paths.existing(op.source());if(!op.type().equals("unmount"))checkName(op.type().equals("rename")?op.target():op.source());if(op.type().equals("rename"))paths.world(op.target());plan(player).add(op);remember(player);showMenu(player);
            }
        } catch(Exception error) {player.sendMessage(t("err.show",error.getMessage()));}
    }
    @EventHandler public void quit(PlayerQuitEvent event){stopResend(event.getPlayer().getUniqueId());dialogs.remove(event.getPlayer().getUniqueId());Boolean was=frozen.remove(event.getPlayer().getUniqueId());if(was!=null)event.getPlayer().setImmobile(was);}
}
