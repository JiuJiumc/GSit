package cn.jiujiu.gsit.carry;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** UUID-only bounded-by-online-player deduplication; no world or player references. */
final class InteractionGate {
    record Stamp(long nanos, int tick) {}
    private final ConcurrentHashMap<UUID, Stamp> inputs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> mountProtection = new ConcurrentHashMap<>();
    boolean recent(UUID id, long now, int tick, int millis) {
        Stamp stamp = inputs.get(id);
        int elapsedTicks = stamp == null ? Integer.MAX_VALUE : tick - stamp.tick;
        // Vanilla holding use repeats every four client ticks. Require a quiet gap before a second action.
        return stamp != null && (elapsedTicks >= 0 && elapsedTicks <= 5 || now - stamp.nanos < millis * 1_000_000L);
    }
    void mark(UUID id, long now, int tick) { inputs.put(id, new Stamp(now, tick)); }
    void protectMounts(UUID id, long now, int millis) { mountProtection.put(id, now + millis * 1_000_000L); }
    boolean mountProtected(UUID id, long now) {
        Long until = mountProtection.get(id);
        if (until == null) return false;
        if (now - until < 0) return true;
        mountProtection.remove(id, until); return false;
    }
    void remove(UUID id) { inputs.remove(id); mountProtection.remove(id); }
    void clear() { inputs.clear(); mountProtection.clear(); }
    int size() { return inputs.size(); }
    int protectionCount() { return mountProtection.size(); }
}
