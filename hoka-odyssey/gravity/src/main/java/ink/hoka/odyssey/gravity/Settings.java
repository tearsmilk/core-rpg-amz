package ink.hoka.odyssey.gravity;

import ink.hoka.odyssey.api.ModuleConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Настройки из config.yml, уже в единицах тика (блоки/тик, блоки/тик²) — как их считает клиент.
 *
 * @param maps         карты с вакуумом
 * @param vThrust      разгон вверх/вниз за тик
 * @param vMax         предел скорости вверх/вниз за тик
 * @param vKeep        какая доля вертикальной скорости остаётся за тик без клавиш
 * @param hThrust      добавочный разгон WASD за тик
 * @param hMax         выше этой скорости (блоков/тик) добавочный разгон не даётся
 * @param hKeep        какая доля горизонтальной скорости остаётся за тик в воздухе без клавиш (0 — не трогать)
 * @param fallDamage   урон от падения в вакууме
 * @param groundJump   обычный прыжок с земли
 * @param enterHint    подсказка в action bar при входе на карту ("" — нет)
 * @param space        чёрные дыры, дрейф, предметы
 */
record Settings(Set<String> maps, double vThrust, double vMax, double vKeep, double hThrust, double hMax, double hKeep,
                boolean fallDamage, boolean groundJump, String enterHint, Space space) {

    static final int TPS = 20;

    /** Без дыр и дрейфа (для тестов движения). */
    Settings(Set<String> maps, double vThrust, double vMax, double vKeep, double hThrust, double hMax, double hKeep,
             boolean fallDamage, boolean groundJump, String enterHint) {
        this(maps, vThrust, vMax, vKeep, hThrust, hMax, hKeep, fallDamage, groundJump, enterHint, Space.NONE);
    }

    /**
     * Космос: дыры и дрейф. Единицы — как в config.yml (секунды); в тики переводит {@link Gravitation}.
     *
     * @param holes          дыры по картам
     * @param falloff        показатель ослабления притяжения с расстоянием
     * @param minPull        наименьшее притяжение в пределах reach, блоков/с²
     * @param holeDefaults   значения для /hograv hole add и пропущенных полей
     * @param driftDir       направление дрейфа без дыр (единичное) или null
     * @param driftSpeed     скорость дрейфа без дыр, блоков/с (0 — нет)
     * @param driftAccel     разгон дрейфа без дыр, блоков/с²
     * @param playerScale    множитель притяжения для игрока
     * @param playerMaxDrift предел скорости дрейфа игрока, блоков/с
     * @param horizonSpawn   игрок у горизонта: true — на спавн карты, false — держать у горизонта
     * @param items          вакуум для предметов (ItemEntity)
     * @param itemKeep       какая доля скорости предмета остаётся за тик
     * @param itemTicks      раз во сколько тиков пересчитывать предметы
     * @param absorbEffect   частицы и звук при поглощении предмета
     */
    record Space(Map<String, List<BlackHole>> holes, double falloff, double minPull, BlackHole holeDefaults,
                 double[] driftDir, double driftSpeed, double driftAccel, double playerScale, double playerMaxDrift,
                 boolean horizonSpawn, boolean items, double itemKeep, int itemTicks, boolean absorbEffect) {
        static final BlackHole DEFAULT_HOLE = new BlackHole("", 0, 0, 0, 6, 1500, 4, 12);
        static final Space NONE = new Space(Map.of(), 1, 0.05, DEFAULT_HOLE, null, 0, 0, 0.5, 1.5, false, false, 1, 2,
            false);

        List<BlackHole> holes(String map) {
            return holes.getOrDefault(map, List.of());
        }
    }

    static Settings from(ModuleConfig c) {
        Set<String> maps = new LinkedHashSet<>(c.getStringList("maps"));
        return new Settings(Collections.unmodifiableSet(maps),
            perTick2(c.getDouble("vertical.thrust", 8.0)),
            perTick(c.getDouble("vertical.max-speed", 4.0)),
            Motion.keepPerTick(c.getDouble("vertical.stop-half-life", 0.35)),
            perTick2(c.getDouble("horizontal.thrust", 2.4)),
            perTick(c.getDouble("horizontal.max-speed", 7.0)),
            Motion.keepPerTick(c.getDouble("horizontal.glide-half-life", 0.8)),
            c.getBoolean("fall-damage", false),
            c.getBoolean("ground-jump", false),
            c.getString("enter-hint", ""),
            space(c));
    }

    private static Space space(ModuleConfig c) {
        BlackHole defs = holeDefaults(c);
        Map<String, List<BlackHole>> holes = holes(c, defs);
        double[] dir = Gravitation.unit(c.getDouble("drift.direction.x", 0), c.getDouble("drift.direction.y", 0),
            c.getDouble("drift.direction.z", -1));
        String onHorizon = c.getString("player-on-horizon", "none").trim().toLowerCase();
        return new Space(Collections.unmodifiableMap(holes),
            positive(c.getDouble("hole-physics.falloff", 1.0)),
            positive(c.getDouble("hole-physics.min-pull", 0.05)),
            defs,
            dir,
            positive(c.getDouble("drift.speed", 0.3)),
            positive(c.getDouble("drift.acceleration", 0.3)),
            positive(c.getDouble("player-pull-scale", 0.5)),
            positive(c.getDouble("player-max-drift-speed", 1.5)),
            onHorizon.equals("spawn"),
            c.getBoolean("items.enabled", true),
            itemKeep(c.getDouble("items.drag-half-life", 30)),
            Math.max(1, Math.min(20, c.getInt("items.update-ticks", 2))),
            c.getBoolean("items.absorb-effect", true));
    }

    /** Значения для /hograv hole add и пропущенных полей дыр (hole-defaults). */
    static BlackHole holeDefaults(ModuleConfig c) {
        BlackHole d = Space.DEFAULT_HOLE;
        return new BlackHole("", 0, 0, 0,
            positive(c.getDouble("hole-defaults.horizon", d.horizon())),
            positive(c.getDouble("hole-defaults.reach", d.reach())),
            positive(c.getDouble("hole-defaults.pull", d.pull())),
            positive(c.getDouble("hole-defaults.max-speed", d.maxSpeed())));
    }

    /**
     * Раздел black-holes. Ключи берём как есть (Object → строка), а не через ModuleConfig.keys/getDouble: YAML читает
     * ключ без кавычек {@code 1:} как число, {@code on:} — как true, и путь через точку их не находит.
     */
    static Map<String, List<BlackHole>> holes(ModuleConfig c, BlackHole defs) {
        Map<String, List<BlackHole>> holes = new LinkedHashMap<>();
        if (!(c.get(HoleYaml.KEY) instanceof Map<?, ?> byMap)) {
            return holes;
        }
        for (var me : byMap.entrySet()) {
            if (!(me.getValue() instanceof Map<?, ?> byName)) {
                continue;
            }
            List<BlackHole> list = new ArrayList<>();
            for (var he : byName.entrySet()) {
                Map<?, ?> h = he.getValue() instanceof Map<?, ?> m ? m : Map.of();
                list.add(new BlackHole(String.valueOf(he.getKey()), num(h, "x", 0), num(h, "y", 0), num(h, "z", 0),
                    positive(num(h, "horizon", defs.horizon())),
                    positive(num(h, "reach", defs.reach())),
                    positive(num(h, "pull", defs.pull())),
                    positive(num(h, "max-speed", defs.maxSpeed()))));
            }
            if (!list.isEmpty()) {
                holes.put(String.valueOf(me.getKey()), List.copyOf(list));
            }
        }
        return holes;
    }

    private static double num(Map<?, ?> m, String key, double def) {
        return m.get(key) instanceof Number n ? n.doubleValue() : def;
    }

    /** Затухание предметов: 0 и меньше — без затухания совсем. */
    private static double itemKeep(double halfLife) {
        return halfLife > 0 ? Motion.keepPerTick(halfLife) : 1;
    }

    private static double positive(double v) {
        return Double.isFinite(v) ? Math.max(0, v) : 0;
    }

    /** блоков/сек → блоков/тик. */
    static double perTick(double perSecond) {
        return Math.max(0, perSecond) / TPS;
    }

    /** блоков/сек² → блоков/тик². */
    static double perTick2(double perSecond2) {
        return Math.max(0, perSecond2) / (TPS * TPS);
    }
}
