package dev.geco.gsit.service;

import dev.geco.gsit.GSitMain;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.function.Consumer;
import org.bukkit.configuration.file.YamlConfiguration;

/** Startup-only immutable network settings; never reads files on player region threads. */
record UpdateOptions(int connectMs, int readMs, int totalMs, int maxBytes,
                     int successSeconds, int failureSeconds, boolean logFailure, String failureMessage) {
    static String source(GSitMain plugin) {
        // The upstream getSource() leaves the descriptor stream open; close our startup read.
        try (var stream=plugin.getResource("plugin.yml")) {
            if (stream==null) throw new IllegalStateException("Missing plugin.yml");
            try (var reader=new InputStreamReader(stream,StandardCharsets.UTF_8)) {
                var yaml=new YamlConfiguration();yaml.load(reader);
                return yaml.getString("source","").toLowerCase(Locale.ROOT);
            }
        } catch (Exception error) {
            plugin.getLogger().warning("更新来源无法读取，本次不查询远端。");
            return "";
        }
    }
    static UpdateOptions load(GSitMain plugin) {
        var yaml = new YamlConfiguration();
        try {
            File file = new File(plugin.getDataFolder(), "jiujiu-update.yml");
            if (!file.exists()) plugin.saveResource("jiujiu-update.yml", false);
            yaml.load(file);
        } catch (Exception error) {
            plugin.getLogger().warning("更新检查配置无法读取，使用安全默认值。");
        }
        return parse(yaml, text -> plugin.getLogger().warning(text));
    }
    static UpdateOptions parse(YamlConfiguration yaml, Consumer<String> warning) {
        boolean log = true;
        if (yaml.contains("log-failure") && !yaml.isBoolean("log-failure"))
            warning.accept("log-failure 必须为 true 或 false，使用 true。");
        else log = yaml.getBoolean("log-failure", true);
        String message = yaml.getString("failure-message", "更新检查暂不可用（{source} {reason}），不影响功能。");
        if ((yaml.contains("failure-message") && !yaml.isString("failure-message")) || message == null || message.length() > 256 || message.contains("\n") || message.contains("\r")) {
            warning.accept("failure-message 必须为文本，不能换行或超过256字符，使用默认提示。");
            message = "更新检查暂不可用（{source} {reason}），不影响功能。";
        }
        return new UpdateOptions(number(yaml,"connect-timeout-ms",3000,500,15000,warning),
                number(yaml,"read-timeout-ms",3000,500,15000,warning),
                number(yaml,"total-timeout-ms",8000,1000,30000,warning),
                number(yaml,"max-response-bytes",1048576,1024,2097152,warning),
                number(yaml,"success-cache-seconds",86400,60,604800,warning),
                number(yaml,"failure-cache-seconds",86400,60,604800,warning),log,message);
    }
    private static int number(YamlConfiguration yaml,String key,int fallback,int min,int max,Consumer<String> warning) {
        if (!yaml.contains(key)) return fallback;
        if (!yaml.isInt(key) || yaml.getInt(key)<min || yaml.getInt(key)>max) {
            warning.accept(key+" 超出整数范围 ["+min+", "+max+"]，使用 "+fallback+"。"); return fallback;
        }
        return yaml.getInt(key);
    }
}
