package dev.tearsmilk.aurahud.layout;

import java.util.Locale;

import static dev.tearsmilk.aurahud.layout.Direction.DOWN;
import static dev.tearsmilk.aurahud.layout.Direction.LEFT;
import static dev.tearsmilk.aurahud.layout.Direction.RIGHT;
import static dev.tearsmilk.aurahud.layout.Direction.UP;

public enum Element {
    HOTBAR("hotbar", "HOTBAR", Shape.ICONS, new Icons(9, 9, 22, 22, 20, 20, 9, 3, RIGHT, DOWN, DOWN, RIGHT), 182, 22, 0, 0),
    OFFHAND("offhand", "OFFHAND", Shape.BOX, null, 29, 24, -106, 1),
    HEARTS("hearts", "HEARTS", Shape.ICONS, new Icons(40, 10, 9, 9, 8, 10, 10, 5, RIGHT, UP, UP, RIGHT), 81, 9, -51, -30),
    ARMOR("armor", "ARMOR", Shape.ICONS, new Icons(10, 10, 9, 9, 8, 10, 10, 5, RIGHT, UP, UP, RIGHT), 81, 9, -51, -40),
    FOOD("food", "FOOD", Shape.ICONS, new Icons(10, 10, 9, 9, 8, 10, 10, 5, LEFT, UP, UP, LEFT), 81, 9, 50, -30),
    AIR("air", "AIR", Shape.ICONS, new Icons(10, 10, 9, 9, 8, 10, 10, 5, LEFT, UP, UP, LEFT), 81, 9, 50, -40),
    MOUNT("mount", "MOUNT", Shape.ICONS, new Icons(30, 10, 9, 9, 8, 10, 10, 5, LEFT, UP, UP, LEFT), 81, 9, 50, -30),
    XP("xp", "XP", Shape.BAR, null, 182, 5, 0, -24),
    ITEM_NAME("item-name", "NAME", Shape.POINT, null, 0, 0, 0, -59);

    public enum Shape {
        ICONS, BOX, BAR, POINT
    }

    public record Icons(int count, int base, int width, int height, int pitch, int linePitch, int perLine, int gridColumns,
                        Direction rowDirection, Direction rowWrap, Direction columnDirection, Direction columnWrap) {

        public Direction direction(Arrangement a) {
            return a == Arrangement.COLUMN ? columnDirection : rowDirection;
        }

        public Direction wrap(Arrangement a) {
            return a == Arrangement.COLUMN ? columnWrap : rowWrap;
        }

        public int perLine(Arrangement a) {
            return a == Arrangement.GRID ? gridColumns : perLine;
        }
    }

    private final String id;
    private final String glsl;
    private final Shape shape;
    private final Icons icons;
    private final int width;
    private final int height;
    private final int vanillaX;
    private final int vanillaY;

    Element(String id, String glsl, Shape shape, Icons icons, int width, int height, int vanillaX, int vanillaY) {
        this.id = id;
        this.glsl = glsl;
        this.shape = shape;
        this.icons = icons;
        this.width = width;
        this.height = height;
        this.vanillaX = vanillaX;
        this.vanillaY = vanillaY;
    }

    public String id() {
        return id;
    }

    public String glsl() {
        return glsl;
    }

    public Shape shape() {
        return shape;
    }

    public Icons icons() {
        return icons;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int vanillaX() {
        return vanillaX;
    }

    public int vanillaY() {
        return vanillaY;
    }

    public static Element parse(String s) {
        if (s == null) {
            return null;
        }
        String k = s.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        switch (k) {
            case "health", "heart" -> k = "hearts";
            case "hunger" -> k = "food";
            case "level", "experience", "exp" -> k = "xp";
            case "bubbles", "oxygen" -> k = "air";
            case "vehicle", "mount-health" -> k = "mount";
            case "name", "itemname", "held-item" -> k = "item-name";
            default -> {
            }
        }
        for (Element e : values()) {
            if (e.id.equals(k)) {
                return e;
            }
        }
        return null;
    }
}
