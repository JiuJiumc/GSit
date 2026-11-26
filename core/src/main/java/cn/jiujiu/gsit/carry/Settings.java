package cn.jiujiu.gsit.carry;

import java.util.*;
import java.util.function.Consumer;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;

/** Immutable snapshot, published atomically on reload. */
final class Settings {
    final YamlConfiguration yaml;
    final boolean enabled, sneakPickup, throwing, protectCarried, releaseDamage, ownPets, menuEnabled;
    final int capacity, period, doubleShiftMs, cooldownMs, holdGuardMs, clickDebounceMs, playerCooldownMs, remountGuardMs;
    final double distance, maxWidth, maxHeight, force, lift, maxSeconds, playerRange;
    final Set<String> denied, allowed, worlds;
    final String usePermission, throwPermission, adminPermission, menuId;
    final Consumer<String> warning;

    Settings(YamlConfiguration yaml, Consumer<String> warning) {
        this.yaml = yaml; this.warning = warning;
        for (String key : List.of("pickup", "players", "players.permissions", "controls", "throw", "safety", "performance", "permissions", "messages", "menu")) {
            if (yaml.contains(key) && !yaml.isConfigurationSection(key)) throw new IllegalArgumentException(key + " 必须是配置分组");
        }
        for (String key : List.of("enabled", "pickup.enabled", "pickup.require-sneaking", "pickup.only-own-tamed-pets",
                "players.enabled", "players.carry", "players.throw", "players.resync-after-dismount", "players.empty-hand-only",
                "players.permissions.enabled", "throw.enabled", "safety.detach-before-commands", "safety.detach-before-menus",
                "safety.block-carried-attacks", "safety.release-on-carrier-damage", "safety.prevent-mob-self-dismount", "permissions.per-entity", "menu.enabled", "controls.air-right-click")) {
            if (yaml.contains(key) && !yaml.isBoolean(key)) throw new IllegalArgumentException(key + " 必须是 true 或 false，不能加引号");
        }
        enabled = yaml.getBoolean("enabled", true);
        menuEnabled = yaml.getBoolean("menu.enabled", true);
        Object id = yaml.get("menu.id", "interaction");
        if (!(id instanceof String value) || !value.matches("[a-zA-Z0-9_-]{1,48}"))
            throw new IllegalArgumentException("menu.id 必须是1～48位英文字母、数字、下划线或短横线");
        menuId = value;
        sneakPickup = yaml.getBoolean("pickup.require-sneaking", true);
        throwing = yaml.getBoolean("throw.enabled", true);
        protectCarried = yaml.getBoolean("safety.block-carried-attacks", true);
        releaseDamage = yaml.getBoolean("safety.release-on-carrier-damage", true);
        ownPets = yaml.getBoolean("pickup.only-own-tamed-pets", true);
        capacity = (int) number("performance.max-carriers", 128, 1, 512);
        period = (int) number("performance.check-period-ticks", 20, 5, 200);
        doubleShiftMs = (int) number("controls.double-shift-ms", 450, 150, 1500);
        holdGuardMs = (int) number("controls.hold-guard-ms", 350, 0, 2000);
        clickDebounceMs = (int) number("controls.right-click-debounce-ms", 350, 50, 1000);
        remountGuardMs = (int) number("controls.remount-guard-ms", 1000, 350, 10000);
        cooldownMs = (int) number("pickup.cooldown-ms", 1000, 100, 60000);
        playerCooldownMs = (int) number("players.cooldown-ms", 1000, 100, 60000);
        playerRange = number("players.range", 3, 1, 5);
        distance = number("pickup.range", 3, 1, 5);
        maxWidth = number("pickup.max-width", 1.4, .2, 4);
        maxHeight = number("pickup.max-height", 2.9, .2, 5);
        force = number("throw.horizontal-speed", 1.25, .1, 3);
        lift = number("throw.upward-speed", .45, 0, 1.5);
        maxSeconds = number("safety.max-hold-seconds", 300, 5, 3600);
        denied = types("pickup.denied-types"); allowed = types("pickup.allowed-types");
        worlds = strings("pickup.disabled-worlds");
        usePermission = yaml.getString("permissions.use", "jiujiucarry.use");
        throwPermission = yaml.getString("permissions.throw", "jiujiucarry.throw");
        adminPermission = yaml.getString("permissions.admin", "jiujiucarry.admin");
    }

    private Set<String> types(String key) {
        Set<String> values = new HashSet<>();
        for (String raw : strings(key)) {
            String name = raw.trim().toUpperCase(Locale.ROOT);
            try { EntityType.valueOf(name); values.add(name); }
            catch (IllegalArgumentException e) { throw new IllegalArgumentException(key + " 中未知生物类型：" + raw, e); }
        }
        return Set.copyOf(values);
    }

    private Set<String> strings(String key) {
        if (!yaml.contains(key)) return Set.of();
        if (!yaml.isList(key)) throw new IllegalArgumentException(key + " 必须是列表");
        Set<String> values = new HashSet<>();
        for (Object raw : Objects.requireNonNull(yaml.getList(key))) {
            if (!(raw instanceof String text) || text.isBlank()) throw new IllegalArgumentException(key + " 不能包含空值或非文本项");
            values.add(text);
        }
        return Set.copyOf(values);
    }

    double number(String key, double fallback, double min, double max) {
        double v = yaml.getDouble(key, fallback);
        if (!Double.isFinite(v) || v < min || v > max) {
            warning.accept(key + " 超出范围 [" + min + ", " + max + "]，使用 " + fallback);
            return fallback;
        }
        return v;
    }

    String text(String key) {
        String incoming = switch (key) {
            case "type-denied" -> "&e配置禁止抱起这种生物。";
            case "receive-ride-on" -> "&b允许别人骑我：&a开启";
            case "receive-ride-off" -> "&b允许别人骑我：&7关闭";
            case "receive-carry-on" -> "&b允许别人抱起我：&a开启";
            case "receive-carry-off" -> "&b允许别人抱起我：&7关闭";
            default -> null;
        };
        if (incoming != null) return yaml.getString("messages." + key, incoming);
        if (key.equals("mount-protected")) return yaml.getString("messages.mount-protected", "");
        if (key.equals("menu-unavailable")) return yaml.getString("messages.menu-unavailable", "&e菜单暂不可用，可用 /sq on 或 /sq off。");
        return yaml.getString("messages." + key, key.equals("sync-failed")
                ? "&e已下乘，本次未抛出。" : "");
    }
    boolean playerHandsAllowed(org.bukkit.entity.Player p, boolean carry) {
        if (carry) return p.getInventory().getItemInMainHand().getType().isAir()
                && p.getInventory().getItemInOffHand().getType().isAir();
        return !yaml.getBoolean("players.empty-hand-only", true) || p.getInventory().getItemInMainHand().getType().isAir();
    }
    boolean playerPermission(org.bukkit.entity.Player p, String action) {
        return !yaml.getBoolean("players.permissions.enabled", false)
                || permission(p, yaml.getString("players.permissions." + action, "jiujiuqi." + action));
    }
    boolean playerEnabled(String action) { return yaml.getBoolean("players.enabled", true) && yaml.getBoolean("players." + action, true); }
    boolean permission(org.bukkit.entity.Player player, String node) { return node.isBlank() || player.hasPermission(node); }
    Material material(String key, Material fallback) {
        Material value = Material.matchMaterial(yaml.getString(key, fallback.name()));
        return value == null || !value.isItem() || value.isAir() ? fallback : value;
    }
}
