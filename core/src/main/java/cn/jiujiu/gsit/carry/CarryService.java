package cn.jiujiu.gsit.carry;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.bukkit.GameMode;
import org.bukkit.entity.*;
import org.bukkit.util.Vector;

final class CarryService {
    final ConcurrentHashMap<UUID, Session> carriers = new ConcurrentHashMap<>();
    final ConcurrentHashMap<UUID, Session> carried = new ConcurrentHashMap<>();
    // Only online players with a successful pickup get an entry; removed on quit/disable.
    final ConcurrentHashMap<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final CarryModule plugin;
    private final TaskScheduler scheduler;
    private final Object reservationLock = new Object();
    private volatile boolean accepting = true;
    private final LongSupplier clock;

    static final class Session {
        final Player player;
        final LivingEntity mob;
        final UUID playerId, mobId;
        final long started;
        final boolean players;
        final AtomicBoolean closed = new AtomicBoolean();
        final AtomicBoolean cleanupQueued = new AtomicBoolean();
        final AtomicBoolean unexpectedDismountLogged = new AtomicBoolean();
        final AtomicReference<TaskScheduler.Handle> task = new AtomicReference<>();
        final AtomicReference<CarryInput> input = new AtomicReference<>();
        long lastSneak;
        boolean mounting;
        volatile boolean exiting;
        Session(Player player, LivingEntity mob, long started) {
            this.player = player; this.mob = mob;
            this.started = started; players = mob instanceof Player;
            playerId = player.getUniqueId(); mobId = mob.getUniqueId();
        }
        void install(TaskScheduler.Handle handle) {
            task.set(handle);
            if (closed.get() && handle != null) handle.cancel();
        }
        void installInput(CarryInput value) {
            input.set(value);
            // Disable/retirement may have closed the session while the packet target was being created.
            if (closed.get()) {
                CarryInput stale = input.getAndSet(null);
                if (stale != null) stale.remove();
            }
        }
    }

    CarryService(CarryModule plugin, TaskScheduler scheduler) {
        this(plugin, scheduler, System::nanoTime);
    }
    CarryService(CarryModule plugin, TaskScheduler scheduler, LongSupplier clock) {
        this.plugin = plugin; this.scheduler = scheduler;
        this.clock = clock;
    }

    boolean held(Player player) { return carriers.containsKey(player.getUniqueId()); }
    boolean tracked(Entity entity) { return carried.containsKey(entity.getUniqueId()); }

    boolean syncingPassenger(UUID id) {
        Session s = carried.get(id);
        return s != null && s.players && s.exiting && !s.closed.get();
    }

    String mountBlockReason(Player rider, Player mount) {
        if (!scheduler.owns(rider) || !scheduler.owns(mount)) return "region-not-owned";
        UUID riderId = rider.getUniqueId(), mountId = mount.getUniqueId();
        if (plugin.mountProtected(riderId) || plugin.mountProtected(mountId)) return "release-protection";
        Session s = carried.get(riderId);
        if (s != null && s.players && s.mounting && !s.exiting && !s.closed.get()
                && s.mob == rider && s.player == mount) return mountStillAllowed(s) ? null : "mount-validation";
        if (carriers.containsKey(riderId) || carried.containsKey(riderId)
                || carriers.containsKey(mountId) || carried.containsKey(mountId)) return "participant-busy";
        return null;
    }

