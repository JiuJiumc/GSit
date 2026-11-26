package cn.jiujiu.gsit.carry;

import me.earthme.luminol.api.entity.EntityTeleportAsyncEvent;
import org.bukkit.event.*;

/** Shiroha/Mizuki/Luminol pre-teleport event, verified against the archived 26.2 core. */
final class NativeTeleportListener implements Listener {
    private final CarryModule module;
    NativeTeleportListener(CarryModule module) { this.module = module; }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void teleport(EntityTeleportAsyncEvent event) { module.beforeTeleport(event.getEntity(), event.getDestination()); }
}
