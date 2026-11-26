package cn.jiujiu.gsit.carry;

import static org.mockito.Mockito.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.event.entity.EntityDismountEvent;

final class MobCarryRegressionTest {
    static int checks;
    static void check(boolean b, String label) { checks++; if (!b) throw new AssertionError(label); }
    static void wire(MergeRegressionTest.CarryFixture f) throws Exception {
        MenuRegressionTest.field(f.plugin, "settings", f.settings);
        MenuRegressionTest.field(f.plugin, "service", f.service);
        MenuRegressionTest.field(f.plugin, "scheduler", f.scheduler);
        MenuRegressionTest.field(f.plugin, "active", true);
        MenuRegressionTest.field(f.plugin, "teleportDetach", java.util.concurrent.ConcurrentHashMap.newKeySet());
        doCallRealMethod().when(f.plugin).retainCarriedMob(any());
    }
    static int run() throws Exception {
        var f = new MergeRegressionTest.CarryFixture(); wire(f);
        when(f.mob.getType()).thenReturn(EntityType.VILLAGER);
        check(f.service.pickup(f.actor, f.mob), "villager pickup establishes native relation");
        var event = new EntityDismountEvent(f.mob, f.actor);
        f.plugin.retainCarriedMob(event);
        check(event.isCancelled(), "unsolicited live mob dismount blocked while held");
        var s = f.service.carriers.get(f.actor.getUniqueId());
        s.exiting = true; event = new EntityDismountEvent(f.mob, f.actor);
        f.plugin.retainCarriedMob(event); check(!event.isCancelled(), "intentional release not blocked");
        s.exiting = false;
        var forced = new EntityDismountEvent(f.mob, f.actor, false);
        f.plugin.retainCarriedMob(forced); check(!forced.isCancelled(), "non-cancellable core detach remains authoritative");
        f.settings.yaml.set("safety.prevent-mob-self-dismount", false);
        event = new EntityDismountEvent(f.mob, f.actor);
        f.plugin.retainCarriedMob(event); check(!event.isCancelled(), "diagnostic-only option honored");
        f.settings.yaml.set("safety.prevent-mob-self-dismount", true);
        when(f.mob.isDead()).thenReturn(true);
        event = new EntityDismountEvent(f.mob, f.actor);
        f.plugin.retainCarriedMob(event); check(!event.isCancelled(), "death cleanup unblocked");
        when(f.mob.isDead()).thenReturn(false);
        when(f.scheduler.owns(f.actor)).thenReturn(false);
        event = new EntityDismountEvent(f.mob, f.actor);
        f.plugin.retainCarriedMob(event); check(!event.isCancelled(), "foreign owner never blocked or dereferenced");
        when(f.scheduler.owns(f.actor)).thenReturn(true);
        doAnswer(i -> {
            var e = new EntityDismountEvent(f.mob, f.actor); f.plugin.retainCarriedMob(e);
            check(!e.isCancelled(), "throw detach passes same protection handler");
            f.vehicle.set(null); return true;
        }).when(f.actor).removePassenger(f.mob);
        check(f.service.throwHeld(f.actor), "throw accepted"); f.runTasks();
        verify(f.mob).setVelocity(any());
        check(!f.service.held(f.actor), "throw clears indexes");

        var g = new MergeRegressionTest.CarryFixture(); wire(g);
        check(g.service.pickup(g.actor, g.mob), "cow allowed without blacklist entry");
        doAnswer(i -> {
            var e = new EntityDismountEvent(g.mob, g.actor); g.plugin.retainCarriedMob(e);
            check(!e.isCancelled(), "double shift/drop detach passes protection handler");
            g.vehicle.set(null); return true;
        }).when(g.mob).leaveVehicle();
        g.service.drop(g.actor, "put-down");
        check(!g.service.held(g.actor) && g.vehicle.get() == null, "drop clears relation");
        var h = new MergeRegressionTest.CarryFixture();
        h.settings.yaml.set("pickup.denied-types", java.util.List.of("COW"));
        when(h.plugin.settings()).thenReturn(new Settings(h.settings.yaml, text -> {}));
        check(!h.service.pickup(h.actor, h.mob), "configured cow blacklist still enforced");
        verify(h.plugin).message(h.actor, "type-denied");
        verify(h.plugin, never()).message(h.actor, "too-large");
        h.settings.yaml.set("pickup.denied-types", java.util.List.of());
        when(h.plugin.settings()).thenReturn(new Settings(h.settings.yaml, text -> {}));
        when(h.mob.getWidth()).thenReturn(3.0);
        check(!h.service.pickup(h.actor, h.mob), "oversize still rejected independently");
        verify(h.plugin).message(h.actor, "too-large");
        return checks;
    }
}
