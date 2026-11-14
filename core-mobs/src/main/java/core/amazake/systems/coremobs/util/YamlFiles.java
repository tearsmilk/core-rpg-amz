package core.amazake.systems.coremobs.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/** Чтение и атомарная запись YAML: пишем во временный файл и переименовываем поверх старого. */
public final class YamlFiles {

    private YamlFiles() {
    }

    public static YamlConfiguration read(final Path file) throws IOException, InvalidConfigurationException {
        final YamlConfiguration yaml = new YamlConfiguration();
        if (Files.isRegularFile(file)) {
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        }
        return yaml;
    }

    public static void write(final Path file, final YamlConfiguration yaml) throws IOException {
        Files.createDirectories(file.getParent());
        final Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, yaml.saveToString(), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
