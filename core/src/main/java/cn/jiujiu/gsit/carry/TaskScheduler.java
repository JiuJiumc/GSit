package cn.jiujiu.gsit.carry;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import dev.geco.gsit.GSitMain;

/** All scheduling enters here; entity tasks always wait at least one tick. */
public final class TaskScheduler {
    interface Handle { void cancel(); }
    private final GSitMain plugin;
    private final boolean folia;
    private final java.util.Set<Ticket> tickets = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final class Ticket implements Handle {
        volatile Handle delegate;
        final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        void install(Handle handle) { delegate = handle; if (cancelled.get() && handle != null) handle.cancel(); }
        @Override public void cancel() {
            cancelled.set(true); tickets.remove(this);
            Handle handle = delegate; if (handle != null) handle.cancel();
        }
    }

    TaskScheduler(GSitMain plugin) {
        this.plugin = plugin;
        // GSit detects Folia once at startup; reuse that result.
        folia = plugin.isFoliaServer();
    }

    public boolean owns(Entity entity) {
        return folia ? Bukkit.isOwnedByCurrentRegion(entity) : Bukkit.isPrimaryThread();
    }

    java.util.concurrent.CompletableFuture<Boolean> syncPosition(org.bukkit.entity.Player player) {
        // Called in the player's entity context, after confirming there is no vehicle.
        return player.teleportAsync(player.getLocation().clone(), org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
    }

    Handle repeat(Entity entity, Runnable action, Runnable retired, long period) {
        Ticket ticket = new Ticket(); tickets.add(ticket);
        Runnable onRetire = () -> { ticket.cancel(); retired.run(); };
        try {
        if (folia) {
            var task = entity.getScheduler().runAtFixedRate(plugin, t -> { if (!ticket.cancelled.get()) action.run(); }, onRetire, 1, Math.max(1, period));
            if (task == null) { ticket.cancel(); return null; }
            ticket.install(task::cancel); return ticket;
        }
        var task = Bukkit.getScheduler().runTaskTimer(plugin, () -> { if (!ticket.cancelled.get()) action.run(); }, 1, Math.max(1, period));
        ticket.install(task::cancel); return ticket;
        } catch (RuntimeException | Error e) { ticket.cancel(); throw e; }
    }

    boolean later(Entity entity, Runnable action, Runnable retired) {
        if (!plugin.isEnabled()) { retired.run(); return false; }
        Ticket ticket = new Ticket(); tickets.add(ticket);
        Runnable invoke = () -> { tickets.remove(ticket); if (!ticket.cancelled.get() && plugin.isEnabled()) action.run(); };
        Runnable onRetire = () -> { ticket.cancel(); retired.run(); };
        try {
        if (folia) {
            var task = entity.getScheduler().runDelayed(plugin, t -> invoke.run(), onRetire, 1);
            if (task == null) { ticket.cancel(); retired.run(); return false; }
            ticket.install(task::cancel);
        } else {
            var task = Bukkit.getScheduler().runTaskLater(plugin, invoke, 1);
            ticket.install(task::cancel);
        }
        return true;
        } catch (RuntimeException | Error e) { ticket.cancel(); throw e; }
    }

    void async(Runnable action) {
        plugin.getTaskService().run(action, false);
    }

    void cancelAll() {
        for (Ticket ticket : tickets) ticket.cancel();
        tickets.clear();
        // Never cancel the parent GSit's unrelated pose/menu tasks.
    }
}