    boolean pickup(Player p, Entity target) {
        Settings c = plugin.settings();
        if (!accepting || !c.enabled || held(p) || !c.yaml.getBoolean("pickup.enabled", true) || !(target instanceof Mob mob)) return false;
        if (!scheduler.owns(p) || !scheduler.owns(mob)) return false;
        if (!c.permission(p, c.usePermission)) { plugin.message(p, "no-permission"); return false; }
        if (!p.getInventory().getItemInMainHand().getType().isAir()
                || !p.getInventory().getItemInOffHand().getType().isAir()) return false;
        if (c.sneakPickup && !p.isSneaking()) return false;
        if (!p.isValid() || p.isDead() || p.isInsideVehicle() || !p.getPassengers().isEmpty()
                || p.isGliding() || p.isSleeping() || p.isRiptiding() || p.getGameMode() == GameMode.SPECTATOR || mob.isDead() || !mob.isValid()
                || mob.isInsideVehicle() || !mob.getPassengers().isEmpty() || mob.isLeashed()) return false;
        if (c.worlds.contains(p.getWorld().getName())) { plugin.message(p, "disabled-world"); return false; }
        if (p.getWorld() != mob.getWorld()
                || p.getEyeLocation().toVector().distanceSquared(mob.getBoundingBox().getCenter()) > c.distance * c.distance
                || !p.hasLineOfSight(mob)) return false;
        String type = mob.getType().name();
        if (c.denied.contains(type) || (!c.allowed.isEmpty() && !c.allowed.contains(type))) {
            plugin.message(p, "type-denied"); return false;
        }
        if (mob.getWidth() > c.maxWidth || mob.getHeight() > c.maxHeight) {
            plugin.message(p, "too-large"); return false;
        }
        if (c.yaml.getBoolean("permissions.per-entity", false)
                && !p.hasPermission("jiujiucarry.entity." + type.toLowerCase(java.util.Locale.ROOT))) {
            plugin.message(p, "no-permission"); return false;
        }
        if (c.ownPets && mob instanceof Tameable pet && pet.isTamed()
                && !p.getUniqueId().equals(pet.getOwnerUniqueId())) {
            plugin.message(p, "not-your-pet"); return false;
        }
        long now = clock.getAsLong();
        Long previous = cooldowns.get(p.getUniqueId());
        if (previous != null && now - previous < c.cooldownMs * 1_000_000L) {
            plugin.message(p, "cooldown"); return false;
        }
        return establish(p, mob, now, p);
    }

    private boolean available(Player p) {
        return p.isOnline() && p.isValid() && !p.isDead() && p.getGameMode() != GameMode.SPECTATOR
                && !p.isSleeping() && !p.isFlying() && !p.isGliding() && !p.isRiptiding();
    }

    boolean mountPlayers(Player actor, Player target) {
        if (!accepting || !scheduler.owns(actor) || !scheduler.owns(target) || actor == target) return false;
        // Ordinary riding and stacks belong exclusively to GSit.
        if (!actor.isSneaking()) return false;
        Settings c = plugin.settings();
        if (plugin.mountProtected(actor.getUniqueId()) || plugin.mountProtected(target.getUniqueId())) {
            plugin.message(actor, "mount-protected"); return false;
        }
        if (!c.enabled || !c.playerEnabled("carry")) return false;
        if (!c.playerPermission(actor, "carry")) { plugin.message(actor, "no-permission"); return false; }
        if (!available(actor) || !available(target) || actor.getWorld() != target.getWorld()
                || c.worlds.contains(actor.getWorld().getName())) return false;
        if (!c.playerHandsAllowed(actor, true)) return false;
        if (!plugin.acceptsCarry(target) || !c.playerPermission(target, "receive")) {
            plugin.message(actor, "player-refused"); return false;
        }
        if (actor.isInsideVehicle() || target.isInsideVehicle() || !actor.getPassengers().isEmpty()
                || !target.getPassengers().isEmpty()) { plugin.message(actor, "player-busy"); return false; }
        double distance = c.playerRange;
        if (actor.getLocation().distanceSquared(target.getLocation()) > distance * distance || !actor.hasLineOfSight(target)) return false;
        long now = clock.getAsLong();
        Long previous = cooldowns.get(actor.getUniqueId());
        long cooldown = c.playerCooldownMs * 1_000_000L;
        if (previous != null && now - previous < cooldown) { plugin.message(actor, "cooldown"); return false; }
        boolean success = establish(actor, target, now, actor);
        if (success) {
            plugin.message(actor, "player-carrying");
            plugin.message(target, "player-carried");
        }
        return success;
    }

    private boolean establish(Player p, LivingEntity mob, long now, Player actor) {
        Settings c = plugin.settings();
        Session s = new Session(p, mob, now);
        // Reserve both indexes atomically, without touching live entities under this short lock.
        boolean full;
        synchronized (reservationLock) {
            if (!accepting) return false;
            full = carriers.size() >= c.capacity;
            if (!full) {
                if (carriers.containsKey(s.playerId) || carried.containsKey(s.playerId)
                        || carried.containsKey(s.mobId) || carriers.containsKey(s.mobId)) return false;
                carriers.put(s.playerId, s); carried.put(s.mobId, s);
            }
        }
        if (full) { plugin.message(p, "capacity"); return false; }
        try {
            boolean mounted;
            s.mounting = true;
            try { mounted = p.addPassenger(mob); }
            finally { s.mounting = false; }
            if (!mounted || s.closed.get() || s.exiting || mob.getVehicle() != p) {
                abortEstablishment(s); return false;
            }
            s.installInput(plugin.createInput(p));
            TaskScheduler.Handle handle = scheduler.repeat(p, () -> {
                if (s.closed.get()) return;
                CarryInput input = s.input.get();
                if (input != null && !s.exiting) input.move(p.getEyeLocation());
                // Input follows each tick; heavier session validation keeps its configured cadence.
                if (input == null || p.getTicksLived() % c.period == 0) check(s);
            }, () -> forget(s), s.input.get() == null ? c.period : 1);
            if (handle == null) { abortEstablishment(s); return false; }
            s.install(handle);
            boolean committed;
            synchronized (reservationLock) {
                committed = accepting && !s.closed.get();
                if (committed) cooldowns.put(actor.getUniqueId(), now);
            }
            if (!committed) { abortEstablishment(s); return false; }
            plugin.holdingHint(p);
            plugin.pickupConsumed(actor);
            if (actor != p) plugin.pickupConsumed(p);
            if (!s.players) plugin.message(p, "picked-up");
            return true;
        } catch (RuntimeException e) {
            abortEstablishment(s);
            plugin.getLogger().warning("抱起失败，已清理会话：" + e.getClass().getSimpleName());
            return false;
        }
    }

