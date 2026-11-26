package cn.jiujiu.gsit.carry;

import static org.mockito.Mockito.*;
import dev.geco.gsit.GSitMain;
import dev.geco.gsit.model.StopReason;
import dev.geco.gsit.service.PlayerSitService;
import dev.geco.gsit.service.ToggleService;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.*;
import org.mockito.ArgumentCaptor;

/** Menu routing and persistent preference behavior, including owner/lifecycle boundaries. */
final class MenuRegressionTest {
    static int checks;
    static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    static void field(Object object, String name, Object value) throws Exception {
        var f = CarryModule.class.getDeclaredField(name); f.setAccessible(true); f.set(object, value);
    }
    static final class Fixture {
        final MergeRegressionTest.CarryFixture f = new MergeRegressionTest.CarryFixture();
        final GSitMain parent = mock(GSitMain.class);
        final Server server = mock(Server.class);
        final PluginManager plugins = mock(PluginManager.class);
        final Plugin gui = mock(Plugin.class);
        final PluginCommand entry = mock(PluginCommand.class);
        final ToggleService toggles = mock(ToggleService.class);
        final PlayerSitService sit = mock(PlayerSitService.class);
        final AtomicBoolean accepting = new AtomicBoolean(true);
        Fixture() throws Exception {
            when(parent.getServer()).thenReturn(server); when(server.getPluginManager()).thenReturn(plugins);
            when(parent.getToggleService()).thenReturn(toggles); when(parent.getPlayerSitService()).thenReturn(sit);
            when(plugins.getPlugin("JiuJiuLib")).thenReturn(gui); when(gui.isEnabled()).thenReturn(true);
            when(server.getPluginCommand("jiujiulib:jjgui")).thenReturn(entry); when(entry.getPlugin()).thenReturn(gui);
            when(f.actor.performCommand(anyString())).thenReturn(true);
            when(toggles.canPlayerUsePlayerSit(any())).thenReturn(true);
            when(toggles.canPlayerBeCarried(any())).thenReturn(true);
            when(toggles.canPlayerUsePlayerSit(f.actor.getUniqueId())).thenAnswer(i -> accepting.get());
            when(toggles.trySetPlayerInteraction(eq(f.actor.getUniqueId()), anyBoolean())).thenAnswer(i -> {
                accepting.set(i.getArgument(1)); return true;
            });
            for (Player p : List.of(f.actor, f.target)) when(p.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
            field(f.plugin,"plugin",parent); field(f.plugin,"settings",f.settings);
            field(f.plugin,"scheduler",f.scheduler); field(f.plugin,"service",f.service); field(f.plugin,"active",true);
            field(f.plugin,"legacyPreference",Objects.requireNonNull(NamespacedKey.fromString("jiujiuqi:accept-interaction")));
            field(f.plugin,"migrated",Objects.requireNonNull(NamespacedKey.fromString("gsit:jiujiu-interaction-migrated")));
            doCallRealMethod().when(f.plugin).onCommand(any(),any(),anyString(),any());
            doCallRealMethod().when(f.plugin).accepts(any());
            doCallRealMethod().when(f.plugin).acceptsRide(any());
            doCallRealMethod().when(f.plugin).acceptsCarry(any());
            doCallRealMethod().when(f.plugin).message(any(),anyString());
            doCallRealMethod().when(f.plugin).onTabComplete(any(),any(),anyString(),any());
        }
        void command(String label, String... args) { f.plugin.onCommand(f.actor,mock(Command.class),label,args); }
        List<String> messages() {
            var capture=ArgumentCaptor.forClass(Component.class); verify(f.actor,atLeastOnce()).sendMessage(capture.capture());
            return capture.getAllValues().stream().map(PlainTextComponentSerializer.plainText()::serialize).toList();
        }
    }
    static int run() throws Exception {
        checks=0;
        for (String alias : List.of("sq","jiujiuqi","carry","baobao")) {
            var x=new Fixture(); x.accepting.set(false); x.command(alias);
            verify(x.f.actor).performCommand("jiujiulib:jjgui open interaction");
            verify(x.toggles,never()).trySetPlayerCanUsePlayerSit(any(),anyBoolean());
            check(!x.accepting.get(),"opening "+alias+" preserves opt-out");
        }
        var x=new Fixture(); x.command("sq","menu");
        verify(x.f.actor).performCommand("jiujiulib:jjgui open interaction");
        check(x.f.plugin.onTabComplete(x.f.actor,mock(Command.class),"sq",new String[]{"m"}).equals(List.of("menu")),"menu tab completion");

        x=new Fixture(); x.f.settings.yaml.set("menu.id","personal_interactions");
        field(x.f.plugin,"settings",new Settings(x.f.settings.yaml,w -> {})); x.command("sq");
        verify(x.f.actor).performCommand("jiujiulib:jjgui open personal_interactions"); check(true,"configured menu ID routed");

        for (String failure : List.of("missing-plugin","disabled-plugin","missing-command","wrong-owner","false-result","menu-disabled")) {
            x=new Fixture();
            switch(failure) {
                case "missing-plugin" -> when(x.plugins.getPlugin("JiuJiuLib")).thenReturn(null);
                case "disabled-plugin" -> when(x.gui.isEnabled()).thenReturn(false);
                case "missing-command" -> when(x.server.getPluginCommand("jiujiulib:jjgui")).thenReturn(null);
                case "wrong-owner" -> when(x.entry.getPlugin()).thenReturn(mock(Plugin.class));
                case "false-result" -> when(x.f.actor.performCommand(anyString())).thenReturn(false);
                case "menu-disabled" -> {x.f.settings.yaml.set("menu.enabled",false);field(x.f.plugin,"settings",new Settings(x.f.settings.yaml,w -> {}));}
            }
            x.command("sq");
            check(x.messages().equals(List.of("菜单暂不可用，可用 /sq on 或 /sq off。")),"short fallback: "+failure);
            if(!failure.equals("false-result")) verify(x.f.actor,never()).performCommand(anyString());
            verify(x.toggles,never()).trySetPlayerCanUsePlayerSit(any(),anyBoolean());
        }

        x=new Fixture(); x.f.settings.yaml.set("players.permissions.enabled",true);
        field(x.f.plugin,"settings",new Settings(x.f.settings.yaml,w -> {})); when(x.f.actor.hasPermission("jiujiuqi.use")).thenReturn(false);
        x.command("sq"); verify(x.f.actor,never()).performCommand(anyString());
        check(x.messages().equals(List.of("没有权限。")),"menu respects configured permission");

        x=new Fixture(); check(x.f.service.mountPlayers(x.f.actor,x.f.target),"carry active before menu-off command");
        x.command("sq","off");
        verify(x.sit).stopPlayerSit(x.f.actor,StopReason.PLUGIN);
        check(!x.accepting.get()&&x.f.vehicle.get()==null,"off persists preference and physically detaches immediately");
        x.f.runTasks();
        check(!x.f.service.held(x.f.actor)&&!x.f.service.tracked(x.f.target),"off releases reserved carry indexes after position sync");
        x.command("sq","status"); check(x.messages().getLast().contains("互动：关"),"status reads actual disabled state");
        x.command("sq","on"); check(x.accepting.get(),"on writes same GSit preference");
        x.command("sq","status"); check(x.messages().getLast().contains("互动：开"),"status reads actual enabled state");
        x.command("sq","help"); verify(x.f.actor,never()).performCommand(anyString()); check(true,"help remains direct command");

        x=new Fixture(); when(x.toggles.trySetPlayerInteraction(any(),anyBoolean())).thenReturn(false);
        x.command("sq","off"); verify(x.sit,never()).stopPlayerSit(any(),any());
        check(x.accepting.get()&&x.messages().equals(List.of("稍后再试。")),"storage not ready never reports false success");

        x=new Fixture(); when(x.f.scheduler.owns(x.f.actor)).thenReturn(false); x.command("sq");
        verify(x.f.actor,never()).performCommand(anyString()); check(x.f.tasks.size()==1,"foreign context defers to entity scheduler");
        when(x.f.scheduler.owns(x.f.actor)).thenReturn(true); x.f.runTasks();
        verify(x.f.actor).performCommand("jiujiulib:jjgui open interaction"); check(true,"deferred route runs in owner context");

        x=new Fixture(); when(x.f.scheduler.owns(x.f.actor)).thenReturn(false); x.command("sq"); field(x.f.plugin,"active",false);
        when(x.f.scheduler.owns(x.f.actor)).thenReturn(true); x.f.runTasks();
        verify(x.f.actor,never()).performCommand(anyString()); check(true,"disabled module cannot reopen menu from queued callback");

        for (Object id : List.of("../interaction","interaction\nreload",12,"")) {
            var config=new org.bukkit.configuration.file.YamlConfiguration(); config.set("menu.id",id); boolean rejected=false;
            try {new Settings(config,w -> {});} catch(IllegalArgumentException expected) {rejected=true;}
            check(rejected,"invalid menu ID refused: "+id);
        }
        return checks;
    }
}
