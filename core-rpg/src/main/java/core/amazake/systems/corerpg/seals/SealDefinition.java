package core.amazake.systems.corerpg.seals;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Печать из каталога. Неизменяема.
 *
 * @param id      ключ в seals.yml (нижний регистр)
 * @param name    название («Агрессии»)
 * @param origin  plugin или royal-quest
 * @param types   WEAPON / ARMOR / SHIELD (ACCESSORY в Minecraft не встречается и отброшен при разборе)
 * @param classes ключи классов и профессий; пусто или «any» — для всех
 * @param enabled false — печать есть в каталоге, но не выпадает
 * @param bonuses ключ аспекта → значения по цветам; порядок как в файле
 */
public record SealDefinition(String id, String name, String origin, Set<String> types, Set<String> classes,
                             boolean enabled, Map<String, Values> bonuses) {

    public static final String ANY = "any";

    public SealDefinition {
        types = Set.copyOf(types);
        classes = Set.copyOf(classes);
        bonuses = Collections.unmodifiableMap(new LinkedHashMap<>(bonuses));
    }

    /** Подходит ли печать типу предмета (WEAPON, ARMOR, SHIELD). */
    public boolean accepts(final String sealType) {
        return sealType != null && this.types.contains(sealType);
    }

    /** Подходит ли печать всем классам. */
    public boolean forAnyClass() {
        return this.classes.isEmpty() || this.classes.contains(ANY);
    }

    /** Подходит ли печать классу или профессии (null — нет). */
    public boolean fitsClass(final String classId, final String professionId) {
        return this.forAnyClass()
            || (classId != null && this.classes.contains(classId))
            || (professionId != null && this.classes.contains(professionId));
    }

    /** Значение бонуса для цвета или NaN, если у этого цвета бонуса нет. */
    public double value(final String key, final SealColor color) {
        final Values values = this.bonuses.get(key);
        return values == null || color == null ? Double.NaN : values.get(color);
    }

    /** Четыре значения бонуса (зелёная, синяя, фиолетовая, оранжевая); NaN — нет бонуса у цвета ({@code ~}). */
    public static final class Values {
        private final double[] values;

        public Values(final double green, final double blue, final double purple, final double orange) {
            this.values = new double[] {green, blue, purple, orange};
        }

        public double get(final SealColor color) {
            return this.values[color.index()];
        }

        public boolean has(final SealColor color) {
            return !Double.isNaN(this.get(color));
        }

        @Override
        public boolean equals(final Object o) {
            return o instanceof final Values other && Arrays.equals(this.values, other.values);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(this.values);
        }

        @Override
        public String toString() {
            return Arrays.toString(this.values);
        }
    }
}
