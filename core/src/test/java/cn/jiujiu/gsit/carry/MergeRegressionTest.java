package cn.jiujiu.gsit.carry;

import static org.mockito.Mockito.*;
import dev.geco.gsit.GSitMain;
import dev.geco.gsit.event.PlayerSitEventHandler;
import dev.geco.gsit.service.*;
import dev.geco.gsit.util.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.jar.JarFile;
import java.util.logging.Logger;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.PluginManager;
import org.bukkit.util.*;
import org.mockito.MockedStatic;

/** Behavioral tests exercise native GSit stack selection and the carry/teleport call chains.
 * Mocked ownership is not a claim of Folia runtime/client validation. */
public final class MergeRegressionTest {
    static int checks;
    static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    static final class Node {
        final Entity entity;
        final AtomicReference<Entity> vehicle=new AtomicReference<>();
        final List<Entity> children=new ArrayList<>();
        final AtomicReference<Location> location=new AtomicReference<>();
        final Set<String> tags=new HashSet<>();
        Node(Entity entity,World world){
            this.entity=entity; location.set(new Location(world,0,65,0));
            when(entity.getUniqueId()).thenReturn(UUID.randomUUID());
            when(entity.getVehicle()).thenAnswer(i->vehicle.get());
            when(entity.isInsideVehicle()).thenAnswer(i->vehicle.get()!=null);
            when(entity.getPassengers()).thenAnswer(i->List.copyOf(children));
            when(entity.getScoreboardTags()).thenReturn(tags);
            when(entity.getLocation()).thenAnswer(i->location.get().clone());
            when(entity.getWorld()).thenReturn(world);when(entity.isValid()).thenReturn(true);
            when(entity.getHeight()).thenReturn(1.8);
        }
    }
    static final class StackFixture implements AutoCloseable {
        final World world=mock(World.class);
        final GSitMain plugin=mock(GSitMain.class);
        final ConfigService config=mock(ConfigService.class);
        final Map<UUID,Node> nodes=new HashMap<>();
        final MockedStatic<Bukkit> bukkit=mockStatic(Bukkit.class);
        final MockedStatic<CarryModule> module=mockStatic(CarryModule.class);
        final PlayerSitService service;
        final PlayerSitEventHandler handler;
        final ToggleService toggles=mock(ToggleService.class);
        StackFixture(){
            when(plugin.isFoliaServer()).thenReturn(true);when(plugin.isEnabled()).thenReturn(true);
            bukkit.when(()->Bukkit.isOwnedByCurrentRegion(any(Entity.class))).thenReturn(true);
            bukkit.when(()->Bukkit.getPlayer(any(UUID.class))).thenAnswer(i->{Node n=nodes.get(i.getArgument(0));return n!=null&&n.entity instanceof Player p?p:null;});
            bukkit.when(()->Bukkit.getEntity(any(UUID.class))).thenAnswer(i->{Node n=nodes.get(i.getArgument(0));return n==null?null:n.entity;});
            bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
            bukkit.when(Bukkit::getOnlinePlayers).thenAnswer(i->nodes.values().stream().map(n->n.entity).filter(Player.class::isInstance).map(Player.class::cast).toList());
            module.when(()->CarryModule.owns(any())).thenReturn(true);
            module.when(()->CarryModule.allowGSit(any(),any())).thenReturn(true);
            VersionService version=mock(VersionService.class);
            when(version.isNewerOrVersion(1,20,2)).thenReturn(true);when(plugin.getVersionManager()).thenReturn(version);
            config.PS_ALLOW_SIT=true;config.PS_ALLOW_SIT_NPC=true;config.PS_EMPTY_HAND_ONLY=true;
            config.FEATUREFLAGS=new HashSet<>();when(plugin.getConfigService()).thenReturn(config);
            when(plugin.getPassengerUtil()).thenReturn(new PassengerUtil());
            PermissionService permission=mock(PermissionService.class);when(permission.hasPermission(any(),any(String[].class))).thenReturn(true);
            when(plugin.getPermissionService()).thenReturn(permission);
            EnvironmentUtil environment=mock(EnvironmentUtil.class);
            when(environment.isEntityInAllowedWorld(any())).thenReturn(true);when(environment.canUseInLocation(any(),any(),anyString())).thenReturn(true);
            when(plugin.getEnvironmentUtil()).thenReturn(environment);when(plugin.getCrawlService()).thenReturn(mock(CrawlService.class));
            when(toggles.canPlayerUsePlayerSit(any())).thenReturn(true);when(plugin.getToggleService()).thenReturn(toggles);
            EntityUtil entities=mock(EntityUtil.class);when(entities.isPlayerSitLocationValid(any())).thenReturn(true);
            when(entities.createPlayerSitEntities(any(),any())).thenAnswer(i->{
                Player rider=i.getArgument(0),bottom=i.getArgument(1);Node seat=node(mock(Entity.class));seat.tags.add(PlayerSitService.PLAYERSIT_ENTITY_TAG);
                mount(seat.entity,bottom);mount(rider,seat.entity);return List.of(seat.entity.getUniqueId());
            });
            when(plugin.getEntityUtil()).thenReturn(entities);
            service=new PlayerSitService(plugin);when(plugin.getPlayerSitService()).thenReturn(service);
            handler=new PlayerSitEventHandler(plugin);
        }
        Node node(Entity entity){
            Node n=new Node(entity,world);nodes.put(entity.getUniqueId(),n);
            when(entity.removePassenger(any())).thenAnswer(i->{Entity child=i.getArgument(0);Node childNode=nodes.get(child.getUniqueId());
                if(childNode.vehicle.get()!=entity)return false;n.children.remove(child);childNode.vehicle.set(null);return true;});
            when(entity.leaveVehicle()).thenAnswer(i->{Entity parent=n.vehicle.getAndSet(null);if(parent!=null)nodes.get(parent.getUniqueId()).children.remove(entity);return parent!=null;});
            doAnswer(i->{for(Entity child:List.copyOf(n.children))nodes.get(child.getUniqueId()).vehicle.set(null);n.children.clear();entity.leaveVehicle();return null;}).when(entity).remove();
            return n;
        }
        Player player(){Player p=mock(Player.class);node(p);when(p.getInventory()).thenReturn(mock(PlayerInventory.class));
            ItemStack air=mock(ItemStack.class);when(air.getType()).thenReturn(Material.AIR);when(p.getInventory().getItemInMainHand()).thenReturn(air);
            when(p.getGameMode()).thenReturn(GameMode.SURVIVAL);return p;}
        void mount(Entity rider,Entity vehicle){nodes.get(rider.getUniqueId()).vehicle.set(vehicle);nodes.get(vehicle.getUniqueId()).children.add(rider);}
        boolean click(Player rider,Player target){PlayerInteractAtEntityEvent e=new PlayerInteractAtEntityEvent(rider,target,new org.bukkit.util.Vector(),EquipmentSlot.HAND);handler.playerInteractAtEntityEventHighest(e);return e.isCancelled();}
        void noLinks(Player...players){for(Player p:players)check(!p.isInsideVehicle()&&p.getPassengers().isEmpty()&&!service.isPlayerInPlayerSitStack(p),"all links removed for "+p);}
        public void close(){module.close();bukkit.close();}
    }
    static void stacks(){
        for(int teleporting=0;teleporting<3;teleporting++)try(StackFixture f=new StackFixture()){
            Player bottom=f.player(),middle=f.player(),top=f.player();
            check(f.click(middle,bottom),"second player mounted using original GSit handler");
            check(f.click(top,bottom),"third player stacks on highest GSit passenger");
            check(f.service.isPlayerInPlayerSitStack(top),"third player is registered");
            check(!f.click(bottom,top),"cycle is rejected");
            Player[] players={bottom,middle,top};check(f.service.splitStackForTeleport(players[teleporting]),"split bottom/middle/top before teleport");
            f.noLinks(players);check(f.service.getPlayerSitCount()==2,"stats preserved");
        }
        try(StackFixture f=new StackFixture()){
            Player a=f.player(),b=f.player(),c=f.player();f.config.PS_MAX_STACK=2;
            check(f.click(b,a),"two-player cap allows first rider");check(!f.click(c,a),"stack cap rejects excess rider");
            f.service.splitStackForTeleport(a);
            when(f.toggles.canPlayerUsePlayerSit(c.getUniqueId())).thenReturn(false);
            check(f.click(c,a),"own incoming opt-out does not prevent riding others");
            f.service.splitStackForTeleport(a);
            when(f.toggles.canPlayerUsePlayerSit(a.getUniqueId())).thenReturn(false);
            check(!f.click(b,a),"target opt-out blocks ordinary riding");
        }
    }
    static final class CarryFixture {
        final CarryModule plugin=mock(CarryModule.class);
        final TaskScheduler scheduler=mock(TaskScheduler.class);
        final Player actor=mock(Player.class),target=mock(Player.class);
        final Mob mob=mock(Mob.class);
        final World world=mock(World.class);
        final AtomicReference<Entity> vehicle=new AtomicReference<>();
        final AtomicLong clock=new AtomicLong(1_000_000_000L);
        final List<Runnable> tasks=new ArrayList<>();
        final Settings settings;
        final CarryService service;
        CarryFixture()throws Exception {
            YamlConfiguration yaml=new YamlConfiguration();yaml.load("resources/jiujiu-carry.yml");
            yaml.set("controls.hold-guard-ms",0);settings=new Settings(yaml,s->{});
            when(plugin.settings()).thenReturn(settings);when(plugin.getLogger()).thenReturn(Logger.getLogger("merge-test"));
            when(plugin.accepts(any())).thenReturn(true);when(plugin.acceptsCarry(any())).thenReturn(true);when(scheduler.owns(any())).thenReturn(true);
            when(scheduler.repeat(any(),any(),any(),anyLong())).thenReturn(mock(TaskScheduler.Handle.class));
            when(scheduler.later(any(),any(),any())).thenAnswer(i->{tasks.add(i.getArgument(1));return true;});
            when(scheduler.syncPosition(any())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(true));
            for(LivingEntity e:List.of(actor,target,mob)){
                when(e.getUniqueId()).thenReturn(UUID.randomUUID());when(e.isValid()).thenReturn(true);when(e.getWorld()).thenReturn(world);
                when(e.getLocation()).thenReturn(new Location(world,0,65,0));when(e.getHeight()).thenReturn(1.4);when(e.getWidth()).thenReturn(.9);
                when(e.getBoundingBox()).thenReturn(new BoundingBox(-.5,65,-.5,.5,66.4,.5));
                when(e.hasLineOfSight(any(Entity.class))).thenReturn(true);
            }
            for(Player p:List.of(actor,target)){
                when(p.isOnline()).thenReturn(true);when(p.getGameMode()).thenReturn(GameMode.SURVIVAL);when(p.hasPermission(anyString())).thenReturn(true);
                when(p.getInventory()).thenReturn(mock(PlayerInventory.class));ItemStack air=mock(ItemStack.class);Material empty=mock(Material.class);when(empty.isAir()).thenReturn(true);when(air.getType()).thenReturn(empty);
                when(p.getInventory().getItemInMainHand()).thenReturn(air);when(p.getInventory().getItemInOffHand()).thenReturn(air);
                when(p.getEyeLocation()).thenReturn(new Location(world,0,66,0));
            }
            when(actor.isSneaking()).thenReturn(true);when(world.getName()).thenReturn("world");when(mob.getType()).thenReturn(EntityType.COW);
            for(LivingEntity e:List.of(target,mob)){
                when(e.getVehicle()).thenAnswer(i->vehicle.get());when(e.isInsideVehicle()).thenAnswer(i->vehicle.get()!=null);
                when(e.leaveVehicle()).thenAnswer(i->{vehicle.set(null);return true;});
                when(actor.addPassenger(e)).thenAnswer(i->{vehicle.set(actor);return true;});
                when(actor.removePassenger(e)).thenAnswer(i->{vehicle.set(null);return true;});
            }
            service=new CarryService(plugin,scheduler,clock::get);
        }
        void runTasks(){while(!tasks.isEmpty()){Runnable task=tasks.removeFirst();task.run();}}
    }
    static void carry()throws Exception {
        CarryFixture f=new CarryFixture();when(f.actor.isSneaking()).thenReturn(false);
        check(!f.service.mountPlayers(f.actor,f.target),"carry service cannot create ordinary player riding");
        when(f.actor.isSneaking()).thenReturn(true);check(f.service.mountPlayers(f.actor,f.target),"sneaking creates explicit carry");
        check(f.service.beforeTeleport(f.actor),"carrier pre-teleport detach succeeds");
        check(f.vehicle.get()==null&&!f.service.held(f.actor)&&!f.service.tracked(f.target),"external teleport clears both carry indexes");
        f.runTasks();verify(f.scheduler,never()).syncPosition(any());verify(f.target,never()).setVelocity(any());
        f=new CarryFixture();check(f.service.mountPlayers(f.actor,f.target),"second carry establishes");
        check(f.service.throwHeld(f.actor),"throw starts release");check(f.service.beforeTeleport(f.target),"external teleport invalidates queued throw");
        f.runTasks();verify(f.target,never()).setVelocity(any());
        f=new CarryFixture();check(f.service.mountPlayers(f.actor,f.target),"third carry establishes");
        f.service.throwHeld(f.actor);f.runTasks();verify(f.target).setVelocity(any());check(!f.service.held(f.actor),"successful throw cleans session");
        f=new CarryFixture();when(f.plugin.acceptsCarry(f.target)).thenReturn(false);check(!f.service.mountPlayers(f.actor,f.target),"pickup opt-out blocks carry");
        f=new CarryFixture();when(f.scheduler.owns(f.target)).thenReturn(false);check(!f.service.mountPlayers(f.actor,f.target),"foreign region blocks carry");
        f=new CarryFixture();check(f.service.pickup(f.actor,f.mob),"allowed small mob can be carried");
        check(f.service.beforeTeleport(f.actor),"carrier teleport also detaches original mob");check(f.vehicle.get()==null,"mob remains behind");
        f=new CarryFixture();when(f.mob.getType()).thenReturn(EntityType.WARDEN);check(!f.service.pickup(f.actor,f.mob),"blacklisted mob is rejected");
        InteractionGate input=new InteractionGate();UUID id=UUID.randomUUID();input.mark(id,0,10);
        check(input.recent(id,900_000_000,14,350),"continuous-right-click repeat tick is rejected");
        input.protectMounts(id,0,1000);check(input.mountProtected(id,999_000_000),"short release guard works");
        check(!input.mountProtected(id,1_001_000_000),"release guard expires without Shift rearm");
        YamlConfiguration invalid=new YamlConfiguration();invalid.set("pickup.allowed-types",List.of("NOT_A_MOB"));
        boolean refused=false;try{new Settings(invalid,s->{});}catch(IllegalArgumentException e){refused=true;}
        check(refused,"invalid entity list fails closed");
    }
    static void commandAndMenu()throws Exception {
        for(String entry:List.of("command","menu")) {
            CarryFixture f=new CarryFixture();
            GSitMain parent=mock(GSitMain.class);PlayerSitService nativeSit=mock(PlayerSitService.class);
            when(parent.getPlayerSitService()).thenReturn(nativeSit);when(nativeSit.splitStackForTeleport(any())).thenReturn(true);
            for(var field:Map.<String,Object>of("plugin",parent,"settings",f.settings,"scheduler",f.scheduler,"service",f.service,
                    "inputs",new InteractionGate(),"positionSync",java.util.concurrent.ConcurrentHashMap.newKeySet(),
                    "teleportDetach",java.util.concurrent.ConcurrentHashMap.newKeySet(),"active",true).entrySet()) {
                var slot=CarryModule.class.getDeclaredField(field.getKey());slot.setAccessible(true);slot.set(f.plugin,field.getValue());
            }
            doCallRealMethod().when(f.plugin).beforeTeleport(any(),any());
            doCallRealMethod().when(f.plugin).beforeCommand(any());
            doCallRealMethod().when(f.plugin).beforeInventoryClick(any());
            check(f.service.mountPlayers(f.actor,f.target),"entry test carry created");
            f.plugin.beforeCommand(new PlayerCommandPreprocessEvent(f.actor,"/carry status",Set.of()));
            check(f.service.held(f.actor),"carry controls do not prematurely detach");
            if(entry.equals("command")) {
                PlayerCommandPreprocessEvent event=new PlayerCommandPreprocessEvent(f.target,"/is home",Set.of());
                f.plugin.beforeCommand(event);check(!event.isCancelled(),"travel command is not cancelled or replayed");
            } else {
                org.bukkit.event.inventory.InventoryClickEvent event=mock(org.bukkit.event.inventory.InventoryClickEvent.class);
                when(event.getWhoClicked()).thenReturn(f.target);f.plugin.beforeInventoryClick(event);
                verify(event,never()).setCancelled(anyBoolean());
            }
            check(!f.service.held(f.actor)&&f.vehicle.get()==null,"command/menu detaches before passenger rejection");
            f.runTasks();verify(f.scheduler,never()).syncPosition(any());verify(f.target,never()).setVelocity(any());
        }
    }
    static void jar()throws Exception {
        try(JarFile jar=new JarFile(System.getProperty("gsit.test.jar"))) {
            check(jar.getEntry("cn/jiujiu/carry/JiuJiuCarry.class")==null,"old JavaPlugin not embedded");
            check(jar.getEntry("cn/jiujiu/gsit/carry/CarryModule.class")!=null,"carry module embedded");
            check(jar.getEntry("jiujiu-carry.yml")!=null,"defaults embedded");
        }
        for(String name:List.of("dev.geco.gsit.GSitMain","dev.geco.gsit.event.PlayerSitEventHandler","dev.geco.gsit.event.SitEventHandler","dev.geco.gsit.service.TaskService")) Class.forName(name,false,MergeRegressionTest.class.getClassLoader());
    }
    public static void main(String[]args)throws Exception {stacks();carry();commandAndMenu();jar();checks+=StrictRegressionTest.run();checks+=MenuRegressionTest.run();checks+=CarryInputRegressionTest.run();checks+=CarryProtocolLayoutTest.run();checks+=MobCarryRegressionTest.run();checks+=cn.jiujiu.gsit.ActionMenuDefaultsTest.run();checks+=dev.geco.gsit.service.UpdateRegressionTest.run();checks+=dev.geco.gsit.service.UpstreamAlignmentTest.run();checks+=dev.geco.gsit.service.ReceivePreferenceTest.run();System.out.println("PASS "+checks+" behavioral checks");}
}
