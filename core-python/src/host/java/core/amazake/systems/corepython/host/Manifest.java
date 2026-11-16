package core.amazake.systems.corepython.host;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * manifest.yml Python-системы.
 *
 * <pre>
 * name: custom_armor        # = имя папки (латиница, цифры, _ и -)
 * version: 1.0.0
 * main: main.py             # главный файл
 * enabled: true             # загружать при включении core-python
 * description: "..."
 * commands:                 # необязательно: свойства команд по умолчанию для @amazake.command
 *   carmor:
 *     permission: customarmor.admin
 *     aliases: [防具]
 *     description: "..."
 *     usage: "/carmor ..."
 * </pre>
 */
public record Manifest(String name, String version, String main, boolean enabled, String description,
                       Map<String, CommandSpec> commands) {

    /** Имя системы: оно же папка, префикс команд (py-имя:команда) и пространство имён PDC (py_имя). */
    public static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,32}");

    public record CommandSpec(String permission, List<String> aliases, String description, String usage) {
    }

    public static Manifest read(final Path dir) throws Exception {
        final Path file = dir.resolve("manifest.yml");
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("нет " + file);
        }
        final YamlConfiguration y = new YamlConfiguration();
        y.loadFromString(Files.readString(file));
        final String folder = dir.getFileName().toString();
        final String name = y.getString("name", folder);
        if (!name.equals(folder)) {
            throw new IllegalArgumentException("manifest.yml: name «" + name + "» не совпадает с папкой «" + folder + "»");
        }
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("имя «" + name + "»: только a-z, 0-9, _ и -, до 32 символов");
        }
        final String main = y.getString("main", "main.py");
        final Path mainFile = dir.resolve(main).normalize();
        if (!mainFile.startsWith(dir) || !Files.isRegularFile(mainFile)) {
            throw new IllegalArgumentException("manifest.yml: нет главного файла " + main);
        }
        final Map<String, CommandSpec> commands = new LinkedHashMap<>();
        final ConfigurationSection cs = y.getConfigurationSection("commands");
        if (cs != null) {
            for (final String key : cs.getKeys(false)) {
                final ConfigurationSection c = cs.getConfigurationSection(key);
                if (c == null) {
                    continue;
                }
                commands.put(key, new CommandSpec(c.getString("permission"), List.copyOf(c.getStringList("aliases")),
                    c.getString("description", ""), c.getString("usage", "/" + key)));
            }
        }
        return new Manifest(name, y.getString("version", "0"), main, y.getBoolean("enabled", true),
            y.getString("description", ""), java.util.Collections.unmodifiableMap(commands));
    }
}
