package core.amazake.systems.corerpg.seals;

import core.amazake.systems.corerpg.aspect.Aspect;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Map;

/** Подписи и единицы бонусов печатей для лора, /seals info и /seals list. Чистая логика. */
public final class BonusFormat {

    private static final Map<String, String> TARGETS = Map.ofEntries(
        Map.entry("fire", "огонь"),
        Map.entry("chaos", "хаос"),
        Map.entry("undead", "нежить"),
        Map.entry("humanoid", "гуманоиды"),
        Map.entry("water", "вода"),
        Map.entry("air", "воздух"),
        Map.entry("earth", "земля"),
        Map.entry("poison", "яд"),
        Map.entry("animal", "животные"),
        Map.entry("arthropod", "членистоногие"),
        Map.entry("monster", "монстры"));

    private BonusFormat() {
    }

    /** «Урон», «Сопротивление (огонь)», «Урон против (монстры)». */
    public static String label(final String key) {
        final String k = key.toLowerCase(Locale.ROOT);
        if (k.startsWith("resistance:")) {
            return "Сопротивление (" + target(k.substring("resistance:".length())) + ")";
        }
        if (k.startsWith("damage_vs:")) {
            return "Урон против (" + target(k.substring("damage_vs:".length())) + ")";
        }
        final Aspect aspect = Aspect.byKey(k);
        return aspect == null ? key : aspect.label();
    }

    private static String target(final String t) {
        return TARGETS.getOrDefault(t, t);
    }

    /** Значение — доля 0..1, показывается ×100%: resistance, resistance:*, resistance_pvp. */
    public static boolean isFraction(final String key) {
        final String k = key.toLowerCase(Locale.ROOT);
        return k.equals("resistance") || k.startsWith("resistance:") || k.equals("resistance_pvp");
    }

    /** Значение уже в процентах: crit_*, lifesteal, exp_bonus, gold_bonus, status_*, damage_vs:*. */
    public static boolean isPercent(final String key) {
        final String k = key.toLowerCase(Locale.ROOT);
        return k.startsWith("crit_") || k.equals("lifesteal") || k.equals("exp_bonus") || k.equals("gold_bonus")
            || k.startsWith("status_") || k.startsWith("damage_vs:");
    }

    /** Бонус на системах, которых пока нет: мана / энергия (resource) и проки (proc). */
    public static boolean inactive(final String key) {
        final String k = key.toLowerCase(Locale.ROOT);
        return k.equals("resource") || k.equals("proc");
    }

    private static String unit(final String key) {
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "max_health", "heal_on_kill" -> " HP";
            case "regen" -> " HP/с";
            case "range" -> " бл.";
            default -> "";
        };
    }

    /** «+1.6», «+3%», «+0.5 HP/с», «-2». */
    public static String value(final String key, final double raw) {
        final boolean fraction = isFraction(key);
        final double shown = fraction ? raw * 100.0 : raw;
        final String sign = shown < 0 ? "-" : "+";
        final String suffix = fraction || isPercent(key) ? "%" : unit(key);
        return sign + number(Math.abs(shown)) + suffix;
    }

    /** Число без лишних нулей, до 3 знаков: 4.000000001 → «4», 0.004 → «0.004», 1.25 → «1.25». */
    public static String number(final double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "0";
        }
        final BigDecimal d = BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros();
        return d.signum() == 0 ? "0" : d.toPlainString();
    }

    /** Строка лора без тегов: «• Урон: +1.6», «• Мана / энергия: +5 (пока не действует)». */
    public static String plainLine(final String key, final double raw) {
        return "• " + label(key) + ": " + value(key, raw) + (inactive(key) ? " (пока не действует)" : "");
    }
}
