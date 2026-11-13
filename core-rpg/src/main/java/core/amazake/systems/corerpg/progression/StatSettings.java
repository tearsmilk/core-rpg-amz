package core.amazake.systems.corerpg.progression;

import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Коэффициенты формул параметров (раздел {@code stats} в progression.yml). Неизменяемый снимок.
 *
 * <p>Главный: attack_damage {@code primaryDamagePerPoint} за очко и {@code primaryDamagePer10} за каждые 10,
 * accuracy = главный / {@code primaryAccuracyDivisor}. Выносливость: max_health {@code staminaHealthPerPoint} за очко.
 * Ловкость: attack_speed за очко и за каждые 10, evasion за каждые 10. Удача: рейтинг крита за очко и за каждые 10,
 * шанс крита {@code 100 × critScale × (1 − critBase^(рейтинг × (critA − уровень × critB)))} не больше
 * {@code critChanceCap}, accuracy за каждые 10, crit_damage = {@code critDamageBase} + за каждые 10.</p>
 */
public record StatSettings(
    double primaryDamagePerPoint, double primaryDamagePer10, double primaryAccuracyDivisor,
    double staminaHealthPerPoint,
    double agilitySpeedPerPoint, double agilitySpeedPer10, double agilityEvasionPer10,
    double luckRatingPerPoint, double luckRatingPer10, double critScale, double critBase, double critA, double critB,
    double critChanceCap, double luckAccuracyPer10, double critDamageBase, double critDamagePer10) {

    public static final StatSettings DEFAULTS = new StatSettings(
        0.5, 3.0, 5.0,
        2.0,
        0.01, 0.1, 2.0,
        0.33, 1.0, 0.6, 0.999, 60.1007, 0.6491,
        57.0, 2.0, 50.0, 5.0);

    public static StatSettings from(final ConfigurationSection section, final Consumer<String> warn) {
        if (section == null) {
            return DEFAULTS;
        }
        final StatSettings d = DEFAULTS;
        final Reader r = new Reader(section, warn, "stats.");
        return new StatSettings(
            r.num("primary.attack-damage-per-point", d.primaryDamagePerPoint(), 0, 1000),
            r.num("primary.attack-damage-per-10", d.primaryDamagePer10(), 0, 10000),
            r.num("primary.accuracy-divisor", d.primaryAccuracyDivisor(), 0.0001, 10000),
            r.num("stamina.max-health-per-point", d.staminaHealthPerPoint(), 0, 1000),
            r.num("agility.attack-speed-per-point", d.agilitySpeedPerPoint(), 0, 100),
            r.num("agility.attack-speed-per-10", d.agilitySpeedPer10(), 0, 100),
            r.num("agility.evasion-per-10", d.agilityEvasionPer10(), 0, 1000),
            r.num("luck.crit-rating-per-point", d.luckRatingPerPoint(), 0, 1000),
            r.num("luck.crit-rating-per-10", d.luckRatingPer10(), 0, 1000),
            r.num("luck.crit-formula.scale", d.critScale(), 0, 1),
            r.num("luck.crit-formula.base", d.critBase(), 0.0001, 0.999999),
            r.num("luck.crit-formula.a", d.critA(), 0, 10000),
            r.num("luck.crit-formula.b", d.critB(), 0, 1000),
            r.num("luck.crit-chance-cap", d.critChanceCap(), 0, 100),
            r.num("luck.accuracy-per-10", d.luckAccuracyPer10(), 0, 1000),
            r.num("luck.crit-damage-base", d.critDamageBase(), 0, 10000),
            r.num("luck.crit-damage-per-10", d.critDamagePer10(), 0, 10000));
    }

    /** Чтение чисел с проверкой диапазона: неверное значение — предупреждение и значение по умолчанию. */
    static final class Reader {
        private final ConfigurationSection section;
        private final Consumer<String> warn;
        private final String prefix;

        Reader(final ConfigurationSection section, final Consumer<String> warn, final String prefix) {
            this.section = section;
            this.warn = warn;
            this.prefix = prefix;
        }

        double num(final String path, final double def, final double min, final double max) {
            if (!this.section.contains(path)) {
                return def;
            }
            final Object raw = this.section.get(path);
            if (!(raw instanceof Number number)) {
                this.warn.accept(this.prefix + path + ": «" + raw + "» — не число, беру " + def);
                return def;
            }
            final double v = number.doubleValue();
            if (!Double.isFinite(v) || v < min || v > max) {
                this.warn.accept(this.prefix + path + ": " + v + " вне " + min + ".." + max + ", беру " + def);
                return def;
            }
            return v;
        }

        int integer(final String path, final int def, final int min, final int max) {
            if (!this.section.contains(path)) {
                return def;
            }
            final Object raw = this.section.get(path);
            if (!(raw instanceof Number number) || number.doubleValue() != Math.rint(number.doubleValue())) {
                this.warn.accept(this.prefix + path + ": «" + raw + "» — не целое число, беру " + def);
                return def;
            }
            final long v = number.longValue();
            if (v < min || v > max) {
                this.warn.accept(this.prefix + path + ": " + v + " вне " + min + ".." + max + ", беру " + def);
                return def;
            }
            return (int) v;
        }

        boolean bool(final String path, final boolean def) {
            if (!this.section.contains(path)) {
                return def;
            }
            final Object raw = this.section.get(path);
            if (raw instanceof Boolean b) {
                return b;
            }
            this.warn.accept(this.prefix + path + ": «" + raw + "» — нужно true или false, беру " + def);
            return def;
        }

        String string(final String path, final String def) {
            if (!this.section.contains(path)) {
                return def;
            }
            final Object raw = this.section.get(path);
            if (raw == null || raw instanceof ConfigurationSection || raw instanceof java.util.List<?>) {
                this.warn.accept(this.prefix + path + ": нужна строка, беру значение по умолчанию");
                return def;
            }
            return String.valueOf(raw);
        }
    }
}
