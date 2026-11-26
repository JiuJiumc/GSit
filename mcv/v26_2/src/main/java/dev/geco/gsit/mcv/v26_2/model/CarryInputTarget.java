package dev.geco.gsit.mcv.v26_2.model;

import cn.jiujiu.gsit.carry.CarryInput;
import java.util.Set;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Location;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

/** Packet-only target: never constructs or mutates an NMS/Bukkit entity, including at world origin. */
public final class CarryInputTarget implements CarryInput {
    private final ServerGamePacketListenerImpl connection;
    private final int id;
    private final java.util.concurrent.atomic.AtomicBoolean removed = new java.util.concurrent.atomic.AtomicBoolean();
    private final Runnable removePacket;

    public CarryInputTarget(Player player) {
        // This constructor is invoked in the real player's owning entity context.
        var viewer = ((CraftPlayer) player).getHandle();
        connection = viewer.connection;
        id = viewer.level().getNextEntityId();
        var removal = new ClientboundRemoveEntitiesPacket(id);
        removePacket = () -> connection.send(removal);
        // Keep the eye inside the box, including when looking straight up/down.
        Location eye = player.getEyeLocation();
        try {
            connection.send(new ClientboundAddEntityPacket(id, UUID.randomUUID(),
                    eye.getX(), eye.getY() - 2.0, eye.getZ(), 0, 0, EntityTypes.INTERACTION, 0, Vec3.ZERO, 0));
            // 26.2 protocol: Entity declares slots 0..7; Interaction width=8, height=9.
            // These are protocol indices verified against the pinned server/client layout, not new data registrations.
            connection.send(new ClientboundSetEntityDataPacket(id, List.of(
                    new SynchedEntityData.DataValue<>(8, EntityDataSerializers.FLOAT, 4.0f),
                    new SynchedEntityData.DataValue<>(9, EntityDataSerializers.FLOAT, 4.0f))));
        } catch (RuntimeException failure) {
            remove();
            throw failure;
        }
    }

    @Override public int entityId() { return id; }
    @Override public void move(Location eye) {
        if (removed.get()) return;
        connection.send(new ClientboundTeleportEntityPacket(id,
                new PositionMoveRotation(new Vec3(eye.getX(), eye.getY() - 2.0, eye.getZ()), Vec3.ZERO, 0, 0), Set.of(), false));
    }
    @Override public void remove() {
        if (removed.compareAndSet(false, true)) removePacket.run();
    }
}
