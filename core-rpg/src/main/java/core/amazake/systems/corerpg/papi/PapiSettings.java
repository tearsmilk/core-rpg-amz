package core.amazake.systems.corerpg.papi;

import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;

/**
 * papi.yml: оформление плейсхолдеров CoreRpg. Неизменяемый снимок; подменяется целиком в reload. Включение и
 * идентификатор расширения — в config.yml ({@code placeholderapi.enabled/identifier}, см. GeneralSettings).
 *
 * @param noneName        что выводят %core_class% и %core_profession%, если класс или профессия не выбраны
 * @param noneKey         что выводят %core_class_key% и %core_profession_key% без класса или профессии
 * @param noSeal          что выводят %core_seal% и %core_seal_color%, если в руке нет печати
 * @param notLoaded       что выводит любой плейсхолдер, пока профиль игрока не загружен (и для игроков не в сети)
 * @param progressDigits  знаков после запятой в %core_exp_progress% (0..3)
 * @param legacyNames     понимать старые имена RoyalQuestClasses: stats_available, stats_primary, …
 */
public record PapiSettings(String noneName, String noneKey, String noSeal, String notLoaded, int progressDigits,
                           boolean legacyNames) {

    public static final PapiSettings DEFAULTS = new PapiSettings("Нет", "none", "", "", 1, false);

    /** Текст файла по умолчанию: пишется в папку системы, если файла нет. */
    public static final String DEFAULT_YAML = """
        # ─────────────────────────────────────────────────────────────────────────────
        #  CoreRpg — плейсхолдеры PlaceholderAPI (модуль papi)
        # ─────────────────────────────────────────────────────────────────────────────
        # Включение и префикс плейсхолдеров — в config.yml, раздел placeholderapi:
        #   enabled: true      — отдавать плейсхолдеры (нужен плагин PlaceholderAPI)
        #   identifier: core   — префикс: %core_level%, %core_class% …
        #
        # Список плейсхолдеров (%core_<имя>%):
        #   class, class_key            — класс (имя / ключ: swordsman, archer, mage, thief)
        #   profession, profession_key  — профессия (имя / ключ)
        #   level, max_level            — уровень и максимальный уровень
        #   exp, exp_needed, exp_left   — опыт внутри уровня, нужно на уровень, осталось
        #   exp_progress                — прогресс уровня в процентах 0..100
        #   points                      — свободные очки параметров
        #   stat_primary, stat_agility, stat_stamina, stat_luck — вложенные очки
        #   stat_<параметр>_total       — вложенные + бонусы печатей и заточки +11
        #   stat_primary_name           — Сила / Точность / Интеллект (по классу)
        #   stat_agility_name, stat_stamina_name, stat_luck_name
        #   has_class, has_profession, needs_profession — true / false
        #   aspect_<ключ>               — любой аспект: aspect_crit_chance, aspect_evasion,
        #                                 aspect_resistance:fire, aspect_damage_vs:monster …
        #   crit_chance                 — то же, что aspect_crit_chance
        #   sharpen, sharpen_max, sharpen_bonus — заточка предмета в основной руке
        #   sharpen_text                — «+N» или пусто без заточки
        #   seal, seal_color            — печать предмета в основной руке и её цвет
        #
        # Предмет в руке запоминается при каждом пересчёте аспектов игрока (смена слота, экипировки),
        # поэтому плейсхолдеры руки безопасно читать из любого потока (табы, скорборды, голограммы).

        # Что выводить вместо имени класса/профессии, если они ещё не выбраны.
        none-name: "Нет"

        # Что выводить вместо ключа класса/профессии (class_key, profession_key), если они не выбраны.
        none-key: "none"

        # Что выводить в seal и seal_color, если на предмете в руке нет печати.
        no-seal: ""

        # Что выводить, пока профиль игрока не загружен (первые мгновения после входа) или игрок не в сети.
        not-loaded: ""

        # Знаков после запятой в exp_progress (0..3). 1 → «42.5».
        progress-digits: 1

        # Совместимость со старыми конфигами RoyalQuestClasses (по умолчанию выключена). При true:
        #   • дополнительно регистрируется старый префикс royalquest, поэтому готовые конфиги табов,
        #     скорбордов и голограмм с %royalquest_level%, %royalquest_stats_available% … работают как раньше
        #     (если префикс royalquest не занят другим плагином — тогда предупреждение в консоли);
        #   • старые имена понимаются под любым префиксом:
        #   stats_available → points, stats_primary → stat_primary, stats_agility → stat_agility,
        #   stats_stamina → stat_stamina, stats_luck → stat_luck, stats_primary_name → stat_primary_name
        legacy-names: false
        """;

    public static PapiSettings from(final ConfigurationSection config, final Consumer<String> warn) {
        final String noneName = text(config, "none-name", DEFAULTS.noneName(), warn);
        final String noneKey = text(config, "none-key", DEFAULTS.noneKey(), warn);
        final String noSeal = text(config, "no-seal", DEFAULTS.noSeal(), warn);
        final String notLoaded = text(config, "not-loaded", DEFAULTS.notLoaded(), warn);

        int digits = DEFAULTS.progressDigits();
        if (config.isSet("progress-digits")) {
            if (!config.isInt("progress-digits")) {
                warn.accept("papi.yml: progress-digits «" + config.get("progress-digits") + "» — не целое число, беру " + digits);
            } else {
                final int value = config.getInt("progress-digits");
                if (value < 0 || value > 3) {
                    warn.accept("papi.yml: progress-digits " + value + " вне 0..3, беру " + digits);
                } else {
                    digits = value;
                }
            }
        }

        boolean legacy = DEFAULTS.legacyNames();
        if (config.isSet("legacy-names")) {
            if (!config.isBoolean("legacy-names")) {
                warn.accept("papi.yml: legacy-names «" + config.get("legacy-names") + "» — нужно true или false, беру " + legacy);
            } else {
                legacy = config.getBoolean("legacy-names");
            }
        }
        return new PapiSettings(noneName, noneKey, noSeal, notLoaded, digits, legacy);
    }

    /** Строка или число/логическое (переводится в строку); список или раздел — предупреждение и значение по умолчанию. */
    private static String text(final ConfigurationSection config, final String path, final String def, final Consumer<String> warn) {
        if (!config.isSet(path)) {
            return def;
        }
        final Object value = config.get(path);
        if (value instanceof String || value instanceof Number || value instanceof Boolean || value instanceof Character) {
            return value.toString();
        }
        warn.accept("papi.yml: " + path + " — нужна строка, беру «" + def + "»");
        return def;
    }
}
