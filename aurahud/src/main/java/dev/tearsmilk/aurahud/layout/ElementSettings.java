package dev.tearsmilk.aurahud.layout;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public record ElementSettings(Element element, boolean enabled, Anchor anchor, int x, int y, Arrangement layout, int perLine,
                              int gap, int lineGap, Direction direction, Direction wrap, Map<Integer, Slot> slots,
                              boolean attack, int attackX, int attackY, boolean follow, int rotate, boolean level, int levelX, int levelY) {

    public static final int SLOTS = 9;

    public record Slot(int x, int y, boolean hidden) {
    }

    public ElementSettings {
        slots = Collections.unmodifiableMap(new TreeMap<>(slots));
    }

    public static ElementSettings defaults(Element e) {
        Element.Icons ic = e.icons();
        return new ElementSettings(e, true, Anchor.BOTTOM_CENTER, e.vanillaX(), e.vanillaY(), Arrangement.ROW,
                ic == null ? 1 : ic.perLine(Arrangement.ROW), 0, 0,
                ic == null ? Direction.RIGHT : ic.direction(Arrangement.ROW), ic == null ? Direction.UP : ic.wrap(Arrangement.ROW),
                Map.of(), true, 0, 0, true, 0, true, 0, 0);
    }

    public static ElementSettings read(Element e, ConfigurationSection sec, List<String> warnings) {
        ElementSettings d = defaults(e);
        if (sec == null) {
            return d;
        }
        String where = "hud." + e.id() + ".";
        for (String key : sec.getKeys(false)) {
            Setting s = Setting.parse(key);
            if (key.equals("slots") && e == Element.HOTBAR) {
                continue;
            }
            if (s == null || !s.appliesTo(e)) {
                warnings.add(where + key + ": неизвестный ключ, пропущен");
            }
        }
        boolean enabled = bool(sec, Setting.ENABLED, true, where, warnings);
        Anchor anchor = d.anchor();
        boolean anchored = sec.contains("anchor");
        if (anchored) {
            Anchor a = Anchor.parse(sec.getString("anchor"));
            if (a == null) {
                warnings.add(where + "anchor: \"" + sec.getString("anchor") + "\" - не якорь, взят bottom-center");
                anchored = false;
            } else {
                anchor = a;
            }
        }
        boolean moved = anchored && anchor != Anchor.BOTTOM_CENTER;
        int x = integer(sec, Setting.X, moved ? 0 : d.x(), where, warnings);
        int y = integer(sec, Setting.Y, moved ? 0 : d.y(), where, warnings);
        Arrangement layout = Arrangement.ROW;
        int perLine = d.perLine();
        int gap = 0;
        int lineGap = 0;
        Direction dir = d.direction();
        Direction wrap = d.wrap();
        Map<Integer, Slot> slots = new TreeMap<>();
        Element.Icons ic = e.icons();
        if (ic != null) {
            if (sec.contains("layout")) {
                Arrangement a = Arrangement.parse(sec.getString("layout"));
                if (a == null) {
                    warnings.add(where + "layout: \"" + sec.getString("layout") + "\" - можно row, column, grid; взят row");
                } else {
                    layout = a;
                }
            }
            perLine = Math.min(ic.count(), integer(sec, Setting.PER_LINE, ic.perLine(layout), where, warnings));
            gap = integer(sec, Setting.GAP, 0, where, warnings);
            lineGap = integer(sec, Setting.LINE_GAP, 0, where, warnings);
            dir = direction(sec, Setting.DIRECTION, ic.direction(layout), where, warnings);
            Direction w = direction(sec, Setting.WRAP, null, where, warnings);
            if (w == null) {
                w = ic.wrap(layout);
                if (!w.perpendicular(dir)) {
                    w = dir.dx() != 0 ? ic.rowWrap() : (ic.rowDirection() == Direction.LEFT ? Direction.LEFT : Direction.RIGHT);
                }
            } else if (!w.perpendicular(dir)) {
                Direction fix = dir.dx() != 0 ? (ic.rowWrap().dy() != 0 ? ic.rowWrap() : Direction.UP) : Direction.RIGHT;
                warnings.add(where + "wrap: " + w.key() + " не поперек direction " + dir.key() + ", взят " + fix.key());
                w = fix;
            }
            wrap = w;
            if (bool(sec, Setting.REVERSE, false, where, warnings)) {
                dir = dir.opposite();
            }
            if (e == Element.HOTBAR) {
                slots = slots(sec.getConfigurationSection("slots"), where, warnings);
            }
        }
        boolean attack = e != Element.HOTBAR || bool(sec, Setting.ATTACK, true, where, warnings);
        int attackX = e == Element.HOTBAR ? integer(sec, Setting.ATTACK_X, 0, where, warnings) : 0;
        int attackY = e == Element.HOTBAR ? integer(sec, Setting.ATTACK_Y, 0, where, warnings) : 0;
        boolean follow = true;
        if (e == Element.OFFHAND) {
            boolean placed = sec.contains("anchor") || sec.contains("x") || sec.contains("y");
            String f = sec.getString("follow", placed ? "none" : "hotbar");
            Object v = Setting.FOLLOW.value(f);
            if (v == null) {
                warnings.add(where + "follow: \"" + f + "\" - можно hotbar или none");
                v = placed ? "none" : "hotbar";
            }
            follow = v.equals("hotbar");
        }
        int rotate = 0;
        boolean level = true;
        int levelX = 0;
        int levelY = 0;
        if (e == Element.XP) {
            String rk = Setting.ROTATE.key();
            if (sec.contains(rk)) {
                Object r = Setting.ROTATE.value(String.valueOf(sec.get(rk)));
                if (r == null) {
                    warnings.add(where + rk + ": \"" + sec.get(rk) + "\" - можно 0, 90, 180, 270");
                } else {
                    rotate = (Integer) r;
                }
            }
            level = bool(sec, Setting.LEVEL, true, where, warnings);
            levelX = integer(sec, Setting.LEVEL_X, 0, where, warnings);
            levelY = integer(sec, Setting.LEVEL_Y, 0, where, warnings);
        }
        return new ElementSettings(e, enabled, anchor, x, y, layout, perLine, gap, lineGap, dir, wrap, slots, attack, attackX, attackY, follow,
                rotate, level, levelX, levelY);
    }

    private static Map<Integer, Slot> slots(ConfigurationSection sec, String where, List<String> warnings) {
        Map<Integer, Slot> out = new TreeMap<>();
        if (sec == null) {
            return out;
        }
        for (String key : sec.getKeys(false)) {
            int n;
            try {
                n = Integer.parseInt(key.trim());
            } catch (NumberFormatException ex) {
                n = -1;
            }
            ConfigurationSection s = sec.getConfigurationSection(key);
            if (n < 1 || n > SLOTS || s == null) {
                warnings.add(where + "slots." + key + ": номер слота 1-9 с ключами x, y, hidden");
                continue;
            }
            String w = where + "slots." + key + ".";
            Slot slot = new Slot(integer(s, Setting.X, 0, w, warnings), integer(s, Setting.Y, 0, w, warnings),
                    s.contains("hidden") && bool(s, "hidden", false, w, warnings));
            if (slot.x() != 0 || slot.y() != 0 || slot.hidden()) {
                out.put(n - 1, slot);
            }
        }
        return out;
    }

    private static boolean bool(ConfigurationSection sec, Setting s, boolean def, String where, List<String> warnings) {
        return bool(sec, s.key(), def, where, warnings);
    }

    private static boolean bool(ConfigurationSection sec, String key, boolean def, String where, List<String> warnings) {
        if (!sec.contains(key)) {
            return def;
        }
        Object v = Setting.ENABLED.value(String.valueOf(sec.get(key)));
        if (v == null) {
            warnings.add(where + key + ": \"" + sec.get(key) + "\" - нужно true или false");
            return def;
        }
        return (Boolean) v;
    }

    private static int integer(ConfigurationSection sec, Setting s, int def, String where, List<String> warnings) {
        if (!sec.contains(s.key())) {
            return def;
        }
        Object v = s.value(String.valueOf(sec.get(s.key())));
        if (v == null) {
            warnings.add(where + s.key() + ": \"" + sec.get(s.key()) + "\" - нужно целое число в допустимых пределах");
            return def;
        }
        return (Integer) v;
    }

    private static Direction direction(ConfigurationSection sec, Setting s, Direction def, String where, List<String> warnings) {
        if (!sec.contains(s.key())) {
            return def;
        }
        Direction d = Direction.parse(sec.getString(s.key()));
        if (d == null) {
            warnings.add(where + s.key() + ": \"" + sec.getString(s.key()) + "\" - можно right, left, up, down");
            return def;
        }
        return d;
    }
}
