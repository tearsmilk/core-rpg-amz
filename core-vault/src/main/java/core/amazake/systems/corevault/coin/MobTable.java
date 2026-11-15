package core.amazake.systems.corevault.coin;

import java.util.Locale;
import java.util.Map;

/**
 * Значение по мобу: сначала id моба core-mobs (метка {@code core-mobs:mob}), затем ванильный тип, затем «любой
 * враждебный» и «любой». Пустая карта/null на уровне — переход к следующему; явное {@code none}-правило решает
 * сам вызывающий (например, правило с шансом 0).
 */
public record MobTable<T>(Map<String, T> coreMobs, Map<String, T> types, T hostile, T fallback) {

    public MobTable {
        coreMobs = Map.copyOf(coreMobs);
        types = Map.copyOf(types);
    }

    public static <T> MobTable<T> empty() {
        return new MobTable<>(Map.of(), Map.of(), null, null);
    }

    /**
     * @param coreMobId id моба core-mobs или null
     * @param type      ванильный тип (ZOMBIE, любой регистр)
     * @param hostile   моб враждебный (Enemy)
     */
    public T find(final String coreMobId, final String type, final boolean hostile) {
        if (coreMobId != null) {
            final T v = this.coreMobs.get(coreMobId.toLowerCase(Locale.ROOT));
            if (v != null) {
                return v;
            }
        }
        if (type != null) {
            final T v = this.types.get(type.toUpperCase(Locale.ROOT));
            if (v != null) {
                return v;
            }
        }
        if (hostile && this.hostile != null) {
            return this.hostile;
        }
        return this.fallback;
    }
}