    private void abortEstablishment(Session s) {
        s.exiting = true;
        // A synchronous mount-event callback may have already closed the logical session.
        // Roll back the actual native relation even then; ordinary release intentionally ignores closed sessions.
        java.util.Set<UUID> ids = s.players ? java.util.Set.of(s.playerId, s.mobId) : java.util.Set.of(s.playerId);
        CarryModule.beginSafetyDetach(ids);
        try {
            if (scheduler.owns(s.player) && scheduler.owns(s.mob) && !detachOwned(s)) {
                plugin.getLogger().severe("抱起挂载回滚被第三方阻止：" + s.playerId + " / " + s.mobId);
            }
        } finally { forget(s); CarryModule.endSafetyDetach(ids); }
    }

    private void check(Session s) {
        if (s.closed.get()) return;
        Settings c = plugin.settings();
        if (s.exiting) {
            if (!s.player.isOnline() || s.player.isDead()
                    || clock.getAsLong() - s.started >= c.maxSeconds * 1_000_000_000L) forget(s);
            return;
        }
        if (!c.enabled || !s.player.isOnline() || s.player.isDead()
                || (!s.players && (!c.yaml.getBoolean("pickup.enabled", true) || !c.permission(s.player, c.usePermission)))
                || c.worlds.contains(s.player.getWorld().getName())
                || clock.getAsLong() - s.started >= c.maxSeconds * 1_000_000_000L) {
            release(s, "auto-released"); return;
        }
        if (!scheduler.owns(s.mob)) {
            release(s, null);
            return;
        }
        if (s.mob instanceof Player && !playerSessionAllowed(s, c)) {
            release(s, "auto-released"); return;
        }
        if (!s.mob.isValid() || s.mob.isDead() || s.mob.getVehicle() != s.player) forget(s);
    }

    private boolean playerSessionAllowed(Session s, Settings c) {
        Player passenger = (Player)s.mob;
        return available(s.player) && available(passenger) && c.playerEnabled("carry")
                && c.playerPermission(s.player, "carry") && c.playerPermission(passenger, "receive")
                && plugin.acceptsCarry(passenger);
    }

    void sneak(Player p) {
        Session s = carriers.get(p.getUniqueId());
        if (s == null || s.exiting) return;
        long now = clock.getAsLong();
        if (now - s.started < plugin.settings().holdGuardMs * 1_000_000L) { s.lastSneak = 0; return; }
        if (s.lastSneak != 0 && now - s.lastSneak <= plugin.settings().doubleShiftMs * 1_000_000L) {
            release(s, "put-down");
        } else s.lastSneak = now;
    }

