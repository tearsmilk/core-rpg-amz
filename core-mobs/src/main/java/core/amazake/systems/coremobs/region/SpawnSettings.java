package core.amazake.systems.coremobs.region;

import core.amazake.systems.coremobs.util.IntRange;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Спавн внутри региона.
 *
 * @param mobs моб → вес: чем больше вес, тем чаще появляется этот моб
 * @param intervalSeconds как часто регион пробует заспавнить новых мобов
 * @param maxAlive сколько мобов региона может быть живо одновременно
 * @param amount сколько мобов за одну попытку
 * @param playerRadius регион работает, только если игрок не дальше этого от его границы
 * @param minPlayerDistance моб не появляется ближе этого к игроку
 */
public record SpawnSettings(boolean enabled, Map<String, Integer> mobs, int intervalSeconds, int maxAlive,
                            IntRange amount, int playerRadius, int minPlayerDistance) {

    public static final SpawnSettings DEFAULTS = new SpawnSettings(true, Map.of(), 10, 10, IntRange.of(1), 48, 6);

    public SpawnSettings {
        mobs = Collections.unmodifiableMap(new LinkedHashMap<>(mobs));
    }

    public SpawnSettings withEnabled(final boolean value) {
        return new SpawnSettings(value, this.mobs, this.intervalSeconds, this.maxAlive, this.amount, this.playerRadius, this.minPlayerDistance);
    }

    public SpawnSettings withMobs(final Map<String, Integer> value) {
        return new SpawnSettings(this.enabled, value, this.intervalSeconds, this.maxAlive, this.amount, this.playerRadius, this.minPlayerDistance);
    }

    public SpawnSettings withInterval(final int value) {
        return new SpawnSettings(this.enabled, this.mobs, value, this.maxAlive, this.amount, this.playerRadius, this.minPlayerDistance);
    }

    public SpawnSettings withMaxAlive(final int value) {
        return new SpawnSettings(this.enabled, this.mobs, this.intervalSeconds, value, this.amount, this.playerRadius, this.minPlayerDistance);
    }

    public SpawnSettings withAmount(final IntRange value) {
        return new SpawnSettings(this.enabled, this.mobs, this.intervalSeconds, this.maxAlive, value, this.playerRadius, this.minPlayerDistance);
    }

    public SpawnSettings withPlayerRadius(final int value) {
        return new SpawnSettings(this.enabled, this.mobs, this.intervalSeconds, this.maxAlive, this.amount, value, this.minPlayerDistance);
    }

    public SpawnSettings withMinPlayerDistance(final int value) {
        return new SpawnSettings(this.enabled, this.mobs, this.intervalSeconds, this.maxAlive, this.amount, this.playerRadius, value);
    }
}
