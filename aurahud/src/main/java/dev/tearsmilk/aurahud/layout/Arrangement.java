package dev.tearsmilk.aurahud.layout;

import java.util.Locale;

public enum Arrangement {
    ROW("row"),
    COLUMN("column"),
    GRID("grid");

    private final String key;

    Arrangement(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static Arrangement parse(String s) {
        if (s == null) {
            return null;
        }
        String k = s.trim().toLowerCase(Locale.ROOT);
        for (Arrangement a : values()) {
            if (a.key.equals(k)) {
                return a;
            }
        }
        return null;
    }
}
