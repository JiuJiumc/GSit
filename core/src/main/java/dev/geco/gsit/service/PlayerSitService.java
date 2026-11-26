/* Maintenance implementation recovered from GSit 3.7.0's PlayerSitService, then adapted for
 * Folia ownership, concurrent state and pre-teleport cleanup. Original author: Gecolay.
 * Public methods and native GSit seat creation are retained. This is not original upstream source. */
package dev.geco.gsit.service;

import cn.jiujiu.gsit.carry.CarryModule;
import dev.geco.gsit.GSitMain;
import dev.geco.gsit.api.event.*;
import dev.geco.gsit.model.StopReason;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.*;

public class PlayerSitService {
    public static final String PLAYERSIT_ENTITY_TAG = "GSit_PlayerSitEntity";
    private final GSitMain gSitMain;
    private final int sitEntityStackCount;
    private record Link(UUID bottom, UUID top, List<UUID> seats, long started) {}
    private final ConcurrentHashMap<UUID, Link> bottomToTopStacks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Link> topToBottomStacks = new ConcurrentHashMap<>();
    private final Set<Player> preventDismountStackPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> mounting = ConcurrentHashMap.newKeySet();
    private final Set<UUID> stopping = ConcurrentHashMap.newKeySet();
    private final AtomicInteger playerSitCount = new AtomicInteger();
    private final AtomicLong playerSitTime = new AtomicLong();

