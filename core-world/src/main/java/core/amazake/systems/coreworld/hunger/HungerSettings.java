package core.amazake.systems.coreworld.hunger;

import core.amazake.systems.coreworld.module.WorldFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Раздел {@code hunger} config.yml. Неизменяемый снимок: reload подменяет его целиком.
 *
 * @param hungerDisabled {@code hunger.disabled}: голод выключен (модуль работает)
 * @param worlds         {@code hunger.worlds}: где выключен; пустой список — везде
 * @param keepSaturation  {@code hunger.keep-saturation}: держать насыщение полным
 * @param clearSaturation {@code hunger.clear-saturation}: держать насыщение на нуле (важнее keep-saturation)
 */
public record HungerSettings(boolean hungerDisabled, WorldFilter worlds, boolean keepSaturation,
                             boolean clearSaturation) {

    public static final HungerSettings DEFAULTS = new HungerSettings(true, WorldFilter.ALL, true, false);

    /** Читает раздел {@code hunger} из всего config.yml; неверные значения заменяются значениями по умолчанию. */
    public static HungerSettings from(final ConfigurationSection config, final Consumer<String> warn) {
        final HungerSettings d = DEFAULTS;
        if (config.isSet("hunger") && !config.isConfigurationSection("hunger")) {
            warn.accept("hunger: должен быть разделом (disabled, worlds, keep-saturation), беру значения по умолчанию");
            return d;
        }
        return new HungerSettings(
            bool(config, "hunger.disabled", d.hungerDisabled(), warn),
            worlds(config, warn),
            bool(config, "hunger.keep-saturation", d.keepSaturation(), warn),
            bool(config, "hunger.clear-saturation", d.clearSaturation(), warn));
    }

    /** Для тестов: разбор текста config.yml. */
    public static HungerSettings parse(final String yaml, final Consumer<String> warn) throws Exception {
        final YamlConfiguration c = new YamlConfiguration();
        c.loadFromString(yaml);
        return from(c, warn);
    }

    /** Затронут ли этот мир (модуль включён и мир в списке). */
    public boolean affects(final String worldName) {
        return this.hungerDisabled && this.worlds.affects(worldName);
    }

    private static boolean bool(final ConfigurationSection c, final String key, final boolean fallback,
                                final Consumer<String> warn) {
        if (!c.isSet(key)) {
            return fallback;
        }
        if (!c.isBoolean(key)) {
            warn.accept(key + ": «" + c.get(key) + "» не true/false, беру " + fallback);
            return fallback;
        }
        return c.getBoolean(key);
    }

    private static WorldFilter worlds(final ConfigurationSection c, final Consumer<String> warn) {
        final String key = "hunger.worlds";
        if (!c.isSet(key)) {
            return WorldFilter.ALL;
        }
        if (c.isString(key)) {
            // worlds: world — частая ошибка вместо [world]; понимаем как один мир.
            final String single = c.getString(key, "");
            if (single.isBlank()) {
                return WorldFilter.ALL;
            }
            warn.accept(key + ": ожидался список, понял как [" + single.trim() + "]");
            return WorldFilter.of(List.of(single));
        }
        if (!c.isList(key)) {
            warn.accept(key + ": «" + c.get(key) + "» не список миров, беру все миры");
            return WorldFilter.ALL;
        }
        final List<String> names = new ArrayList<>();
        for (final Object raw : c.getList(key, List.of())) {
            if (raw == null || raw.toString().isBlank()) {
                warn.accept(key + ": пустое имя мира пропущено");
                continue;
            }
            names.add(raw.toString());
        }
        return WorldFilter.of(names);
    }
}
