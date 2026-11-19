package dev.tearsmilk.coreshaders;

import java.util.Locale;

public enum Shape {
    CUBE,
    PLANE;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public Shape next() {
        return this == CUBE ? PLANE : CUBE;
    }

    public static Shape parse(String s) {
        if (s == null) {
            return null;
        }
        String v = s.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "cube", "куб", "block", "блок" -> CUBE;
            case "plane", "плоскость", "panel", "портал", "portal" -> PLANE;
            default -> null;
        };
    }
}
