package ink.hoka.odyssey.gravity;

import ink.hoka.odyssey.api.ModuleConfig;
import ink.hoka.odyssey.spacephys.Body;
import ink.hoka.odyssey.spacephys.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Настоящая физика вакуума (разделы physics и bodies в config.yml). Единицы — секунды: блоки/с, блоки/с².
 * Физика — библиотека spacephys: поле карты {@link Field} из её тел (bodies) и чёрных дыр (black-holes).
 *
 * @param legacyMaps     карты из maps со старым поведением (тяга с затуханием, дрейф к дырам — {@link Floater})
 * @param bodies         тела по картам (как в конфиге, без дыр)
 * @param fields         поле карты: тела + дыры этой карты; карты без тел и дыр — {@link Field#EMPTY}
 * @param thrust         ускорение ранца, блоки/с² (0 — ранца нет)
 * @param deltaV         запас Δv ранца, блоки/с (бесконечность — без ограничения)
 * @param pushOff        скорость толчка от блока (пробел у стены/пола), блоки/с (0 — нельзя)
 * @param restitution    упругость удара о блок (0 — не отскакивает, 1 — как мячик)
 * @param friction       трение в момент удара (доля «съеденной» касательной скорости на единицу Δv)
 * @param impactThreshold удар слабее этого |Δv по нормали| не ранит, блоки/с
 * @param impactDamage   урон за каждый блок/с сверх порога (0 — без урона)
 * @param antiCheat      проверка скорости игроков с модом spaceflight
 * @param surfaceWalk    запас проверки у поверхности (ходьба по стенам и потолку в «магнитных ботинках»), блоки/с
 * @param speedReadout   показывать скорость в action bar
 * @param warnings       ошибки в разделе bodies (для лога)
 */
record RealPhysics(Set<String> legacyMaps, Map<String, List<Body>> bodies, Map<String, Field> fields, double thrust,
                   double deltaV, double pushOff, double restitution, double friction, double impactThreshold,
                   double impactDamage, boolean antiCheat, double surfaceWalk, boolean speedReadout, List<String> warnings) {

    static final String KEY = "bodies";

    /** Карта с настоящей физикой (а не старым поведением). */
    boolean real(String map) {
        return !legacyMaps.contains(map);
    }

    /** Поле карты (тела + дыры). */
    Field field(String map) {
        return fields.getOrDefault(map, Field.EMPTY);
    }

    static RealPhysics from(ModuleConfig c, Map<String, List<BlackHole>> holes) {
        List<String> warnings = new ArrayList<>();
        Map<String, List<Body>> bodies = bodies(c.get(KEY), warnings);
        double dv = c.getDouble("physics.thrust.delta-v", 60);
        return new RealPhysics(
            Collections.unmodifiableSet(new LinkedHashSet<>(c.getStringList("physics.legacy-maps"))),
            bodies,
            fields(bodies, holes),
            positive(c.getDouble("physics.thrust.acceleration", 2.0)),
            dv > 0 && Double.isFinite(dv) ? dv : Double.POSITIVE_INFINITY,
            positive(c.getDouble("physics.push-off", 2.5)),
            Math.min(1, positive(c.getDouble("physics.impact.restitution", 0.2))),
            positive(c.getDouble("physics.impact.friction", 0.3)),
            positive(c.getDouble("physics.impact.threshold", 8.0)),
            positive(c.getDouble("physics.impact.damage", 1.0)),
            c.getBoolean("physics.anti-cheat", true),
            Math.min(50, positive(c.getDouble("physics.surface-walk", 8.0))),
            c.getBoolean("physics.speed-readout", false),
            List.copyOf(warnings));
    }

    /** Поля карт: тела раздела bodies и дыры раздела black-holes (дыра — тело HOLE, см. {@link #hole}). */
    static Map<String, Field> fields(Map<String, List<Body>> bodies, Map<String, List<BlackHole>> holes) {
        Map<String, List<Body>> all = new LinkedHashMap<>();
        bodies.forEach((map, list) -> all.computeIfAbsent(map, k -> new ArrayList<>()).addAll(list));
        holes.forEach((map, list) -> {
            for (BlackHole h : list) {
                Body b = hole(h);
                if (b != null) {
                    all.computeIfAbsent(map, k -> new ArrayList<>()).add(b);
                }
            }
        });
        Map<String, Field> out = new LinkedHashMap<>();
        all.forEach((map, list) -> out.put(map, Field.of(list)));
        return Collections.unmodifiableMap(out);
    }

    /**
     * Дыра из black-holes как тело поля. В старом конфиге у дыры нет массы, есть pull — ускорение «у горизонта»;
     * берём μ = pull·r_s²: по закону Пачинского–Виита a = μ/(r − r_s)², так что на расстоянии 2·r_s от центра
     * ускорение ровно pull, дальше слабеет как 1/r². null — дыра без горизонта.
     */
    static Body hole(BlackHole h) {
        if (!(h.horizon() > 0)) {
            return null;
        }
        return Body.hole(h.name(), h.x(), h.y(), h.z(), h.pull() * h.horizon() * h.horizon(), h.horizon());
    }

    /**
     * Раздел bodies: {@code <карта>: <имя>: {type, x, y, z, radius, surface-gravity | mu, softening}}. Ключи берём
     * как есть (YAML читает {@code 1:} как число, {@code on:} — как true). Ошибочное тело пропускается с
     * предупреждением в warnings.
     */
    static Map<String, List<Body>> bodies(Object section, List<String> warnings) {
        Map<String, List<Body>> out = new LinkedHashMap<>();
        if (!(section instanceof Map<?, ?> byMap)) {
            return Collections.unmodifiableMap(out);
        }
        for (var me : byMap.entrySet()) {
            if (!(me.getValue() instanceof Map<?, ?> byName)) {
                continue;
            }
            String map = String.valueOf(me.getKey());
            List<Body> list = new ArrayList<>();
            for (var be : byName.entrySet()) {
                String name = String.valueOf(be.getKey());
                Map<?, ?> m = be.getValue() instanceof Map<?, ?> mm ? mm : Map.of();
                try {
                    list.add(body(name, m));
                } catch (IllegalArgumentException e) {
                    warnings.add("bodies." + map + "." + name + ": " + e.getMessage());
                }
            }
            if (!list.isEmpty()) {
                out.put(map, List.copyOf(list));
            }
        }
        return Collections.unmodifiableMap(out);
    }

    /** Одно тело из конфига. */
    static Body body(String name, Map<?, ?> m) {
        String type = m.get("type") == null ? "planet" : String.valueOf(m.get("type")).trim().toLowerCase(Locale.ROOT);
        double x = num(m, "x", 0);
        double y = num(m, "y", 0);
        double z = num(m, "z", 0);
        double radius = num(m, "radius", 0);
        double mu;
        if (m.get("mu") != null) {
            mu = num(m, "mu", Double.NaN);
        } else if (m.get("surface-gravity") != null) {
            if (!(radius > 0)) {
                throw new IllegalArgumentException("surface-gravity без radius > 0 — укажите radius или mu");
            }
            mu = num(m, "surface-gravity", Double.NaN) * radius * radius;
        } else {
            throw new IllegalArgumentException("нужно mu или surface-gravity");
        }
        return switch (type) {
            case "planet", "moon" -> Body.planet(name, x, y, z, mu, radius);
            case "star" -> Body.star(name, x, y, z, mu, radius);
            case "small", "station", "asteroid" ->
                // у малого тела radius — только для surface-gravity; закон — Пламмер со смягчением
                Body.small(name, x, y, z, mu, num(m, "softening", Math.max(1, radius)), 0);
            default -> throw new IllegalArgumentException("неизвестный type «" + type + "» (planet, star, small)");
        };
    }

    private static double num(Map<?, ?> m, String key, double def) {
        Object v = m.get(key);
        if (v == null) {
            return def;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " = «" + v + "»: нужно число");
        }
    }

    private static double positive(double v) {
        return Double.isFinite(v) ? Math.max(0, v) : 0;
    }
}
