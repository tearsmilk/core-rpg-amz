package core.amazake.systems.corerpg.util;

import core.amazake.systems.api.SystemContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/** Загрузка файлов настроек системы: config.yml через ядро, остальные — из папки системы. */
public final class Configs {

    private Configs() {
    }

    /**
     * {@code systems/core-rpg/<name>}: копируется из .azs при первом обращении, затем читается заново. Ошибка YAML —
     * {@link IllegalStateException} с именем файла (в enable система не включится, в reload — выключится, как требует
     * гайд), отсутствие файла и в .azs — пустая конфигурация.
     */
    public static YamlConfiguration load(final SystemContext context, final String name) {
        if ("config.yml".equals(name)) {
            return context.loadConfig();
        }
        context.saveDefaultResource(name);
        final Path file = context.dataFolder().resolve(name);
        final YamlConfiguration yaml = new YamlConfiguration();
        if (!Files.isRegularFile(file)) {
            return yaml;
        }
        try {
            yaml.loadFromString(Files.readString(file));
        } catch (final IOException e) {
            throw new IllegalStateException("Не удалось прочитать " + name + ": " + e.getMessage(), e);
        } catch (final InvalidConfigurationException e) {
            throw new IllegalStateException("Ошибка YAML в " + name + ": " + e.getMessage(), e);
        }
        return yaml;
    }
}
