package core.amazake.systems.corerpg;

import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;

/** config.yml: общие настройки системы. Неизменяемый снимок. */
public record GeneralSettings(String prefix, int saveIntervalMinutes, boolean papiEnabled, String papiIdentifier) {

    public static final GeneralSettings DEFAULTS = new GeneralSettings(
        "<dark_gray>[<gradient:#DC143C:#FFB6C1>CoreRpg</gradient>]</dark_gray> ", 2, true, "core");

    public static GeneralSettings from(final ConfigurationSection config, final Consumer<String> warn) {
        final String prefix = config.getString("messages.prefix", DEFAULTS.prefix());
        int interval = config.getInt("settings.save-interval-minutes", DEFAULTS.saveIntervalMinutes());
        if (interval < 1 || interval > 60) {
            warn.accept("settings.save-interval-minutes: " + interval + " вне 1..60, беру " + DEFAULTS.saveIntervalMinutes());
            interval = DEFAULTS.saveIntervalMinutes();
        }
        final boolean papi = config.getBoolean("placeholderapi.enabled", DEFAULTS.papiEnabled());
        String id = config.getString("placeholderapi.identifier", DEFAULTS.papiIdentifier());
        if (id == null || !id.matches("[a-z0-9_]{1,32}")) {
            warn.accept("placeholderapi.identifier: «" + id + "» — нужны a-z, 0-9, _ (до 32), беру core");
            id = DEFAULTS.papiIdentifier();
        }
        return new GeneralSettings(prefix, interval, papi, id);
    }
}
