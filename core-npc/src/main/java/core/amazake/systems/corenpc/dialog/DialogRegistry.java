package core.amazake.systems.corenpc.dialog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/** Диалоги из папки dialogs/ (включая подпапки: id — путь без .yml через «/»). Снимок неизменяемый. */
public final class DialogRegistry {

    private volatile Map<String, Dialog> dialogs = Map.of();

    public Dialog get(final String id) {
        return id == null ? null : this.dialogs.get(id);
    }

    public List<String> ids() {
        return new ArrayList<>(this.dialogs.keySet());
    }

    public int size() {
        return this.dialogs.size();
    }

    /** Прочитать папку; проблемы — в лог. Возвращает число загруженных. */
    public int load(final Path dir, final Logger log) {
        final Map<String, Dialog> out = new TreeMap<>();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.walk(dir)) {
                for (final Path f : files.filter(p -> p.toString().endsWith(".yml")).sorted().toList()) {
                    final String rel = dir.relativize(f).toString().replace('\\', '/');
                    final String id = rel.substring(0, rel.length() - 4);
                    final YamlConfiguration y = new YamlConfiguration();
                    try {
                        y.loadFromString(Files.readString(f, StandardCharsets.UTF_8));
                    } catch (final IOException | InvalidConfigurationException e) {
                        log.warning("dialogs/" + rel + ": не прочитан: " + e.getMessage());
                        continue;
                    }
                    final DialogParser.Result r = DialogParser.parse(id, y);
                    r.errors().forEach(e -> log.warning("dialogs/" + rel + ": ОШИБКА: " + e));
                    r.warnings().forEach(w -> log.warning("dialogs/" + rel + ": " + w));
                    out.put(id, r.dialog());
                }
            } catch (final IOException e) {
                log.warning("dialogs/: не прочитана папка: " + e.getMessage());
            }
        }
        DialogParser.validateExternal(out).forEach(e -> log.warning("dialogs: ОШИБКА: " + e));
        this.dialogs = Map.copyOf(out);
        return out.size();
    }
}