    boolean throwHeld(Player p) {
        if (!scheduler.owns(p)) return false;
        Session s = carriers.get(p.getUniqueId());
        if (s == null) return false;
        if (s.exiting) return true;
        Settings c = plugin.settings();
        if (clock.getAsLong() - s.started < c.holdGuardMs * 1_000_000L) return true;
        if (!c.enabled || (!s.players && (!c.yaml.getBoolean("pickup.enabled", true) || !c.permission(p, c.usePermission))) || c.worlds.contains(p.getWorld().getName())) {
            release(s, "auto-released"); return true;
        }
        if (!c.throwing || (!s.players && !c.permission(p, c.throwPermission))
                || (s.players && (!c.playerEnabled("throw") || !c.playerPermission(p, "throw")))) {
            plugin.message(p, "throw-disabled"); return true;
        }
        if (!scheduler.owns(s.mob)) { release(s, "auto-released"); return true; }
        if (s.players && !playerSessionAllowed(s, c)) { release(s, "auto-released"); return true; }
        Vector direction = p.getEyeLocation().getDirection().normalize().multiply(c.force);
        direction.setY(Math.max(-3, Math.min(3, direction.getY() + c.lift)));
        if (s.mob.getVehicle() != p) { forget(s); return true; }
        // CraftEntity's boolean return does not prove dismount succeeded on 26.2.
        s.exiting = true;
        protectRelease(s);
        p.removePassenger(s.mob);
        if (s.mob.getVehicle() == p) {
            s.exiting = false;
            plugin.message(p, "release-blocked"); return true;
        }
        if (s.players) {
            finishPlayerRelease(s, direction, "thrown");
            return true;
        }
        forget(s);
        if (s.mob.getVehicle() == null && s.mob.isValid()) {
            // An entity scheduler tick avoids the vanilla dismount tick overwriting velocity.
            scheduler.later(s.mob, () -> {
                if (!s.mob.isValid() || s.mob.isDead() || s.mob.isInsideVehicle()) return;
                s.mob.setFallDistance(0);
                s.mob.setVelocity(direction);
            }, () -> {});
            plugin.message(p, "thrown");
        }
        return true;
    }

    void drop(Player p, String message) {
        Session s = carriers.get(p.getUniqueId());
        if (s != null) release(s, message);
    }

    void down(Player passenger) {
        Session s = carried.get(passenger.getUniqueId());
        if (s != null && s.players) release(s, "put-down");
    }

    void endParticipant(Player p) {
        drop(p, null); down(p);
    }

    /** Native pre-teleport event runs in the owner context, before the core builds its passenger tree. */
    boolean beforeTeleport(Player p) {
        Session s = carriers.get(p.getUniqueId());
        if (s == null) s = carried.get(p.getUniqueId());
        if (s == null) return true;
        if (!scheduler.owns(s.player) || !scheduler.owns(s.mob)) return false;
        // A same-position player resync belongs to the release pipeline itself.
        protectRelease(s);
        s.exiting = true;
        if (!detachOwned(s)) { s.exiting = false; return false; }
        // No position resync or velocity task may run after an external teleport has begun.
        forget(s);
        return true;
    }

    void release(Session s, String message) {
        if (s.exiting || s.closed.get()) return;
        if (!scheduler.owns(s.mob)) {
            if (s.cleanupQueued.compareAndSet(false, true)) {
                try {
                    scheduler.later(s.mob, () -> {
                        try { release(s, null); }
                        finally { s.cleanupQueued.set(false); }
                    }, () -> { s.cleanupQueued.set(false); forget(s); });
                } catch (RuntimeException e) { s.cleanupQueued.set(false); throw e; }
            }
            return;
        }
        s.exiting = true;
        protectRelease(s);
        if (!detachOwned(s)) {
            s.exiting = false;
            if (message != null && scheduler.owns(s.player) && s.player.isOnline()) plugin.message(s.player, "release-blocked");
            return;
        }
        if (s.players && s.mob.isValid() && !s.mob.isDead() && ((Player)s.mob).isOnline()) {
            finishPlayerRelease(s, null, message);
            return;
        }
        forget(s);
        if (message != null && scheduler.owns(s.player) && s.player.isOnline()) plugin.message(s.player, message);
    }

    private void protectRelease(Session s) {
        plugin.protectMounts(s.playerId);
        if (s.players) plugin.protectMounts(s.mobId);
    }

    void dismounted(Session s) { protectRelease(s); forget(s); }

    private void finishPlayerRelease(Session s, Vector velocity, String message) {
        // Keep both indexes reserved until the player has detached and position sync has completed.
        // A new interaction cannot remount either participant between dismount and launch.
        scheduler.later(s.mob, () -> {
            if (s.closed.get()) return;
            Player passenger = (Player)s.mob;
            if (!passenger.isOnline() || !passenger.isValid() || passenger.isDead()) { forget(s); return; }
            if (passenger.isInsideVehicle()) { s.exiting = false; notifyCarrier(s, "release-blocked"); return; }
            if (!plugin.settings().yaml.getBoolean("players.resync-after-dismount", true)) {
                completePlayerRelease(s, velocity, message, true); return;
            }
            try {
                java.util.UUID passengerId = s.mobId;
                plugin.beginPositionSync(passenger);
                scheduler.syncPosition(passenger).whenComplete((success, error) -> {
                    plugin.endPositionSync(passengerId);
                    // Completion can run anywhere; access live state only in the next owning entity tick.
                    scheduler.later(passenger, () -> completePlayerRelease(s, velocity, message,
                            error == null && Boolean.TRUE.equals(success)), () -> forget(s));
                });
            } catch (RuntimeException failure) {
                plugin.endPositionSync(passenger.getUniqueId());
                completePlayerRelease(s, velocity, message, false);
            }
        }, () -> forget(s));
    }

