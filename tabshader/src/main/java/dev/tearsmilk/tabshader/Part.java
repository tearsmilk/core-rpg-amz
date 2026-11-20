package dev.tearsmilk.tabshader;

import java.util.Locale;

public enum Part {
    TAB("tab"),
    BOARD("board"),
    BOSSBAR("bossbar"),
    ACTIONBAR("actionbar"),
    TITLE("title");

    private final String key;

    Part(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static Part parse(String s) {
        if (s == null) {
            return null;
        }
        String k = s.toLowerCase(Locale.ROOT);
        for (Part p : values()) {
            if (p.key.equals(k)) {
                return p;
            }
        }
        return switch (k) {
            case "sidebar", "scoreboard", "score" -> BOARD;
            case "boss", "bar" -> BOSSBAR;
            case "action", "ab" -> ACTIONBAR;
            case "titles", "subtitle" -> TITLE;
            case "list", "tablist" -> TAB;
            default -> null;
        };
    }
}
