package core.amazake.systems.rezusmenu;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;

/** Настройки из config.yml. Неизменяемый снимок: reload подменяет его целиком. */
public record Settings(
    String prefix,
    String dialogTitle,
    String dialogBody,
    List<MenuEntry> menus,
    double eyeOffset,
    double sensitivity,
    boolean invulnerable,
    int refreshTicks,
    String phLevel,
    String phExp,
    String phExpNeeded,
    String phClass,
    String phMana,
    String phManaMax,
    List<Stat> stats,
    List<Stat> equipmentLines
) {

    /** Кнопка в диалоге /menuses. */
    public record MenuEntry(String id, String label, String tooltip) {
    }

    /** Строка «название — значение» из плейсхолдеров (или готового текста). */
    public record Stat(String name, String value) {
    }

    public static final List<Stat> DEFAULT_STATS = List.of(
        new Stat("%core_stat_primary_name%", "%core_stat_primary_total%"),
        new Stat("%core_stat_agility_name%", "%core_stat_agility_total%"),
        new Stat("%core_stat_stamina_name%", "%core_stat_stamina_total%"),
        new Stat("%core_stat_luck_name%", "%core_stat_luck_total%"));

    public static final List<Stat> DEFAULT_EQUIPMENT = List.of(
        new Stat("Урон", "%core_aspect_attack_damage%"),
        new Stat("Броня", "%core_aspect_armor%"),
        new Stat("Здоровье (бонус)", "%core_aspect_max_health%"),
        new Stat("Шанс крита, %", "%core_crit_chance%"),
        new Stat("Урон крита, %", "%core_aspect_crit_damage%"),
        new Stat("Точность", "%core_aspect_accuracy%"),
        new Stat("Уклонение", "%core_aspect_evasion%"),
        new Stat("Сопротивление", "%core_aspect_resistance%"),
        new Stat("Заточка в руке", "%core_sharpen_text%"),
        new Stat("Печать в руке", "%core_seal%"));

    public static final Settings DEFAULTS = new Settings(
        "<dark_gray>[<gold>Меню</gold>]</dark_gray> ",
        "<gold>Меню сервера</gold>",
        "<gray>Выберите, что открыть.</gray>",
        List.of(new MenuEntry("rpg", "<gold>RPG-инвентарь</gold>", "<gray>Персонаж, снаряжение и инвентарь</gray>")),
        0.18, 0.045, true, 10,
        "%core_level%", "%core_exp%", "%core_exp_needed%", "%core_class%", "", "",
        DEFAULT_STATS, DEFAULT_EQUIPMENT);

    public static Settings from(final ConfigurationSection c, final Consumer<String> warn) {
        final Settings d = DEFAULTS;
        final List<MenuEntry> menus = new ArrayList<>();
        final ConfigurationSection m = c.getConfigurationSection("menus");
        if (m != null) {
            for (final String id : m.getKeys(false)) {
                final ConfigurationSection e = m.getConfigurationSection(id);
                if (e == null || !e.getBoolean("enabled", true)) {
                    continue;
                }
                final String key = id.toLowerCase(Locale.ROOT);
                if (!key.matches("[a-z0-9_\\-]{1,32}")) {
                    warn.accept("menus." + id + ": id — латиница в нижнем регистре, цифры, _ и -");
                    continue;
                }
                menus.add(new MenuEntry(key, e.getString("label", key), e.getString("tooltip", "")));
            }
        } else {
            menus.addAll(d.menus());
        }
        double sensitivity = c.getDouble("rpg.cursor-speed", d.sensitivity());
        if (!(sensitivity >= 0.005 && sensitivity <= 0.5)) {
            warn.accept("rpg.cursor-speed: " + sensitivity + " вне 0.005..0.5, беру " + d.sensitivity());
            sensitivity = d.sensitivity();
        }
        double eye = c.getDouble("rpg.eye-offset", d.eyeOffset());
        if (!(eye >= -1 && eye <= 1)) {
            eye = d.eyeOffset();
        }
        int refresh = c.getInt("rpg.refresh-ticks", d.refreshTicks());
        if (refresh < 2 || refresh > 200) {
            warn.accept("rpg.refresh-ticks: " + refresh + " вне 2..200, беру " + d.refreshTicks());
            refresh = d.refreshTicks();
        }
        return new Settings(
            c.getString("messages.prefix", d.prefix()),
            c.getString("dialog.title", d.dialogTitle()),
            c.getString("dialog.body", d.dialogBody()),
            List.copyOf(menus),
            eye,
            sensitivity,
            c.getBoolean("rpg.invulnerable", d.invulnerable()),
            refresh,
            c.getString("rpg.placeholders.level", d.phLevel()),
            c.getString("rpg.placeholders.exp", d.phExp()),
            c.getString("rpg.placeholders.exp-needed", d.phExpNeeded()),
            c.getString("rpg.placeholders.class", d.phClass()),
            c.getString("rpg.placeholders.mana", d.phMana()),
            c.getString("rpg.placeholders.mana-max", d.phManaMax()),
            stats(c, "rpg.stats", d.stats()),
            stats(c, "rpg.equipment-lines", d.equipmentLines()));
    }

    private static List<Stat> stats(final ConfigurationSection c, final String path, final List<Stat> fallback) {
        if (!c.isList(path)) {
            return fallback;
        }
        final List<Stat> out = new ArrayList<>();
        for (final java.util.Map<?, ?> row : c.getMapList(path)) {
            final Object name = row.get("name");
            final Object value = row.get("value");
            if (name != null && value != null) {
                out.add(new Stat(name.toString(), value.toString()));
            }
        }
        return List.copyOf(out);
    }
}
