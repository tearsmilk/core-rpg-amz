package core.amazake.systems.corerpg.progression;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;

/** progression.yml: уровни, опыт, очки параметров, формулы, сообщения. Неизменяемый снимок. */
public record ProgressionSettings(
    int maxLevel,
    int professionLevel,
    boolean blockExpUntilProfession,
    boolean requireClassForExp,
    boolean openClassMenuOnFirstJoin,
    boolean openProfessionMenuOnJoin,
    boolean convertVanillaExp,
    double vanillaExpMultiplier,
    int pointsEveryLevels,
    int pointsPerStep,
    int blockedMessageCooldownSeconds,
    boolean showExpGain,
    String levelUpSound,
    float levelUpVolume,
    float levelUpPitch,
    boolean levelUpTitle,
    ExpCurve expCurve,
    StatSettings stats,
    Messages messages) {

    /** Таблица опыта по умолчанию: RQ ÷ 200, продолжена до 71 (опыт ДЛЯ уровня N). */
    public static final Map<Integer, Long> DEFAULT_TABLE;

    static {
        final long[] values = {
            10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10,           // 2..15
            20, 30, 30, 40, 40,                                                 // 16..20
            80, 100, 110, 120, 140,                                             // 21..25
            290, 320, 360, 390, 440,                                            // 26..30
            950, 1040, 1150, 1260, 1380,                                        // 31..35
            3060, 3340, 3650, 3980, 4320,                                       // 36..40
            9800, 10650, 11560, 12530, 13550,                                   // 41..45
            31310, 33880, 36610, 39490, 42540,                                  // 46..50
            100040, 107850, 116110, 124830, 134020,                             // 51..55
            320270, 344210, 369460, 396080, 424110,                             // 56..60
            1028740, 1102690, 1180630, 1262700, 1349040,                        // 61..65
            3210710, 3441880, 3686260, 3944300, 4212510,                        // 66..70
            10025770};                                                          // 71
        final Map<Integer, Long> table = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i++) {
            table.put(i + 2, values[i]);
        }
        DEFAULT_TABLE = Map.copyOf(table);
    }

    public static final ExpCurve DEFAULT_CURVE = new ExpCurve(DEFAULT_TABLE, 100.0, 1.5);

    public static final ProgressionSettings DEFAULTS = new ProgressionSettings(
        71, 21, true, true, true, true, true, 1.0, 5, 4, 15, false,
        "entity.player.levelup", 1.0f, 1.0f, true,
        DEFAULT_CURVE, StatSettings.DEFAULTS, Messages.DEFAULT);

    public static ProgressionSettings from(final ConfigurationSection config, final Consumer<String> warn) {
        final ProgressionSettings d = DEFAULTS;
        final StatSettings.Reader r = new StatSettings.Reader(config, warn, "");
        final int maxLevel = r.integer("max-level", d.maxLevel(), 2, 1000);
        int professionLevel = r.integer("profession-level", d.professionLevel(), 1, 1000);
        if (professionLevel > maxLevel) {
            warn.accept("profession-level: " + professionLevel + " больше max-level " + maxLevel + ", беру " + maxLevel);
            professionLevel = maxLevel;
        }
        final ExpCurve curve = parseCurve(config, warn, maxLevel);
        final String sound = r.string("level-up.sound", d.levelUpSound());
        return new ProgressionSettings(
            maxLevel,
            professionLevel,
            r.bool("block-exp-until-profession", d.blockExpUntilProfession()),
            r.bool("require-class-for-exp", d.requireClassForExp()),
            r.bool("open-class-menu-on-first-join", d.openClassMenuOnFirstJoin()),
            r.bool("open-profession-menu-on-join", d.openProfessionMenuOnJoin()),
            r.bool("convert-vanilla-exp", d.convertVanillaExp()),
            r.num("vanilla-exp-multiplier", d.vanillaExpMultiplier(), 0, 100000),
            r.integer("points-every-levels", d.pointsEveryLevels(), 1, 1000),
            r.integer("points-per-step", d.pointsPerStep(), 0, 1000),
            r.integer("exp-blocked-message-cooldown-seconds", d.blockedMessageCooldownSeconds(), 0, 3600),
            r.bool("show-exp-gain", d.showExpGain()),
            sound == null ? "" : sound.trim(),
            (float) r.num("level-up.volume", d.levelUpVolume(), 0, 10),
            (float) r.num("level-up.pitch", d.levelUpPitch(), 0.5, 2),
            r.bool("level-up.title", d.levelUpTitle()),
            curve,
            StatSettings.from(config.getConfigurationSection("stats"), warn),
            Messages.from(config.getConfigurationSection("messages"), warn));
    }

    static ExpCurve parseCurve(final ConfigurationSection config, final Consumer<String> warn, final int maxLevel) {
        final StatSettings.Reader r = new StatSettings.Reader(config, warn, "");
        final double base = r.num("exp-formula.base", DEFAULT_CURVE.base(), 0.0001, 1e12);
        final double exponent = r.num("exp-formula.exponent", DEFAULT_CURVE.exponent(), 0, 10);
        if (!config.contains("exp-table")) {
            return new ExpCurve(DEFAULT_TABLE, base, exponent);
        }
        final ConfigurationSection table = config.getConfigurationSection("exp-table");
        if (table == null) {
            warn.accept("exp-table: нужен раздел «уровень: опыт», беру таблицу по умолчанию");
            return new ExpCurve(DEFAULT_TABLE, base, exponent);
        }
        final Map<Integer, Long> out = new LinkedHashMap<>();
        for (final String key : table.getKeys(false)) {
            final int level;
            try {
                level = Integer.parseInt(key.trim());
            } catch (final NumberFormatException e) {
                warn.accept("exp-table." + key + ": ключ должен быть номером уровня, пропускаю");
                continue;
            }
            if (level < 2 || level > maxLevel) {
                if (level != 1) {
                    warn.accept("exp-table." + key + ": уровень вне 2.." + maxLevel + ", пропускаю");
                }
                continue;
            }
            final Object raw = table.get(key);
            if (!(raw instanceof Number number) || number.doubleValue() < 1 || number.doubleValue() != Math.rint(number.doubleValue())) {
                warn.accept("exp-table." + key + ": «" + raw + "» — нужно целое число ≥ 1, беру формулу");
                continue;
            }
            out.put(level, number.longValue());
        }
        return new ExpCurve(out, base, exponent);
    }

    /** Сколько опыта нужно для следующего уровня; 0 на максимуме. */
    public long expToNext(final int level) {
        return level >= this.maxLevel ? 0L : this.expCurve.need(level + 1);
    }
}
