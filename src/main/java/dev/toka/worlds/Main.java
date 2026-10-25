package dev.toka.worlds;

import cn.nukkit.Player;
import cn.nukkit.command.*;
import cn.nukkit.command.data.CommandParameter;
import cn.nukkit.command.data.CommandParamType;
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
    private record Pending(int id,java.util.function.Consumer<PlayerFormRespondedEvent> handler) {}
    private interface Step {void run() throws Exception;}
    private static final String ADMIN="tokaWorlds.admin";
    private static final int PER_PAGE=10,SEARCH_FROM=8,HELP_LINES=8;
    private final Map<String,List<Operation>> plans=new HashMap<>();
    private final Set<String> checked=new HashSet<>();
    private final Map<UUID,Pending> forms=new HashMap<>();
    private final Map<UUID,String> queries=new HashMap<>();
    private List<String> suggested=List.of();
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
            suggestWorldCommands();
            getLogger().info(t("log.loadedWorlds",paths.names().size()));
            Path data=getDataFolder().toPath();
            Backend backend=Backend.valueOf(getConfig().getString("storage.backend","sqlite").toUpperCase(Locale.ROOT));
            Path active=storagePath(data,backend);
            if(!Files.exists(active)) {
                for(Backend other:Backend.values())if(other!=backend&&Files.exists(storagePath(data,other)))
                    throw new IllegalStateException(t("err.otherFormat"));
            }
            store=DocumentStore.open(new StoreConfig(backend,active,1));
            Optional<JsonObject> loaded=store.load("state");
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
            loadWorlds();
        } catch(Exception error) {getLogger().error(t("log.initFailed"),error);getServer().getPluginManager().disablePlugin(this);}
    }
    /** Runs inside onEnable so the worlds are ready before the server reports that it started; one failing world only warns. */
    private void loadWorlds() {
        if(recovery!=null)return;
        for(String name:new ArrayList<>(mounts.keySet()))if(Boolean.TRUE.equals(mounts.get(name))) try {mount(name);}catch(Exception e){getLogger().warning(t("log.mountFailed",name,e.getMessage()));}
        for(String name:new ArrayList<>(mounts.keySet()))if(Boolean.FALSE.equals(mounts.get(name))) try {unmount(name);}catch(Exception e){getLogger().warning(t("log.unmountFailed",name,e.getMessage()));}
        // worlds named in autoload-worlds are loaded at every start, whatever was recorded about them
        for(String name:getConfig().getStringList("autoload-worlds"))
            if(getServer().getLevelByName(name)==null) try {mount(name);}catch(Exception e){getLogger().warning(t("log.mountFailed",name,e.getMessage()));}
        refreshCommands();
    }
    /** Rebuilds the world name list of /world and sends it to the players again. */
    private void refreshCommands() {
        try {suggestWorldCommands();}catch(java.io.IOException e){getLogger().warning(t("log.suggestFailed",e.getMessage()));return;}
        for(Player player:getServer().getOnlinePlayers().values())player.sendCommandData();
    }
    /** For worlds that appear on disk without going through this plugin. */
    private void refreshIfChanged() {
        try {if(!paths.names().equals(suggested))refreshCommands();}catch(java.io.IOException ignored){}
    }
    private void suggestWorldCommands() throws java.io.IOException{
        suggested=paths.names();
        String[] names=suggested.stream().filter(name->name.equals(name.toLowerCase(Locale.ROOT))).toArray(String[]::new);
        Command registered=(Command)getCommand("world");
        if(registered==null)return;
        registered.getCommandParameters().clear();
        CommandParameter action=CommandParameter.newEnum("action",new String[]{"help","return","cancel","list","mount","unmount","rename","plan","clear","check","confirm","resend","reload"});
        registered.addCommandParameters("action",new CommandParameter[]{action});
        CommandParameter world=names.length==0?CommandParameter.newType("world",CommandParamType.STRING):CommandParameter.newEnum("world",new cn.nukkit.command.data.CommandEnum("Worlds",names));
        registered.addCommandParameters("mount",new CommandParameter[]{CommandParameter.newEnum("action",new String[]{"mount"}),world});
        registered.addCommandParameters("unmount",new CommandParameter[]{CommandParameter.newEnum("action",new String[]{"unmount"}),world});
        registered.addCommandParameters("rename",new CommandParameter[]{CommandParameter.newEnum("action",new String[]{"rename"}),world,CommandParameter.newType("new_name",CommandParamType.STRING),CommandParameter.newEnum("link_mode",true,new cn.nukkit.command.data.CommandEnum("LinkMode",new String[]{"--link","--no-link"}))});
        registered.addCommandParameters("confirm",new CommandParameter[]{CommandParameter.newEnum("action",new String[]{"confirm"}),CommandParameter.newType("delay_seconds",true,CommandParamType.INT)});
        registered.addCommandParameters("resend",new CommandParameter[]{CommandParameter.newEnum("action",new String[]{"resend"}),CommandParameter.newType("radius",true,CommandParamType.INT)});
        for(String word:List.of("plan","check","clear","cancel","list","return","reload"))
            registered.addCommandParameters(word,new CommandParameter[]{CommandParameter.newEnum("action",new cn.nukkit.command.data.CommandEnum("Worlds"+Character.toUpperCase(word.charAt(0))+word.substring(1),word))});
        registered.addCommandParameters("help",new CommandParameter[]{CommandParameter.newEnum("action",new cn.nukkit.command.data.CommandEnum("WorldsHelp","help")),CommandParameter.newType("page",true,CommandParamType.INT)});
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
    /** The one place where a mount, unmount or rename enters the plan, for the command and the windows. */
    private void addOperation(CommandSender sender,Operation op) throws Exception {
        mutable();
        paths.existing(op.source());
        if(op.type().equals("mount"))checkName(op.source());
        if(op.type().equals("rename")){checkName(op.target());paths.world(op.target());}
        plan(sender).add(op);remember(sender);
    }
    private void cancelSchedule(CommandSender sender) {
        if(executing)throw new IllegalStateException(t("err.alreadyStarted"));
        if(scheduled!=null)scheduled.cancel();
        scheduled=null;busy=false;checked.remove(owner(sender));
    }
    private void startResend(Player self,int radius) {
        if(resends.containsKey(self.getUniqueId()))throw new IllegalStateException(t("err.resendRunning"));
        Resend job=new Resend(self,radius);
        resends.put(self.getUniqueId(),getServer().getScheduler().scheduleRepeatingTask(this,job,1));
        self.sendMessage(t("msg.resendStart",radius));
    }
    /** Edits only the autoload-worlds line of config.yml so its comments stay. */
    private void setAutoload(String name,boolean on) throws Exception {
        List<String> list=new ArrayList<>(getConfig().getStringList("autoload-worlds"));
        if(on&&!list.contains(name))list.add(name);
        if(!on)list.remove(name);
        Path file=getDataFolder().toPath().resolve("config.yml");
        AutoloadConfig.write(file,AutoloadConfig.edit(Files.readString(file,java.nio.charset.StandardCharsets.UTF_8),list));
        reloadConfig();
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
    private void rename(Operation op,Timing timing) throws Exception {
        paths.existing(op.source());paths.available(op.target());
        List<Links.Change> changes=Links.prepare(this,op.source(),op.target(),op.link());
        boolean mounted=getServer().getLevelByName(op.source())!=null;
        Path source=paths.world(op.source()),target=paths.world(op.target());
        String log=UUID.randomUUID().toString();
        JsonObject record=new JsonObject();record.addProperty("source",op.source());record.addProperty("target",op.target());record.addProperty("was-mounted",mounted);
        JsonArray receipts=new JsonArray();for(Links.Change change:changes){receipts.add(Receipts.write(change.code(),change.args(),change.token(),messages));}record.add("receipts",receipts);
        byte[] metadata=Files.readAllBytes(source.resolve("level.dat"));record.addProperty("level.dat-before",Base64.getEncoder().encodeToString(metadata));
        journal(log,record,"prepared");timing.mark(t("log.timing.prepare"));boolean moved=false;List<Links.Change> attempted=new ArrayList<>();
        try {
            journal(log,record,"unloading");unmount(op.source());journal(log,record,"unloaded");timing.mark(t("log.timing.unload"));
            journal(log,record,"renaming");paths.available(op.target());Files.move(source,target);moved=true;journal(log,record,"renamed");timing.mark(t("log.timing.move"));
            journal(log,record,"loading-target");
            if(!getServer().loadLevel(op.target()))throw new IllegalStateException(t("err.renameLoadFailed"));
            Level renamed=getServer().getLevelByName(op.target());
            renamed.getProvider().updateLevelName(op.target());renamed.getProvider().saveLevelData();timing.mark(t("log.timing.load"));
            for(Links.Change change:changes){journal(log,record,"linking-"+attempted.size());attempted.add(change);change.commit();journal(log,record,"linked-"+attempted.size());}
            journal(log,record,"linked");timing.mark(t("log.timing.link"));
            journal(log,record,"saving-mounts");
            forget(op.source());mark(op.target(),true);
            if(!mounted)unmount(op.target());
            timing.mark(t("log.timing.mounts"));
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
                Timing whole=new Timing(System::nanoTime);
                validate(jobs);whole.mark(t("log.timing.validate"));evacuate(jobs);whole.mark(t("log.timing.evacuate"));
                for(Operation op:jobs) {
                    Timing timing=new Timing(System::nanoTime);
                    if(op.type().equals("mount")){mount(op.source());timing.mark(t("log.timing.mount"));}
                    else if(op.type().equals("unmount")){unmount(op.source());timing.mark(t("log.timing.unmount"));}
                    else rename(op,timing);
                    finishReturns(op);plan(sender).remove(op);remember(sender);timing.mark(t("log.timing.finish"));
                    getLogger().info(t("log.opPass",opName(op.type()),op.type().equals("rename")?op.source()+" → "+op.target():op.source()));
                    logTiming("log.timing.operation",timing,opName(op.type()),op.type().equals("rename")?op.source()+" → "+op.target():op.source());
                    whole.mark(opName(op.type())+" "+op.source());
                }
                sender.sendMessage(t("msg.allDone"));
                logTiming("log.timing.total",whole,jobs.size());
            } catch(Exception error) {sender.sendMessage(t("msg.stopped",error.getMessage()));getLogger().error(t("log.jobFailed"),error);}
            finally {releasePlayers();maintenance.clear();busy=false;executing=false;scheduled=null;refreshCommands();}
        },Math.max(1,delay*20));
    }
    /** One log line per finished operation; the warning colour is used when the total passes slow-operation-ms. */
    private void logTiming(String key,Timing timing,Object... arguments) {
        long total=timing.totalMs();boolean slow=Timing.slow(total,getConfig().getLong("slow-operation-ms",Timing.DEFAULT_SLOW_MS));
        Object[] all=Arrays.copyOf(arguments,arguments.length+2);all[arguments.length]=total;all[arguments.length+1]=timing.detail();
        getLogger().info(t(slow?key+"Slow":key,all));
    }
    private void saveReturns()throws Exception {saveState();}
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
    @Override public void onDisable(){if(scheduled!=null)scheduled.cancel();forms.clear();queries.clear();resends.values().forEach(cn.nukkit.scheduler.TaskHandler::cancel);resends.clear();releasePlayers();maintenance.clear();busy=false;if(store!=null)try{store.close();}catch(Exception error){getLogger().error(t("log.closeFailed"),error);}storageHealthy=false;}
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        String sub=args.length==0?(sender instanceof Player?"menu":"help"):args[0].toLowerCase(Locale.ROOT);
        if(sub.equals("return")&&sender instanceof Player player){if(!busy)returnPlayer(player);return true;}
        if(sub.equals("help")){sendHelp(sender,args.length>1?args[1]:null);return true;}
        if(!sender.hasPermission(ADMIN)){sender.sendMessage(t("err.noPermission"));return true;}
        try {
            switch(sub) {
                case "menu" -> showMenu((Player)sender,0);
                case "cancel" -> {cancelSchedule(sender);sender.sendMessage(t("msg.cancelled"));}
                case "list" -> {refreshIfChanged();for(String name:paths.names()){Level level=getServer().getLevelByName(name);sender.sendMessage(level==null?t("msg.list.unmounted",name):t("msg.list.mounted",name,level.getPlayers().size()));}}
                case "mount","unmount" -> {if(args.length!=2)throw new IllegalArgumentException(t("err.usage.mount",sub));addOperation(sender,new Operation(sub,args[1],"",false));sender.sendMessage(t("msg.added"));}
                case "rename" -> {if(args.length<3 || args.length>4)throw new IllegalArgumentException(t("err.usage.rename"));boolean link=getConfig().getBoolean("link-on-rename",true);if(args.length==4){if(args[3].equals("--no-link"))link=false;else if(args[3].equals("--link"))link=true;else throw new IllegalArgumentException(t("err.unknownLink"));}addOperation(sender,new Operation("rename",args[1],args[2],link));sender.sendMessage(t("msg.added"));}
                case "plan" -> sender.sendMessage(describe(planView(sender)));
                case "clear" -> {mutable();plan(sender).clear();remember(sender);sender.sendMessage(t("msg.cleared"));}
                case "check" -> {mutable();validate(plan(sender));checked.add(owner(sender));sender.sendMessage(describe(plan(sender))+"\n"+t("msg.checkPass"));}
                case "confirm" -> {if(args.length>2)throw new IllegalArgumentException(t("err.usage.confirm"));execute(sender,args.length==2?Integer.parseInt(args[1]):0);}
                case "resend" -> {
                    if(!(sender instanceof Player self))throw new IllegalArgumentException(t("err.playerOnly"));
                    startResend(self,args.length>=2?Math.max(1,Math.min(48,Integer.parseInt(args[1]))):16);
                }
                case "reload" -> {reloadConfig();messages.language(getConfig().getString("language","zh-TW"));refreshCommands();sender.sendMessage(t("msg.reloaded"));}
                default -> sender.sendMessage(t("cmd.worlds.usage"));
            }
        } catch(Exception error) {sender.sendMessage(t("err.show",error.getMessage()));}
        return true;
    }
    /** Chat help in pages; a word that is not a number shows the first page. */
    private void sendHelp(CommandSender sender,String requested) {
        var page=dev.toka.i18n.Pager.of(messages.lines("help.worlds"),HELP_LINES,dev.toka.i18n.Pager.parse(requested,1));
        sender.sendMessage(t("msg.helpTitle",page.page(),page.pages()));
        for(String line:page.lines())sender.sendMessage(line);
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
    private static final String ICON_PLAN="textures/items/book_writable",ICON_RESEND="textures/items/redstone_dust",ICON_RETURN="textures/items/ender_pearl",
        ICON_ON="textures/items/map_filled",ICON_OFF="textures/blocks/barrier",ICON_SEARCH="textures/items/name_tag",ICON_HELP="textures/items/book_normal",
        ICON_CLOSE="textures/ui/cancel",ICON_PAGE="textures/items/arrow";
    /** Shows a window and remembers what to do with its answer; the answer is dropped when the player answers another window. */
    private void open(Player player,cn.nukkit.form.window.FormWindow window,java.util.function.Consumer<PlayerFormRespondedEvent> handler) {
        forms.put(player.getUniqueId(),new Pending(player.showFormWindow(window),handler));
    }
    private ElementButton button(String text,String icon) {
        return icon==null||!getConfig().getBoolean("window-icons",true)?new ElementButton(text):new ElementButton(text,new ElementButtonImageData(ElementButtonImageData.IMAGE_DATA_TYPE_PATH,icon));
    }
    /** A window of buttons; each button is added together with the step it does, and an error in a step goes to the player. */
    private final class Buttons {
        private final Player player;private final FormWindowSimple window;private final List<Step> steps=new ArrayList<>();
        Buttons(Player player,String title,String content){this.player=player;window=new FormWindowSimple(title,content);}
        Buttons add(String text,String icon,Step step){window.addButton(button(text,icon));steps.add(step);return this;}
        Buttons back(Step step){return add(t("ui.common.back"),ICON_PAGE,step);}
        Buttons close(){return add(t("ui.common.close"),ICON_CLOSE,()->{});}
        void show(){
            open(player,window,event-> {
                if(event.wasClosed()||!(event.getResponse() instanceof FormResponseSimple answer))return;
                int id=answer.getClickedButtonId();
                if(id>=0&&id<steps.size())try {steps.get(id).run();}catch(Exception error){player.sendMessage(t("err.show",error.getMessage()));}
            });
        }
    }
    private void showMenu(Player player,int page) throws Exception {
        refreshIfChanged();
        List<String> all=paths.names();
        String query=queries.getOrDefault(player.getUniqueId(),"");
        List<String> shown=all.stream().filter(name->query.isEmpty()||name.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))).toList();
        int pages=Math.max(1,(shown.size()+PER_PAGE-1)/PER_PAGE),at=Math.max(0,Math.min(page,pages-1));
        int mounted=(int)all.stream().filter(name->getServer().getLevelByName(name)!=null).count();
        String content=t("ui.menu.content",String.format("%,d",all.size()),String.format("%,d",mounted));
        if(!query.isEmpty())content+="\n"+t("ui.menu.filter",query,String.format("%,d",shown.size()));
        Buttons menu=new Buttons(player,pages>1?t("ui.menu.titlePage",at+1,pages):t("ui.menu.title"),content);
        int planned=planView(player).size();
        menu.add(t("ui.menu.plan",planned),ICON_PLAN,()->showPlan(player));
        menu.add(t("ui.menu.resend"),ICON_RESEND,()->showResend(player));
        if(returns.containsKey(player.getUniqueId().toString()))menu.add(t("ui.menu.return"),ICON_RETURN,()->{if(!busy)returnPlayer(player);});
        for(String name:shown.subList(at*PER_PAGE,Math.min(shown.size(),(at+1)*PER_PAGE))) {
            Level level=getServer().getLevelByName(name);
            menu.add(level==null?t("ui.menu.worldOff",name):t("ui.menu.worldOn",name,level.getPlayers().size()),level==null?ICON_OFF:ICON_ON,()->showWorld(player,name));
        }
        if(at>0)menu.add(t("ui.common.prev"),ICON_PAGE,()->showMenu(player,at-1));
        if(at<pages-1)menu.add(t("ui.common.next"),ICON_PAGE,()->showMenu(player,at+1));
        if(all.size()>SEARCH_FROM||!query.isEmpty())menu.add(t("ui.menu.search"),ICON_SEARCH,()->showSearch(player));
        menu.add(t("ui.menu.help"),ICON_HELP,()->showHelp(player));
        menu.close().show();
    }
    private void showSearch(Player player) {
        FormWindowCustom form=new FormWindowCustom(t("ui.search.title"));
        form.addElement(new cn.nukkit.form.element.ElementHeader(t("ui.search.title")));
        form.addElement(new ElementInput(t("ui.search.query"),t("ui.search.hint"),queries.getOrDefault(player.getUniqueId(),"")));
        open(player,form,event-> {
            try {
                if(event.wasClosed()||!(event.getResponse() instanceof FormResponseCustom answer)){showMenu(player,0);return;}
                String query=answer.getInputResponse(1)==null?"":answer.getInputResponse(1).strip();
                if(query.isEmpty())queries.remove(player.getUniqueId());else queries.put(player.getUniqueId(),query.length()>64?query.substring(0,64):query);
                showMenu(player,0);
            } catch(Exception error) {player.sendMessage(t("err.show",error.getMessage()));}
        });
    }
    /** One window with a button per topic, no pages. */
    private void showHelp(Player player) {
        Buttons help=new Buttons(player,t("ui.help.title"),"");
        help.back(()->showMenu(player,0));
        for(String[] topic:new String[][]{{t("ui.help.worlds.title"),t("ui.help.worlds.body")},{t("ui.help.plan.title"),t("ui.help.plan.body")},{t("ui.help.other.title"),t("ui.help.other.body")}})
            help.add(topic[0],ICON_HELP,()->new Buttons(player,topic[0],topic[1]).back(()->showHelp(player)).show());
        help.close().show();
    }
    private void showWorld(Player player,String name) {
        Level level=getServer().getLevelByName(name);
        boolean autoload=getConfig().getStringList("autoload-worlds").contains(name);
        FormWindowCustom form=new FormWindowCustom(t("ui.world.title",name));
        form.addElement(new cn.nukkit.form.element.ElementHeader(name));
        form.addElement(new ElementLabel(level==null?t("ui.world.stateOff"):t("ui.world.stateOn",String.format("%,d",level.getPlayers().size()))));
        form.addElement(new cn.nukkit.form.element.ElementDivider());
        form.addElement(new ElementDropdown(t("ui.world.action"),List.of(t("ui.world.none"),t("ui.add.mount"),t("ui.add.unmount"),t("ui.menu.rename")),0));
        form.addElement(new ElementInput(t("ui.add.newName"),t("ui.world.newNameHint")));
        form.addElement(new ElementToggle(t("ui.add.link"),getConfig().getBoolean("link-on-rename",true)));
        form.addElement(new ElementToggle(t("ui.world.autoload"),autoload));
        open(player,form,event-> {
            try {
                if(event.wasClosed()||!(event.getResponse() instanceof FormResponseCustom answer)){showMenu(player,0);return;}
                var chosen=answer.getDropdownResponse(3);
                int action=chosen==null?0:chosen.getElementID();
                String target=answer.getInputResponse(4)==null?"":answer.getInputResponse(4).strip();
                boolean link=answer.getToggleResponse(5),auto=answer.getToggleResponse(6);
                if(auto!=autoload){setAutoload(name,auto);player.sendMessage(t(auto?"msg.autoloadOn":"msg.autoloadOff",name));}
                switch(action) {
                    case 1 -> {addOperation(player,new Operation("mount",name,"",false));player.sendMessage(t("msg.added"));}
                    case 2 -> {confirmUnmount(player,name);return;}
                    case 3 -> {addOperation(player,new Operation("rename",name,target,link));player.sendMessage(t("msg.added"));}
                    default -> {}
                }
                showMenu(player,0);
            } catch(Exception error) {player.sendMessage(t("err.show",error.getMessage()));}
        });
    }
    private void confirmUnmount(Player player,String name) {
        Level level=getServer().getLevelByName(name);
        FormWindowModal form=new FormWindowModal(t("ui.unmount.title"),t("ui.unmount.body",name,level==null?0:level.getPlayers().size()),t("ui.unmount.confirm"),t("ui.common.cancel"));
        open(player,form,event-> {
            try {
                if(!event.wasClosed()&&event.getResponse() instanceof FormResponseModal answer&&answer.getClickedButtonId()==0){
                    addOperation(player,new Operation("unmount",name,"",false));player.sendMessage(t("msg.added"));
                }
                showMenu(player,0);
            } catch(Exception error) {player.sendMessage(t("err.show",error.getMessage()));}
        });
    }
    private void showPlan(Player player) {
        List<Operation> items=planView(player);
        Buttons plan=new Buttons(player,t("ui.plan.title"),items.isEmpty()?t("ui.plan.empty"):describe(items));
        plan.back(()->showMenu(player,0));
        if(!items.isEmpty()) {
            plan.add(t("ui.plan.check"),ICON_PLAN,()->{mutable();validate(plan(player));checked.add(owner(player));showConfirm(player);});
            plan.add(t("ui.plan.clear"),ICON_OFF,()->{mutable();plan(player).clear();remember(player);showPlan(player);});
        }
        if(scheduled!=null&&!executing)plan.add(t("ui.plan.cancel"),ICON_CLOSE,()->{cancelSchedule(player);player.sendMessage(t("msg.cancelled"));showPlan(player);});
        plan.close().show();
    }
    private void showConfirm(Player player) {
        FormWindowCustom form=new FormWindowCustom(t("ui.confirm.title"));
        form.addElement(new cn.nukkit.form.element.ElementHeader(t("ui.confirm.title")));
        form.addElement(new ElementLabel(describe(planView(player))));
        form.addElement(new cn.nukkit.form.element.ElementDivider());
        form.addElement(new ElementSlider(t("ui.confirm.delay"),0,3600,5,30));
        form.addElement(new ElementToggle(t("ui.confirm.toggle"),false));
        open(player,form,event-> {
            try {
                if(event.wasClosed()||!(event.getResponse() instanceof FormResponseCustom answer))return;
                if(answer.getToggleResponse(4))execute(player,Math.max(0,Math.min(3600,Math.round(answer.getSliderResponse(3)))));
                else showPlan(player);
            } catch(Exception error) {player.sendMessage(t("err.show",error.getMessage()));}
        });
    }
    private void showResend(Player player) {
        FormWindowCustom form=new FormWindowCustom(t("ui.resend.title"));
        form.addElement(new cn.nukkit.form.element.ElementHeader(t("ui.resend.title")));
        form.addElement(new ElementLabel(t("ui.resend.body")));
        form.addElement(new ElementSlider(t("ui.resend.radius"),1,48,1,16));
        open(player,form,event-> {
            try {
                if(event.wasClosed()||!(event.getResponse() instanceof FormResponseCustom answer))return;
                startResend(player,Math.max(1,Math.min(48,Math.round(answer.getSliderResponse(2)))));
            } catch(Exception error) {player.sendMessage(t("err.show",error.getMessage()));}
        });
    }
    @EventHandler public void response(PlayerFormRespondedEvent event) {
        Player player=event.getPlayer();Pending pending=forms.get(player.getUniqueId());
        if(pending==null || pending.id()!=event.getFormID())return;
        forms.remove(player.getUniqueId());
        if(!player.hasPermission(ADMIN))return;
        try {pending.handler().accept(event);}
        catch(Exception error) {player.sendMessage(t("err.show",error.getMessage()));}
    }
    @EventHandler public void levelLoaded(cn.nukkit.event.level.LevelLoadEvent event) {
        getServer().getScheduler().scheduleDelayedTask(this,this::refreshIfChanged,1);
    }
    @EventHandler public void quit(PlayerQuitEvent event){stopResend(event.getPlayer().getUniqueId());forms.remove(event.getPlayer().getUniqueId());queries.remove(event.getPlayer().getUniqueId());Boolean was=frozen.remove(event.getPlayer().getUniqueId());if(was!=null)event.getPlayer().setImmobile(was);}
}
