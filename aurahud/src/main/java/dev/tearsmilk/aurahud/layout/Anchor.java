package dev.tearsmilk.aurahud.layout;

import java.util.Locale;

public enum Anchor {
    TOP_LEFT("top-left", 0, 0),
    TOP_CENTER("top-center", 1, 0),
    TOP_RIGHT("top-right", 2, 0),
    CENTER_LEFT("center-left", 0, 1),
    CENTER("center", 1, 1),
    CENTER_RIGHT("center-right", 2, 1),
    BOTTOM_LEFT("bottom-left", 0, 2),
    BOTTOM_CENTER("bottom-center", 1, 2),
    BOTTOM_RIGHT("bottom-right", 2, 2);

    private final String key;
    private final int hx;
    private final int vy;

    Anchor(String key, int hx, int vy) {
        this.key = key;
        this.hx = hx;
        this.vy = vy;
    }

    public String key() {
        return key;
    }

    public static int part(int size, int third) {
        return third == 0 ? 0 : third == 1 ? Math.floorDiv(size, 2) : size;
    }

    public int x(int width) {
        return part(width, hx);
    }

    public int y(int height) {
        return part(height, vy);
    }

    public float fx() {
        return hx * 0.5f;
    }

    public float fy() {
        return vy * 0.5f;
    }

    public static Anchor parse(String s) {
        if (s == null) {
            return null;
        }
        String k = s.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if (k.equals("middle")) {
            return CENTER;
        }
        for (Anchor a : values()) {
            if (a.key.equals(k)) {
                return a;
            }
        }
        return null;
    }
}
