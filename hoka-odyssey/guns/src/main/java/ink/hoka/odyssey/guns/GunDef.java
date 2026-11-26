package ink.hoka.odyssey.guns;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Описание одного оружия из config.yml (раздел {@code guns.<id>}). Неизменяемое: после {@code homodule reload guns}
 * читается заново. Значения по умолчанию — АКМ из пака TACZ (числа из его ak47_data.json).
 */
record GunDef(
        String id,
        String name,
        List<String> lore,
        String item,
        String material,
        String ammoItem,
        String ammoMaterial,
        String ammoName,
        String caliber,
        boolean infiniteAmmo,
        int magazine,
        boolean chamber,
        double rpm,
        boolean auto,
        boolean semi,
        double[] damageDistance,
        double[] damage,
        double headshot,
        double speed,
        double life,
        double gravity,
        double friction,
        int pierce,
        double knockback,
        int tracerColor,
        double spreadStand,
        double spreadMove,
        double spreadSneak,
        double spreadAim,
        double spreadVacuum,
        List<RecoilKey> recoilPitch,
        List<RecoilKey> recoilYaw,
        Reload tactical,
        Reload empty,
        double draw,
        double aimTime,
        double zoom,
        double aimSpeed,
        String model,
        String hud,
        String flash,
        String playerAnimation,
        Map<String, String> sounds,
        Map<Double, String> reloadSoundsTactical,
        Map<Double, String> reloadSoundsEmpty) {

    /** Ключевая точка отдачи: время (с) и пределы случайного значения (градусы). */
    record RecoilKey(double time, double min, double max) {}

    /** Перезарядка: feed — патроны в магазине, total — можно стрелять (секунды от начала). */
    record Reload(double feed, double total) {}

    /** Промежуток между выстрелами, мс. */
    double intervalMs() {
        return 60000.0 / rpm;
    }

    /** Сколько патронов вмещает оружие сейчас: магазин и (если в патроннике остался патрон) ещё один. */
    int capacity(int ammoNow) {
        return magazine + (chamber && ammoNow > 0 ? 1 : 0);
    }

    /** Урон на расстоянии dist (блоков) — по полосам damage. */
    double damageAt(double dist) {
        for (int i = 0; i < damageDistance.length; i++) {
            if (dist < damageDistance[i]) {
                return damage[i];
            }
        }
        return damage.length == 0 ? 0 : damage[damage.length - 1];
    }

    String sound(String key) {
        return sounds.getOrDefault(key, "");
    }

    /** Чтение из config.yml: base — "guns.ak47.". Нет значения — число TACZ. */
    static GunDef read(String id, Cfg c) {
        String b = "guns." + id + ".";
        List<String> modes = c.list(b + "modes");
        boolean auto = modes.isEmpty() || modes.contains("auto");
        boolean semi = modes.isEmpty() || modes.contains("semi");
        if (!auto && !semi) {
            auto = true;
        }
        List<Map<?, ?>> dmg = c.maps(b + "damage");
        double[] dd;
        double[] dv;
        if (dmg.isEmpty()) {
            dd = new double[] {30, 60, Double.POSITIVE_INFINITY};
            dv = new double[] {9, 7.5, 6};
        } else {
            dd = new double[dmg.size()];
            dv = new double[dmg.size()];
            for (int i = 0; i < dmg.size(); i++) {
                dd[i] = num(dmg.get(i).get("distance"), Double.POSITIVE_INFINITY);
                dv[i] = Math.max(0, num(dmg.get(i).get("damage"), 0));
            }
        }
        Map<String, String> sounds = new LinkedHashMap<>();
        sounds.put("shoot", "ak47_shoot");
        sounds.put("shoot3p", "ak47_shoot_3p");
        sounds.put("draw", "ak47_draw");
        sounds.put("put_away", "ak47_put_away");
        sounds.put("dry_fire", "gun_dry_fire");
        sounds.put("fire_select", "gun_fire_select");
        sounds.put("flesh_hit", "gun_flesh_hit");
        sounds.put("head_hit", "gun_head_hit");
        sounds.put("kill", "gun_kill");
        for (String k : c.keys(b + "sounds")) {
            sounds.put(k, c.str(b + "sounds." + k, ""));
        }
        List<String> lore = c.list(b + "lore");
        return new GunDef(id,
                c.str(b + "name", id.toUpperCase(Locale.ROOT)),
                lore,
                c.str(b + "item", id),
                c.str(b + "material", "iron_horse_armor"),
                c.str(b + "ammo-item", "ammo_762x39"),
                c.str(b + "ammo-material", "gold_nugget"),
                c.str(b + "ammo-name", "Патроны"),
                c.str(b + "caliber", "762x39"),
                c.bool(b + "infinite-ammo", false),
                clamp(c.integer(b + "magazine", 30), 1, 1000),
                c.bool(b + "chamber", true),
                clamp(c.dbl(b + "rpm", 600), 1, 3000),
                auto, semi, dd, dv,
                Math.max(0, c.dbl(b + "headshot", 1.5)),
                clamp(c.dbl(b + "bullet.speed", 500), 1, 5000),
                clamp(c.dbl(b + "bullet.life", 0.8), 0.05, 30),
                c.dbl(b + "bullet.gravity", 0.15),
                clamp(c.dbl(b + "bullet.friction", 0.02), 0, 0.99),
                clamp(c.integer(b + "bullet.pierce", 1), 1, 16),
                Math.max(0, c.dbl(b + "bullet.knockback", 0)),
                color(c.str(b + "bullet.tracer", "#FF8888"), 0xFF8888),
                Math.max(0, c.dbl(b + "spread.stand", 4.5)),
                Math.max(0, c.dbl(b + "spread.move", 5)),
                Math.max(0, c.dbl(b + "spread.sneak", 2.5)),
                Math.max(0, c.dbl(b + "spread.aim", 0.15)),
                Math.max(0, c.dbl(b + "spread.vacuum", 4.5)),
                recoil(c, b + "recoil.pitch", List.of(new RecoilKey(0, 0.8, 0.8), new RecoilKey(0.1, 1.4, 1.4),
                        new RecoilKey(0.15, 0.8, 0.8), new RecoilKey(0.45, -0.175, -0.175), new RecoilKey(0.6, 0, 0))),
                recoil(c, b + "recoil.yaw", List.of(new RecoilKey(0, -0.23, 0.18), new RecoilKey(0.35, 0, 0))),
                reload(c, b + "reload.tactical", 1.55, 2.0),
                reload(c, b + "reload.empty", 2.25, 2.6),
                clamp(c.dbl(b + "draw", 0.35), 0, 10),
                clamp(c.dbl(b + "aim-time", 0.22), 0.01, 5),
                clamp(c.dbl(b + "zoom", 1.33), 1, 1.9),
                clamp(c.dbl(b + "aim-speed", 0.8), 0, 1),
                c.str(b + "model", "guns/" + id + ".bbmodel"),
                c.str(b + "hud", "guns/" + id + "_hud.png"),
                c.str(b + "flash", "guns/common_muzzle_flash.png"),
                c.str(b + "player-animation", "guns/rifle_default.player_animation.json"),
                Map.copyOf(sounds),
                timeline(c, b + "reload-sounds-3p.tactical"),
                timeline(c, b + "reload-sounds-3p.empty"));
    }

    private static List<RecoilKey> recoil(Cfg c, String path, List<RecoilKey> def) {
        List<Map<?, ?>> l = c.maps(path);
        if (l.isEmpty()) {
            return def;
        }
        List<RecoilKey> out = new ArrayList<>();
        for (Map<?, ?> m : l) {
            double t = num(m.get("time"), 0);
            double lo = num(m.get("min"), 0);
            double hi = num(m.get("max"), lo);
            out.add(new RecoilKey(Math.max(0, t), Math.min(lo, hi), Math.max(lo, hi)));
        }
        out.sort((a, d) -> Double.compare(a.time(), d.time()));
        return List.copyOf(out);
    }

    private static Reload reload(Cfg c, String path, double feed, double total) {
        double f = clamp(c.dbl(path + ".feed", feed), 0, 30);
        double t = clamp(c.dbl(path + ".total", total), f, 30);
        return new Reload(f, t);
    }

    private static Map<Double, String> timeline(Cfg c, String path) {
        Map<Double, String> out = new TreeMap<>();
        if (!(c.get(path) instanceof Map<?, ?> m)) {
            return out;
        }
        for (Map.Entry<?, ?> e : m.entrySet()) {                 // ключи «0.35» с точкой — не через путь
            double t = num(e.getKey(), Double.NaN);
            if (!Double.isNaN(t) && e.getValue() != null) {
                out.put(t, String.valueOf(e.getValue()));
            }
        }
        return out;
    }

    static int color(String s, int def) {
        if (s == null) {
            return def;
        }
        String t = s.trim();
        if (t.startsWith("#")) {
            t = t.substring(1);
        }
        try {
            return Integer.parseInt(t, 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    static double num(Object o, double def) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o instanceof String s) {
            String t = s.trim().toLowerCase(Locale.ROOT);
            if (t.equals("inf") || t.equals("infinity") || t.equals("∞")) {
                return Double.POSITIVE_INFINITY;
            }
            try {
                return Double.parseDouble(t);
            } catch (NumberFormatException e) {
                return def;
            }
        }
        return def;
    }

    static double clamp(double v, double lo, double hi) {
        return Double.isNaN(v) ? lo : Math.max(lo, Math.min(hi, v));
    }

    static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Доступ к настройкам (ModuleConfig модуля или карта в тестах). */
    interface Cfg {
        Object get(String path);

        default String str(String path, String def) {
            Object o = get(path);
            return o == null ? def : String.valueOf(o);
        }

        default double dbl(String path, double def) {
            return num(get(path), def);
        }

        default int integer(String path, int def) {
            return get(path) instanceof Number n ? n.intValue() : def;
        }

        default boolean bool(String path, boolean def) {
            return get(path) instanceof Boolean b ? b : def;
        }

        default List<String> list(String path) {
            if (!(get(path) instanceof List<?> l)) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            for (Object x : l) {
                out.add(String.valueOf(x));
            }
            return out;
        }

        default List<Map<?, ?>> maps(String path) {
            if (!(get(path) instanceof List<?> l)) {
                return List.of();
            }
            List<Map<?, ?>> out = new ArrayList<>();
            for (Object x : l) {
                if (x instanceof Map<?, ?> m) {
                    out.add(m);
                }
            }
            return out;
        }

        default List<String> keys(String path) {
            if (!(get(path) instanceof Map<?, ?> m)) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            for (Object k : m.keySet()) {
                out.add(String.valueOf(k));
            }
            return out;
        }

        /** Настройки из вложенных карт YAML (как ModuleConfig.get). */
        static Cfg of(Map<?, ?> root) {
            return path -> {
                Object cur = root;
                for (String k : path.split("\\.")) {
                    if (!(cur instanceof Map<?, ?> m)) {
                        return null;
                    }
                    cur = m.get(k);
                }
                return cur;
            };
        }
    }
}