    private void completePlayerRelease(Session s, Vector velocity, String message, boolean synced) {
        if (s.closed.get()) return;
        if (!s.mob.isValid() || s.mob.isDead() || !((Player)s.mob).isOnline()) { forget(s); return; }
        if (s.mob.isInsideVehicle()) {
            if (s.mob.getVehicle().getUniqueId().equals(s.playerId)) s.exiting = false;
            else forget(s);
            notifyCarrier(s, "release-blocked"); return;
        }
        // Do not tell the carrier a launch succeeded until ownership, detach and sync all passed.
        protectRelease(s);
        long now = clock.getAsLong();
        cooldowns.put(s.mobId, now);
        plugin.pickupConsumed((Player)s.mob);
        Runnable rearmCarrier = () -> {
            if (s.player.isOnline()) { cooldowns.put(s.playerId, now); plugin.pickupConsumed(s.player); }
        };
        if (scheduler.owns(s.player)) rearmCarrier.run();
        else scheduler.later(s.player, rearmCarrier, () -> {});
        forget(s);
        if (synced && velocity != null) { s.mob.setFallDistance(0); s.mob.setVelocity(velocity); }
        notifyCarrier(s, synced ? message : "sync-failed");
    }

    private void notifyCarrier(Session s, String key) {
        if (key == null) return;
        if (scheduler.owns(s.player)) {
            if (s.player.isOnline()) plugin.message(s.player, key);
        } else scheduler.later(s.player, () -> { if (s.player.isOnline()) plugin.message(s.player, key); }, () -> {});
    }

    private boolean detachOwned(Session s) {
        Session current = carried.get(s.mobId);
        if (current != null && current != s) return true;
        if (!s.mob.isValid() || s.mob.getVehicle() == null) return true;
        if (!s.mob.getVehicle().getUniqueId().equals(s.playerId)) return true;
        // Removing a passenger mutates both live entities; schedule retries on the carrier when it still owns the pair.
        if (!scheduler.owns(s.player)) return false;
        s.mob.leaveVehicle();
        Entity vehicle = s.mob.getVehicle();
        return vehicle == null || !vehicle.getUniqueId().equals(s.playerId);
    }

    boolean mountStillAllowed(Session s) {
        Settings c = plugin.settings();
        if (!s.players || !scheduler.owns(s.player) || !scheduler.owns(s.mob)) return false;
        Player passenger = (Player)s.mob;
        return c.enabled && playerSessionAllowed(s, c)
                && s.player.getWorld() == passenger.getWorld() && !c.worlds.contains(s.player.getWorld().getName())
                && !s.player.isInsideVehicle() && !passenger.isInsideVehicle()
                && s.player.getPassengers().isEmpty() && passenger.getPassengers().isEmpty()
                && s.player.getLocation().distanceSquared(passenger.getLocation()) <= c.playerRange * c.playerRange
                && s.player.hasLineOfSight(passenger)
                && c.playerHandsAllowed(s.player, true);
    }

    void forget(Session s) {
        if (!s.closed.compareAndSet(false, true)) return;
        carriers.remove(s.playerId, s); carried.remove(s.mobId, s);
        TaskScheduler.Handle task = s.task.getAndSet(null);
        if (task != null) task.cancel();
        CarryInput input = s.input.getAndSet(null);
        // Network-only cleanup also works on retirement/disable; it never accesses live entity state.
        if (input != null) input.remove();
    }

    void removed(Entity e) {
        Session s = carried.get(e.getUniqueId());
        if (s != null) forget(s);
        s = carriers.get(e.getUniqueId());
        if (s != null) {
            Session session = s;
            forget(session);
            scheduler.later(session.mob, () -> detachOwned(session), () -> {});
        }
        cooldowns.remove(e.getUniqueId());
    }

    void close() {
        synchronized (reservationLock) { accepting = false; }
        for (Session s : carriers.values()) {
            // Folia disable runs globally: never force cross-region entity mutations here.
            forget(s);
            if (scheduler.owns(s.mob)) detachOwned(s);
        }
        carriers.clear(); carried.clear(); cooldowns.clear();
    }
}
