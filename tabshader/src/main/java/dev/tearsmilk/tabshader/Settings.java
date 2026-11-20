package dev.tearsmilk.tabshader;

import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;
import java.util.Locale;

record Settings(
        String server,
        String zone,
        String timeFormat,
        String dateFormat,
        int period,
        boolean tab,
        List<String> header,
        List<String> footer,
        boolean board,
        boolean respectOther,
        String boardTitle,
        List<String> boardLines,
        boolean bossbar,
        String bossbarText,
        String bossbarProgress,
        BossBar.Color bossbarColor,
        BossBar.Overlay bossbarOverlay,
        String actionbarTest,
        String titleTest,
        String subtitleTest,
        int fadeIn,
        int stay,
        int fadeOut) {

    static Settings of(FileConfiguration c) {
        return new Settings(
                c.getString("server-name", "Server"),
                c.getString("time-zone", "Europe/Moscow"),
                c.getString("time-format", "HH:mm"),
                c.getString("date-format", "dd.MM.yyyy"),
                Math.max(5, c.getInt("update-ticks", 20)),
                c.getBoolean("tab.enabled", true),
                c.getStringList("tab.header"),
                c.getStringList("tab.footer"),
                c.getBoolean("board.enabled", true),
                c.getBoolean("board.respect-other-scoreboards", true),
                c.getString("board.title", "{server}"),
                c.getStringList("board.lines"),
                c.getBoolean("bossbar.enabled", true),
                c.getString("bossbar.text", "{server}"),
                c.getString("bossbar.progress", "clock").toLowerCase(Locale.ROOT),
                color(c.getString("bossbar.color", "pink")),
                overlay(c.getString("bossbar.overlay", "progress")),
                c.getString("actionbar.test", "actionbar"),
                c.getString("title.test-title", "title"),
                c.getString("title.test-subtitle", ""),
                ticks(c.getConfigurationSection("title"), "fade-in", 10),
                ticks(c.getConfigurationSection("title"), "stay", 60),
                ticks(c.getConfigurationSection("title"), "fade-out", 15));
    }

    private static int ticks(ConfigurationSection s, String key, int def) {
        return s == null ? def : Math.max(0, s.getInt(key, def));
    }

    static BossBar.Color color(String s) {
        try {
            return BossBar.Color.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            return BossBar.Color.PINK;
        }
    }

    static BossBar.Overlay overlay(String s) {
        String k = s.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return switch (k) {
            case "6", "NOTCHED_6" -> BossBar.Overlay.NOTCHED_6;
            case "10", "NOTCHED_10" -> BossBar.Overlay.NOTCHED_10;
            case "12", "NOTCHED_12" -> BossBar.Overlay.NOTCHED_12;
            case "20", "NOTCHED_20" -> BossBar.Overlay.NOTCHED_20;
            default -> BossBar.Overlay.PROGRESS;
        };
    }
}
