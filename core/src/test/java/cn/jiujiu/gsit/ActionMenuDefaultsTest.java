package cn.jiujiu.gsit;

import org.bukkit.configuration.file.YamlConfiguration;

public final class ActionMenuDefaultsTest {
    public static int run() throws Exception {
        var bundled = new YamlConfiguration(); bundled.load("resources/jiujiu-menu.yml");
        var old = new YamlConfiguration(); old.load("resources/jiujiu-menu.yml");
        old.set("buttons.receive-ride", null); old.set("buttons.receive-carry", null);
        old.set("states", null); old.set("messages.receive-ride-on", null);
        old.set("buttons.sit.slot", 10); old.set("buttons.lay.enabled", false);
        old.set("title", "原菜单");
        // Reproduce the actual Bukkit overload used in ActionMenu, not a hand-written mock.
        old.setDefaults(bundled);
        if (old.getInt("buttons.receive-ride.slot", -1) != -1)
            throw new AssertionError("expected Bukkit fallback overload to bypass root defaults");
        ActionMenu.mergeMissing(old, bundled);
        if (old.getInt("buttons.receive-ride.slot", -1) != 38
                || old.getInt("buttons.receive-carry.slot", -1) != 42)
            throw new AssertionError("both buttons must have valid slots with the runtime getter");
        if (!old.getString("buttons.receive-carry.name", "missing").contains("允许别人抱起我")
                || !old.getString("states.on", "missing").contains("已开启"))
            throw new AssertionError("button names and state labels must materialize");
        if (old.getInt("buttons.sit.slot", -1) != 10 || old.getBoolean("buttons.lay.enabled", true)
                || !old.getString("title").equals("原菜单"))
            throw new AssertionError("existing settings must be preserved");
        old.set("buttons.receive-ride.enabled", false);
        ActionMenu.mergeMissing(old, bundled);
        if (old.getBoolean("buttons.receive-ride.enabled", true))
            throw new AssertionError("explicitly disabled button must stay disabled");
        return 5;
    }
}
