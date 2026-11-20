package dev.tearsmilk.chatshader;

import java.util.Locale;

public enum Position {
    BOTTOM_LEFT("bottom-left", 0),
    TOP_LEFT("top-left", 1),
    TOP_RIGHT("top-right", 2),
    BOTTOM_RIGHT("bottom-right", 3);

    private final String key;
    private final int code;

    Position(String key, int code) {
        this.key = key;
        this.code = code;
    }

    public String key() {
        return key;
    }

    public int code() {
        return code;
    }

    public static Position parse(String s) {
        if (s == null) {
            return null;
        }
        String k = s.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (Position p : values()) {
            if (p.key.equals(k)) {
                return p;
            }
        }
        return null;
    }
}
