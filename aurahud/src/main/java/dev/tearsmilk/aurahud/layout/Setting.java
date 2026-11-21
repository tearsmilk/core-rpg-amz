package dev.tearsmilk.aurahud.layout;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public enum Setting {
    ENABLED("enabled", Type.BOOL, null),
    ANCHOR("anchor", Type.ANCHOR, null),
    X("x", Type.INT, null),
    Y("y", Type.INT, null),
    LAYOUT("layout", Type.LAYOUT, Element.Shape.ICONS),
    PER_LINE("per-line", Type.COUNT, Element.Shape.ICONS),
    GAP("gap", Type.INT, Element.Shape.ICONS),
    LINE_GAP("line-gap", Type.INT, Element.Shape.ICONS),
    DIRECTION("direction", Type.DIRECTION, Element.Shape.ICONS),
    WRAP("wrap", Type.DIRECTION, Element.Shape.ICONS),
    REVERSE("reverse", Type.BOOL, Element.Shape.ICONS),
    ATTACK("attack-indicator", Type.BOOL, null),
    ATTACK_X("attack-x", Type.INT, null),
    ATTACK_Y("attack-y", Type.INT, null),
    FOLLOW("follow", Type.FOLLOW, null),
    ROTATE("rotate", Type.ROTATE, null),
    LEVEL("level", Type.BOOL, null),
    LEVEL_X("level-x", Type.INT, null),
    LEVEL_Y("level-y", Type.INT, null);

    public enum Type {
        BOOL, INT, COUNT, ANCHOR, LAYOUT, DIRECTION, FOLLOW, ROTATE
    }

    public static final int LIMIT = 4096;
    public static final int GAP_LIMIT = 256;

    private final String key;
    private final Type type;
    private final Element.Shape shape;

    Setting(String key, Type type, Element.Shape shape) {
        this.key = key;
        this.type = type;
        this.shape = shape;
    }

    public String key() {
        return key;
    }

    public Type type() {
        return type;
    }

    public boolean appliesTo(Element e) {
        return switch (this) {
            case ATTACK, ATTACK_X, ATTACK_Y -> e == Element.HOTBAR;
            case FOLLOW -> e == Element.OFFHAND;
            case ROTATE, LEVEL, LEVEL_X, LEVEL_Y -> e == Element.XP;
            default -> shape == null || shape == e.shape();
        };
    }

    public static Set<Setting> of(Element e) {
        Set<Setting> out = EnumSet.noneOf(Setting.class);
        for (Setting s : values()) {
            if (s.appliesTo(e)) {
                out.add(s);
            }
        }
        return out;
    }

    public static Setting parse(String s) {
        if (s == null) {
            return null;
        }
        String k = s.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if (k.equals("columns")) {
            k = "per-line";
        }
        if (k.equals("attack")) {
            k = "attack-indicator";
        }
        for (Setting v : values()) {
            if (v.key.equals(k)) {
                return v;
            }
        }
        return null;
    }

    public Object value(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        return switch (type) {
            case BOOL -> switch (v) {
                case "true", "yes", "on", "1", "да" -> Boolean.TRUE;
                case "false", "no", "off", "0", "нет" -> Boolean.FALSE;
                default -> null;
            };
            case INT -> integer(v, this == GAP || this == LINE_GAP ? GAP_LIMIT : LIMIT, this == GAP || this == LINE_GAP ? -GAP_LIMIT : -LIMIT);
            case COUNT -> integer(v, 40, 1);
            case ANCHOR -> {
                Anchor a = Anchor.parse(v);
                yield a == null ? null : a.key();
            }
            case LAYOUT -> {
                Arrangement a = Arrangement.parse(v);
                yield a == null ? null : a.key();
            }
            case DIRECTION -> {
                Direction d = Direction.parse(v);
                yield d == null ? null : d.key();
            }
            case FOLLOW -> v.equals("hotbar") || v.equals("none") ? v : null;
            case ROTATE -> {
                Integer r = integer(v, 270, 0);
                yield r != null && r % 90 == 0 ? r : null;
            }
        };
    }

    public List<String> suggestions() {
        List<String> out = new ArrayList<>();
        switch (type) {
            case BOOL -> out.addAll(List.of("true", "false"));
            case INT -> out.addAll(List.of("0", "-10", "10"));
            case COUNT -> out.addAll(List.of("1", "3", "5", "9", "10"));
            case ANCHOR -> {
                for (Anchor a : Anchor.values()) {
                    out.add(a.key());
                }
            }
            case LAYOUT -> {
                for (Arrangement a : Arrangement.values()) {
                    out.add(a.key());
                }
            }
            case DIRECTION -> {
                for (Direction d : Direction.values()) {
                    out.add(d.key());
                }
            }
            case FOLLOW -> out.addAll(List.of("hotbar", "none"));
            case ROTATE -> out.addAll(List.of("0", "90", "180", "270"));
        }
        return out;
    }

    private static Integer integer(String v, int max, int min) {
        try {
            int i = Integer.parseInt(v.startsWith("+") ? v.substring(1) : v);
            return i < min || i > max ? null : i;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
