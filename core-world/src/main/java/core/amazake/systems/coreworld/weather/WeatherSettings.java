package core.amazake.systems.coreworld.weather;

import core.amazake.systems.coreworld.module.WorldFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Раздел {@code weather} config.yml.
 *
 * @param rainDisabled {@code weather.disabled}: дождя и грозы нет
 * @param worlds       {@code weather.worlds}: где; пустой список — везде
 */
public record WeatherSettings(boolean rainDisabled, WorldFilter worlds) {

    public static final WeatherSettings DEFAULTS = new WeatherSettings(true, WorldFilter.ALL);

    public boolean affects(final String world) {
        return this.rainDisabled && this.worlds.affects(world);
    }

    public static WeatherSettings from(final ConfigurationSection c, final Consumer<String> warn) {
        final WeatherSettings d = DEFAULTS;
        if (c.isSet("weather") && !c.isConfigurationSection("weather")) {
            warn.accept("weather: должен быть разделом (disabled, worlds), беру значения по умолчанию");
            return d;
        }
        boolean disabled = d.rainDisabled();
        if (c.isSet("weather.disabled")) {
            if (c.isBoolean("weather.disabled")) {
                disabled = c.getBoolean("weather.disabled");
            } else {
                warn.accept("weather.disabled: не true/false, беру " + disabled);
            }
        }
        final List<String> worlds = new ArrayList<>();
        for (final Object o : c.getList("weather.worlds", List.of())) {
            if (o != null) {
                worlds.add(o.toString());
            }
        }
        return new WeatherSettings(disabled, WorldFilter.of(worlds));
    }

    public static WeatherSettings parse(final String yaml, final Consumer<String> warn) throws Exception {
        final YamlConfiguration c = new YamlConfiguration();
        c.loadFromString(yaml);
        return from(c, warn);
    }
}
