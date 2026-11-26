package cn.jiujiu.gsit.carry;

import org.bukkit.Location;

/** Client-only target: create/move in carrier's owning tick; removal sends an immutable network packet only. */
public interface CarryInput {
    int entityId();
    void move(Location eye);
    void remove();
}
