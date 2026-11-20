package dev.tearsmilk.tabshader;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

public final class PositionCode {

    public static final int PLACE_R = 232;
    public static final int WIDTH_R = 233;
    public static final int HEIGHT_R = 234;
    public static final int MAX_MARGIN = 255;
    public static final int MAX_VALUE = 4095;

    public enum Element {
        TAB(0),
        BOARD(1);

        private final int id;

        Element(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }

        public static Element byId(int id) {
            for (Element e : values()) {
                if (e.id == id) {
                    return e;
                }
            }
            return null;
        }
    }

    public enum Position {
        CENTER(0, "center"),
        TOP_LEFT(1, "top-left"),
        TOP_RIGHT(2, "top-right"),
        BOTTOM_LEFT(3, "bottom-left"),
        BOTTOM_RIGHT(4, "bottom-right"),
        LEFT(5, "left"),
        RIGHT(6, "right");

        private final int id;
        private final String key;

        Position(int id, String key) {
            this.id = id;
            this.key = key;
        }

        public int id() {
            return id;
        }

        public String key() {
            return key;
        }

        public static Position byId(int id) {
            for (Position p : values()) {
                if (p.id == id) {
                    return p;
                }
            }
            return null;
        }
    }

    public static final Set<Position> TAB_POSITIONS = EnumSet.of(Position.CENTER, Position.TOP_LEFT,
            Position.TOP_RIGHT, Position.BOTTOM_LEFT, Position.BOTTOM_RIGHT);
    public static final Set<Position> BOARD_POSITIONS = EnumSet.of(Position.TOP_LEFT, Position.TOP_RIGHT,
            Position.BOTTOM_LEFT, Position.BOTTOM_RIGHT, Position.LEFT, Position.RIGHT);

    private PositionCode() {
    }

    public static Position parse(String s, Element element, Position def) {
        if (s == null) {
            return def;
        }
        String k = s.trim().toLowerCase(Locale.ROOT).replace('_', '-').replace(' ', '-');
        Set<Position> allowed = element == Element.TAB ? TAB_POSITIONS : BOARD_POSITIONS;
        for (Position p : allowed) {
            if (p.key.equals(k)) {
                return p;
            }
        }
        return def;
    }

    public static int clampMargin(int margin) {
        return Math.max(0, Math.min(MAX_MARGIN, margin));
    }

    public static int clampValue(int value) {
        return Math.max(0, Math.min(MAX_VALUE, value));
    }

    public static int place(Element element, Position position, int margin) {
        int g = (element.id() << 4) | position.id();
        return (PLACE_R << 16) | (g << 8) | clampMargin(margin);
    }

    public static int width(Element element, int width) {
        return sized(WIDTH_R, element, width);
    }

    public static int height(Element element, int height) {
        return sized(HEIGHT_R, element, height);
    }

    private static int sized(int r, Element element, int value) {
        int v = clampValue(value);
        int g = (element.id() << 4) | (v >> 8);
        return (r << 16) | (g << 8) | (v & 255);
    }

    public static int red(int rgb) {
        return (rgb >> 16) & 255;
    }

    public static boolean isCode(int rgb) {
        int r = red(rgb);
        return (r == PLACE_R || r == WIDTH_R || r == HEIGHT_R) && element(rgb) != null
                && (r != PLACE_R || position(rgb) != null);
    }

    public static Element element(int rgb) {
        return Element.byId(((rgb >> 8) & 255) >> 4);
    }

    public static Position position(int rgb) {
        if (red(rgb) != PLACE_R) {
            return null;
        }
        return Position.byId((rgb >> 8) & 15);
    }

    public static int margin(int rgb) {
        return rgb & 255;
    }

    public static int value(int rgb) {
        return (((rgb >> 8) & 15) << 8) | (rgb & 255);
    }
}
