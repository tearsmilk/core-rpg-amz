package dev.tearsmilk.aurahud.layout;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class Presets {

    public static final List<String> BUILT_IN = List.of("vanilla", "left-column", "right-column", "grid-corner", "compact", "sides", "minimal");
    public static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");

    private final Path dir;

    public Presets(Path dir) {
        this.dir = dir;
    }

    public static YamlConfiguration builtIn(String name) {
        if (!BUILT_IN.contains(name)) {
            return null;
        }
        try (InputStream in = Presets.class.getClassLoader().getResourceAsStream("presets/" + name + ".yml")) {
            if (in == null) {
                return null;
            }
            YamlConfiguration y = new YamlConfiguration();
            y.loadFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            return y;
        } catch (IOException | InvalidConfigurationException e) {
            return null;
        }
    }

    public List<String> names() {
        TreeSet<String> custom = new TreeSet<>();
        if (dir != null && Files.isDirectory(dir)) {
            try (Stream<Path> s = Files.list(dir)) {
                for (Path p : s.toList()) {
                    String f = p.getFileName().toString();
                    if (f.endsWith(".yml") && NAME.matcher(f.substring(0, f.length() - 4)).matches()) {
                        custom.add(f.substring(0, f.length() - 4));
                    }
                }
            } catch (IOException ignored) {
                custom.clear();
            }
        }
        List<String> out = new ArrayList<>(BUILT_IN);
        for (String c : custom) {
            if (!out.contains(c)) {
                out.add(c);
            }
        }
        return out;
    }

    public YamlConfiguration load(String raw) {
        String name = raw.toLowerCase(Locale.ROOT);
        if (!NAME.matcher(name).matches()) {
            return null;
        }
        if (dir != null) {
            Path p = dir.resolve(name + ".yml");
            if (Files.isRegularFile(p)) {
                YamlConfiguration y = new YamlConfiguration();
                try {
                    y.loadFromString(Files.readString(p, StandardCharsets.UTF_8));
                    return y;
                } catch (IOException | InvalidConfigurationException e) {
                    return null;
                }
            }
        }
        return builtIn(name);
    }

    public Path save(String raw, ConfigurationSection hud, String description) throws IOException {
        String name = raw.toLowerCase(Locale.ROOT);
        if (dir == null || !NAME.matcher(name).matches()) {
            throw new IOException("bad preset name " + raw);
        }
        YamlConfiguration y = new YamlConfiguration();
        y.set("description", description);
        y.createSection("hud");
        copy(hud, y, "hud");
        Files.createDirectories(dir);
        Path p = dir.resolve(name + ".yml");
        Files.writeString(p, y.saveToString(), StandardCharsets.UTF_8);
        return p;
    }

    public static void copy(ConfigurationSection from, ConfigurationSection to, String path) {
        to.set(path, null);
        to.createSection(path);
        if (from == null) {
            return;
        }
        for (Map.Entry<String, Object> e : from.getValues(true).entrySet()) {
            if (!(e.getValue() instanceof ConfigurationSection)) {
                to.set(path + "." + e.getKey(), e.getValue());
            }
        }
    }
}
