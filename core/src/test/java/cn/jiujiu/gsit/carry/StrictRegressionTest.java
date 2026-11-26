package cn.jiujiu.gsit.carry;

import static org.mockito.Mockito.*;
import dev.geco.gsit.GSitMain;
import dev.geco.gsit.cmd.GSitReloadCommand;
import dev.geco.gsit.service.MessageService;
import dev.geco.gsit.service.PermissionService;
import dev.geco.gsit.service.ToggleStrictTest;
import java.util.*;
import java.util.function.LongSupplier;
import org.bukkit.command.CommandSender;
import org.bukkit.command.Command;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;

/** Regression cases for failures found during the strict review. */
final class StrictRegressionTest {
    static int checks;
    static void check(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }
    static void badConfig(String key, Object value) {
        YamlConfiguration config = new YamlConfiguration();
        config.set(key, value);
        boolean rejected = false;
        try { new Settings(config, warning -> {}); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "invalid config rejected: " + key);
    }
    static void gesture() throws Exception {
        var f = new MergeRegressionTest.CarryFixture();
        f.settings.yaml.set("pickup.require-sneaking", false);
        Settings config = new Settings(f.settings.yaml, warning -> {});
        when(f.plugin.settings()).thenReturn(config);
        GSitMain parent = mock(GSitMain.class);
        Map<String, Object> fields = new HashMap<>();
        fields.put("plugin", parent); fields.put("settings", config);
        fields.put("scheduler", f.scheduler); fields.put("service", f.service);
        fields.put("inputs", new InteractionGate()); fields.put("active", true);
        fields.put("nativeTeleportHook", true); fields.put("clock", (LongSupplier) f.clock::get);
        for (var field : fields.entrySet()) {
            var slot = CarryModule.class.getDeclaredField(field.getKey());
            slot.setAccessible(true); slot.set(f.plugin, field.getValue());
        }
        doCallRealMethod().when(f.plugin).interactEntity(any());
        when(f.actor.isSneaking()).thenReturn(false);
        f.plugin.interactEntity(new PlayerInteractEntityEvent(f.actor, f.target, EquipmentSlot.HAND));
        check(!f.service.held(f.actor), "ordinary player click still belongs to GSit");
        f.plugin.interactEntity(new PlayerInteractEntityEvent(f.actor, f.mob, EquipmentSlot.HAND));
        check(f.service.tracked(f.mob), "non-sneaking mob pickup switch works through real listener");
        check(f.service.beforeTeleport(f.actor), "gesture test cleanup");
    }
    static void abortedMount() throws Exception {
        var f = new MergeRegressionTest.CarryFixture();
        doAnswer(invocation -> {
            f.service.beforeTeleport(f.actor); // synchronous re-entry while native mount event runs
            f.vehicle.set(f.actor);
            return true;
        }).when(f.actor).addPassenger(f.target);
        check(!f.service.mountPlayers(f.actor, f.target), "invalidated mount is not committed");
        check(f.vehicle.get() == null, "invalidated native mount is physically rolled back");
        check(!f.service.held(f.actor) && !f.service.tracked(f.target), "aborted mount leaves no indexes");
    }
    static void foliaReload() {
        GSitMain parent = mock(GSitMain.class);
        CommandSender sender = mock(CommandSender.class);
        when(parent.isFoliaServer()).thenReturn(true);
        doCallRealMethod().when(parent).reload(sender);
        parent.reload(sender); // unsafe old path would call Bukkit/unload and fail here
        verify(sender).sendMessage(anyString());
        verify(parent, never()).getDataService();
        check(true, "Folia refuses unsafe native hot reload before touching old services");
    }
    static void reloadCommand() {
        GSitMain parent = mock(GSitMain.class);
        ConsoleCommandSender sender = mock(ConsoleCommandSender.class);
        PermissionService permissions = mock(PermissionService.class);
        MessageService messages = mock(MessageService.class);
        when(parent.isFoliaServer()).thenReturn(true);
        when(parent.getPermissionService()).thenReturn(permissions);
        when(parent.getMessageService()).thenReturn(messages);
        when(permissions.hasPermission(sender, "Reload")).thenReturn(true);
        GSitReloadCommand command = new GSitReloadCommand(parent);
        check(command.onCommand(sender, mock(Command.class), "gsitreload", new String[0]), "refused reload command handled");
        verify(sender).sendMessage("§e请完整重启；抱起配置用 /carry reload。");
        verify(parent, never()).reload(any());
        verifyNoInteractions(messages);
        check(true, "refused reload emits no success message and never enters reload");

        clearInvocations(sender, parent, messages);
        when(permissions.hasPermission(sender, "Reload")).thenReturn(false);
        command.onCommand(sender, mock(Command.class), "gsitrl", new String[0]);
        verify(sender, never()).sendMessage(anyString());
        verify(messages).sendMessage(sender, "Messages.command-permission-error");
        check(true, "original reload permission refusal preserved");

        clearInvocations(sender, parent, messages);
        when(permissions.hasPermission(sender, "Reload")).thenReturn(true);
        when(parent.isFoliaServer()).thenReturn(false);
        command.onCommand(sender, mock(Command.class), "gsitreload", new String[0]);
        verify(parent).reload(sender);
        verify(messages).sendMessage(sender, "Plugin.plugin-reload");
        check(true, "non-Folia reload command preserves original behavior");
    }
    static void abortedMob() throws Exception {
        var f=new MergeRegressionTest.CarryFixture();
        var ids=new java.util.concurrent.atomic.AtomicReference<Set<UUID>>();
        doAnswer(invocation->{f.service.beforeTeleport(f.actor);f.vehicle.set(f.actor);return true;}).when(f.actor).addPassenger(f.mob);
        try(var module=mockStatic(CarryModule.class)) {
            module.when(()->CarryModule.beginSafetyDetach(any())).thenAnswer(invocation->{ids.set(invocation.getArgument(0));return null;});
            check(!f.service.pickup(f.actor,f.mob),"invalidated mob mount aborts");
            check(f.vehicle.get()==null,"aborted mob physically detaches");
            check(Set.of(f.actor.getUniqueId()).equals(ids.get()),"safety guard stores player UUIDs only");
        }
    }
    interface Checked { void run() throws Exception; }
    static void group(String name, Checked task, List<String> failures) {
        try { task.run(); }
        catch (Throwable failure) { failures.add(name + ": " + failure); }
    }
    static int run() throws Exception {
        checks = 0;
        List<String> failures = new ArrayList<>();
        group("boolean", () -> badConfig("players.carry", "false"), failures);
        group("section", () -> badConfig("players", false), failures);
        group("world-list", () -> badConfig("pickup.disabled-worlds", "world"), failures);
        group("null-type", () -> badConfig("pickup.allowed-types", Arrays.asList((Object) null)), failures);
        group("gesture", StrictRegressionTest::gesture, failures);
        group("mount-rollback", StrictRegressionTest::abortedMount, failures);
        group("mob-rollback", StrictRegressionTest::abortedMob, failures);
        group("native-reload", StrictRegressionTest::foliaReload, failures);
        group("reload-command", StrictRegressionTest::reloadCommand, failures);
        group("database", () -> checks += ToggleStrictTest.run(), failures);
        for (String failure : failures) System.out.println("STRICT FAIL " + failure);
        if (!failures.isEmpty()) throw new AssertionError("Strict regression groups failed: " + failures.size());
        return checks;
    }
}
