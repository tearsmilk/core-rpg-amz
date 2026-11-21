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
        boolean tabVisible,
        List<String> header,
        List<String> footer,
        int maxNameLength,
        int maxColumns,
        int maxPlayers,
        PositionCode.Position tabPosition,
        int tabMargin,
        int tabWidth,
        int bossbarRows,
        boolean pingBars,
        boolean markers,
        boolean board,
        boolean respectOther,
        String boardTitle,
        PositionCode.Position boardPosition,
        int boardMargin,
        PositionCode.Avoid boardAvoid,
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
                c.getBoolean("tab.visible", true),
                c.getStringList("tab.header"),
                c.getStringList("tab.footer"),
                Math.max(0, c.getInt("tab.max-name-length", 12)),
                Math.max(0, Math.min(4, c.getInt("tab.max-columns", 2))),
                Math.max(0, Math.min(TabLayout.CLIENT_CAP, c.getInt("tab.max-players", 15))),
                PositionCode.parse(c.getString("tab.position"), PositionCode.Element.TAB, PositionCode.Position.CENTER),
                PositionCode.clamp(c.getInt("tab.margin", 4), PositionCode.TAB_MAX_MARGIN),
                width(c.getString("tab.width", "205")),
                bossbarRows(c.getString("tab.bossbar-rows", "own")),
                c.getBoolean("tab.ping-bars", false),
                c.getBoolean("position-markers", true),
                c.getBoolean("board.enabled", true),
                c.getBoolean("board.respect-other-scoreboards", true),
                c.getString("board.title", "{server}"),
                PositionCode.parse(c.getString("board.position"), PositionCode.Element.BOARD, PositionCode.Position.RIGHT),
                PositionCode.clamp(c.getInt("board.margin", 4), PositionCode.BOARD_MAX_MARGIN),
                PositionCode.Avoid.parse(c.getString("board.avoid-tab"), PositionCode.Avoid.OVERLAP),
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

    int tabLimit() {
        return TabLayout.maxEntries(maxColumns, maxPlayers);
    }

    static final int AUTO_WIDTH = -1;
    static final int ROWS_OWN = -1;
    static final int ROWS_ALL = -2;

    static int bossbarRows(String s) {
        String k = s == null ? "own" : s.trim().toLowerCase(Locale.ROOT);
        if (k.equals("all")) {
            return ROWS_ALL;
        }
        try {
            return PositionCode.clamp(Integer.parseInt(k), PositionCode.MAX_BOSSBAR_ROWS);
        } catch (NumberFormatException e) {
            return ROWS_OWN;
        }
    }

    static int width(String s) {
        if (s == null) {
            return AUTO_WIDTH;
        }
        String k = s.trim().toLowerCase(Locale.ROOT);
        if (k.isEmpty() || k.equals("auto")) {
            return AUTO_WIDTH;
        }
        try {
            return PositionCode.clampValue(Integer.parseInt(k));
        } catch (NumberFormatException e) {
            return AUTO_WIDTH;
        }
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