    public PlayerSitService(GSitMain main) {
        gSitMain = main;
        sitEntityStackCount = main.getVersionManager().isNewerOrVersion(1, 20, 2) ? 1 : 2;
    }
    private boolean owns(Entity e) { return gSitMain.isFoliaServer() ? Bukkit.isOwnedByCurrentRegion(e) : Bukkit.isPrimaryThread(); }
    public int getSitEntityStackCount() { return sitEntityStackCount; }
    public Set<Player> getPreventDismountStackPlayers() { return preventDismountStackPlayers; }
    public boolean isPlayerInPlayerSitStack(Player player) {
        UUID id = player.getUniqueId(); return bottomToTopStacks.containsKey(id) || topToBottomStacks.containsKey(id);
    }
    public void removeAllPlayerSitStacks() {
        // GSit's disable/reload can run globally. Marshal each live mutation to its owner.
        for (UUID id : List.copyOf(topToBottomStacks.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) { purge(id); continue; }
            if (owns(p)) stopPlayerSit(p, StopReason.PLUGIN, true, true, false);
            else if (gSitMain.isEnabled()) gSitMain.getTaskService().run(() -> stopPlayerSit(p, StopReason.PLUGIN, true, true, false), p);
        }
        preventDismountStackPlayers.clear();
        if (!gSitMain.isEnabled()) { bottomToTopStacks.clear(); topToBottomStacks.clear(); mounting.clear(); stopping.clear(); }
    }
    private void purge(UUID id) {
        Link upper = bottomToTopStacks.remove(id), lower = topToBottomStacks.remove(id);
        if (upper != null) topToBottomStacks.remove(upper.top(), upper);
        if (lower != null) bottomToTopStacks.remove(lower.bottom(), lower);
    }
    public boolean sitOnPlayer(Player player, Player target) {
        if (!owns(player) || !owns(target) || player == target || !CarryModule.allowGSit(player, target)) return false;
        UUID rider = player.getUniqueId(), bottom = target.getUniqueId();
        if (!mounting.add(rider)) return false;
        if (!mounting.add(bottom)) { mounting.remove(rider); return false; }
        try {
            if (stopping.contains(rider) || stopping.contains(bottom) || topToBottomStacks.containsKey(rider) || bottomToTopStacks.containsKey(bottom)) return false;
            if (!gSitMain.getEntityUtil().isPlayerSitLocationValid(target.getLocation())) return false;
            PrePlayerPlayerSitEvent pre = new PrePlayerPlayerSitEvent(player, target);
            Bukkit.getPluginManager().callEvent(pre);
            if (pre.isCancelled() || !CarryModule.allowGSit(player, target)) return false;
            List<UUID> seats = gSitMain.getEntityUtil().createPlayerSitEntities(player, target);
            if (seats == null) return false;
            Link link = new Link(bottom, rider, List.copyOf(seats), System.nanoTime());
            bottomToTopStacks.put(bottom, link); topToBottomStacks.put(rider, link);
            playerSitCount.incrementAndGet();
            if (gSitMain.getConfigService().CUSTOM_MESSAGE) gSitMain.getMessageService().sendActionBarMessage(player, "Messages.action-playersit-info");
            Bukkit.getPluginManager().callEvent(new PlayerPlayerSitEvent(player, target));
            return true;
        } finally { mounting.remove(rider); mounting.remove(bottom); }
    }
    public boolean stopPlayerSit(Player source, StopReason reason) { return stopPlayerSit(source, reason, true, true, true); }
    public boolean stopPlayerSit(Player source, StopReason reason, boolean removePassengers, boolean removeVehicle, boolean callPreEvent) {
        if (!owns(source)) {
            if (gSitMain.isEnabled()) gSitMain.getTaskService().run(() -> stopPlayerSit(source, reason, removePassengers, removeVehicle, callPreEvent), source);
            return false;
        }
        UUID id = source.getUniqueId();
        if (!stopping.add(id)) return true;
        try {
            Link upper = removePassengers ? bottomToTopStacks.get(id) : null;
            Link lower = removeVehicle ? topToBottomStacks.get(id) : null;
            if (upper == null && lower == null) return true;
            if (callPreEvent) {
                PrePlayerStopPlayerSitEvent pre = new PrePlayerStopPlayerSitEvent(source, reason);
                Bukkit.getPluginManager().callEvent(pre);
                if (pre.isCancelled() && reason.isCancellable()) return false;
            }
            if (upper != null && !detach(source, upper, true)) return false;
            if (lower != null && !detach(source, lower, false)) return false;
            preventDismountStackPlayers.remove(source);
            Bukkit.getPluginManager().callEvent(new PlayerStopPlayerSitEvent(source, reason));
            return true;
        } finally { stopping.remove(id); }
    }
    private boolean detach(Player source, Link link, boolean upper) {
        // Only touch the GSit link represented by this entry, never an unrelated new vehicle/passenger.
        if (upper) {
            for (Entity passenger : List.copyOf(source.getPassengers())) {
                if (!link.seats().contains(passenger.getUniqueId()) && !link.top().equals(passenger.getUniqueId())) continue;
                if (!owns(passenger)) return false;
                source.removePassenger(passenger);
                if (passenger.getVehicle() == source) return false;
            }
        } else {
            Entity vehicle = source.getVehicle();
            if (vehicle != null && (link.seats().contains(vehicle.getUniqueId()) || link.bottom().equals(vehicle.getUniqueId()))) {
                if (!owns(vehicle)) return false;
                source.leaveVehicle();
                if (source.getVehicle() == vehicle) return false;
            }
        }
        bottomToTopStacks.remove(link.bottom(), link); topToBottomStacks.remove(link.top(), link);
        playerSitTime.addAndGet(Math.max(0, System.nanoTime() - link.started()));
        for (UUID seatId : link.seats()) {
            Entity seat = Bukkit.getEntity(seatId);
            if (seat == null) continue;
            Runnable remove = () -> { if (seat.isValid() && seat.getScoreboardTags().contains(PLAYERSIT_ENTITY_TAG)) seat.remove(); };
            if (owns(seat)) remove.run(); else if (gSitMain.isEnabled()) gSitMain.getTaskService().run(remove, seat);
        }
        return true;
    }
    /** Tear down the connected GSit player chain before the native core snapshots passengers. */
    public boolean splitStackForTeleport(Player source) {
        if (!owns(source)) return false;
        Set<UUID> ids = new LinkedHashSet<>();
        ArrayDeque<UUID> queue = new ArrayDeque<>(); queue.add(source.getUniqueId());
        while (!queue.isEmpty()) {
            UUID id = queue.removeFirst(); if (!ids.add(id)) continue;
            Link upper = bottomToTopStacks.get(id), lower = topToBottomStacks.get(id);
            if (upper != null) queue.addLast(upper.top());
            if (lower != null) queue.addLast(lower.bottom());
        }
        List<Player> players = new ArrayList<>();
        for (UUID id : ids) {
            Player player = id.equals(source.getUniqueId()) ? source : Bukkit.getPlayer(id);
            if (player == null) continue;
            if (!owns(player)) return false;
            players.add(player);
        }
        CarryModule.beginSafetyDetach(ids);
        try {
            boolean success = true;
            for (Player p : players) success &= stopPlayerSit(p, StopReason.TELEPORT, true, true, false);
            return success;
        } finally { CarryModule.endSafetyDetach(ids); }
    }
    public int getPlayerSitCount() { return playerSitCount.get(); }
    public int getPlayerSitTime() { return (int)Math.min(Integer.MAX_VALUE, playerSitTime.get() / 1_000_000_000L); }
    public void resetPlayerSitStats() { playerSitCount.set(0); playerSitTime.set(0); }
}
