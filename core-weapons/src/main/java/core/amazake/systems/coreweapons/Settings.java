package core.amazake.systems.coreweapons;

import core.amazake.systems.coreweapons.logic.Frames;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;

/** Неизменяемый снимок config.yml (подменяется целиком при перечитывании). */
public record Settings(String prefix, Map<String, Weapon> weapons, Map<String, String> messages) {

    public static final Settings EMPTY = new Settings("", Map.of(), Map.of());

    /** Звук «ключ:громкость:высота» (ключ — как в /playsound, можно с minecraft:). */
    public record SoundSpec(String key, float volume, float pitch) {

        public static SoundSpec parse(final String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            final String[] p = raw.trim().split(":");
            float vol = 1f;
            float pitch = 1f;
            int keyParts = p.length;
            if (p.length >= 3 && isNum(p[p.length - 1]) && isNum(p[p.length - 2])) {
                vol = Float.parseFloat(p[p.length - 2]);
                pitch = Float.parseFloat(p[p.length - 1]);
                keyParts -= 2;
            } else if (p.length >= 2 && isNum(p[p.length - 1])) {
                vol = Float.parseFloat(p[p.length - 1]);
                keyParts -= 1;
            }
            final String key = String.join(":", java.util.Arrays.copyOf(p, keyParts)).toLowerCase(Locale.ROOT);
            return key.isEmpty() ? null : new SoundSpec(key, vol, pitch);
        }

        private static boolean isNum(final String s) {
            try {
                Float.parseFloat(s);
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }
    }

    public record TestItem(String material, String itemModel, String name) {
    }

    public record Orbit(int period, int points, double radius, double degreesPerTick, double tilt, double bob,
                        double right, double up, double forward, int colorFrom, int colorTo, float size,
                        int flameEvery, boolean handEmber) {
    }

    public record Swing(Frames frames, int minInterval, boolean trail, int trailPoints, double trailRadius,
                        double trailDistance, int colorFrom, int colorTo, SoundSpec sound) {
    }

    public record Tremor(double radius, int duration, int cooldown, int particles, boolean dustPillar, boolean shake,
                         double shakeHeight, int maxDisplays, double kick, boolean kickAttacker, double damage,
                         List<SoundSpec> sounds) {
    }

    /** Оружие: любое поведение может быть null (выключено). */
    public record Weapon(String id, TestItem testItem, Orbit orbit, Swing swing, Tremor tremor) {
    }

    public Weapon weapon(final String id) {
        return id == null ? null : this.weapons.get(id);
    }

    public static Settings from(final ConfigurationSection c, final Consumer<String> warn) {
        final Map<String, Weapon> weapons = new LinkedHashMap<>();
        final ConfigurationSection ws = c.getConfigurationSection("weapons");
        if (ws != null) {
            for (final String id : ws.getKeys(false)) {
                final ConfigurationSection w = ws.getConfigurationSection(id);
                if (w == null) {
                    warn.accept("weapons." + id + ": не секция");
                    continue;
                }
                weapons.put(id, weapon(id, w, m -> warn.accept("weapons." + id + "." + m)));
            }
        }
        final Map<String, String> messages = new LinkedHashMap<>();
        final ConfigurationSection ms = c.getConfigurationSection("messages");
        if (ms != null) {
            for (final String k : ms.getKeys(false)) {
                messages.put(k, ms.getString(k, ""));
            }
        }
        return new Settings(c.getString("prefix", ""), Map.copyOf(weapons), Map.copyOf(messages));
    }

    private static Weapon weapon(final String id, final ConfigurationSection w, final Consumer<String> warn) {
        TestItem test = null;
        final ConfigurationSection t = w.getConfigurationSection("test-item");
        if (t != null) {
            test = new TestItem(t.getString("material", "NETHERITE_SWORD").toUpperCase(Locale.ROOT),
                t.getString("item-model", ""), t.getString("name", id));
        }
        Orbit orbit = null;
        final ConfigurationSection o = w.getConfigurationSection("orbit");
        if (o != null && o.getBoolean("enabled", true)) {
            final List<Double> center = doubles(o.getList("center"), List.of(0.45, 2.15, 0.45));
            final List<String> colors = o.getStringList("colors");
            orbit = new Orbit(Math.max(1, o.getInt("period", 2)), clamp(o.getInt("points", 3), 1, 12),
                o.getDouble("radius", 0.75), o.getDouble("degrees-per-tick", 14), o.getDouble("tilt", 25),
                o.getDouble("bob", 0.12), center.get(0), center.get(1), center.get(2),
                color(colors, 0, 0xFF3B2F, warn), color(colors, 1, 0xFF9A3D, warn),
                (float) Math.max(0.1, Math.min(4.0, o.getDouble("size", 0.75))), Math.max(0, o.getInt("flame-every", 2)),
                o.getBoolean("hand-ember", true));
        }
        Swing swing = null;
        final ConfigurationSection s = w.getConfigurationSection("swing");
        if (s != null && s.getBoolean("enabled", true)) {
            final Frames frames = Frames.parse(s.getStringList("frames"), m -> warn.accept("swing.frames: " + m));
            final List<String> colors = s.getStringList("trail-colors");
            swing = new Swing(frames, Math.max(1, s.getInt("min-interval", 6)), s.getBoolean("trail", true),
                clamp(s.getInt("trail-points", 5), 1, 20), s.getDouble("trail-radius", 1.35),
                s.getDouble("trail-distance", 1.25), color(colors, 0, 0xFF2A1F, warn), color(colors, 1, 0xFFB347, warn),
                SoundSpec.parse(s.getString("sound", "")));
        }
        Tremor tremor = null;
        final ConfigurationSection r = w.getConfigurationSection("crit-tremor");
        if (r != null && r.getBoolean("enabled", true)) {
            final List<SoundSpec> sounds = new ArrayList<>();
            for (final String raw : r.getStringList("sounds")) {
                final SoundSpec sp = SoundSpec.parse(raw);
                if (sp != null) {
                    sounds.add(sp);
                }
            }
            tremor = new Tremor(Math.max(1.0, Math.min(16.0, r.getDouble("radius", 6))),
                clamp(r.getInt("duration", 12), 2, 60), Math.max(0, r.getInt("cooldown", 30)),
                clamp(r.getInt("particles", 3), 0, 20), r.getBoolean("dust-pillar", true),
                r.getBoolean("shake-blocks", true), Math.max(0, Math.min(1.0, r.getDouble("shake-height", 0.22))),
                clamp(r.getInt("max-displays", 90), 0, 400), Math.max(0, Math.min(1.5, r.getDouble("kick", 0.12))),
                r.getBoolean("kick-attacker", false), Math.max(0, r.getDouble("damage", 0)), List.copyOf(sounds));
        }
        return new Weapon(id, test, orbit, swing, tremor);
    }

    static int clamp(final int v, final int lo, final int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static List<Double> doubles(final List<?> raw, final List<Double> def) {
        if (raw == null || raw.size() != def.size()) {
            return def;
        }
        final List<Double> out = new ArrayList<>();
        for (final Object o : raw) {
            if (!(o instanceof Number n)) {
                return def;
            }
            out.add(n.doubleValue());
        }
        return out;
    }

    static int color(final List<String> list, final int i, final int def, final Consumer<String> warn) {
        if (list == null || list.size() <= i) {
            return def;
        }
        final String s = list.get(i).trim().replace("#", "");
        try {
            return Integer.parseInt(s, 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            warn.accept("цвет «" + list.get(i) + "» не понят, взят " + Integer.toHexString(def));
            return def;
        }
    }
}
