package cn.jiujiu.gsit.carry;

import org.bukkit.event.*;
import org.bukkit.event.player.PlayerTeleportEvent;

/** Registered only on non-Folia servers; Folia uses the native pre-teleport bridge. */
final class PaperTeleportListener implements Listener {
    private final CarryModule module;
    PaperTeleportListener(CarryModule module) { this.module = module; }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent event) { module.beforeTeleport(event.getPlayer(), event.getTo()); }
}
