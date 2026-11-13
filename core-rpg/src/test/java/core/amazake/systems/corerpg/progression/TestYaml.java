package core.amazake.systems.corerpg.progression;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/** Помощники тестов: YAML из строки и из ресурсов модуля. */
final class TestYaml {

    private TestYaml() {
    }

    static YamlConfiguration of(final String text) {
        final YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(text);
        } catch (final InvalidConfigurationException e) {
            throw new IllegalStateException(e);
        }
        return yaml;
    }

    static YamlConfiguration resource(final String name) {
        try (InputStream in = TestYaml.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("нет ресурса " + name);
            }
            return of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Собирает предупреждения. */
    static final class Warnings {
        final List<String> list = new ArrayList<>();

        void add(final String w) {
            this.list.add(w);
        }

        boolean any(final String fragment) {
            return this.list.stream().anyMatch(w -> w.contains(fragment));
        }
    }
}
