package ink.hoka.odyssey.items;

import ink.hoka.odyssey.api.ModuleConfig;
import java.util.Set;

/**
 * Настройки из config.yml.
 *
 * @param pickupDelayMs  задержка подбора выброшенного предмета, мс
 * @param despawnSeconds через сколько секунд лежащий предмет исчезает (0 — никогда)
 * @param merge          сливать одинаковые лежащие предметы
 * @param mergeRange     радиус слияния, блоков
 * @param throwSpeed     скорость броска, блоков/тик
 * @param spread         случайный разброс броска
 * @param noDespawnMaps  карты (папки в maps/), где предметы не исчезают по времени — летают, пока их что-то не поглотит
 */
record Settings(long pickupDelayMs, long despawnSeconds, boolean merge, float mergeRange, double throwSpeed,
                boolean spread, Set<String> noDespawnMaps) {

    static Settings from(ModuleConfig c) {
        return new Settings(
            Math.round(Math.max(0, c.getDouble("pickup-delay-seconds", 2.0)) * 1000),
            Math.max(0, c.getLong("despawn-seconds", 300)),
            c.getBoolean("merge", true),
            (float) Math.max(0, c.getDouble("merge-range", 1.0)),
            Math.max(0, c.getDouble("throw-speed", 0.3)),
            c.getBoolean("spread", true),
            Set.copyOf(c.getStringList("no-despawn-maps")));
    }
}
