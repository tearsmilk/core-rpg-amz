package core.amazake.systems.coremobs;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Общие настройки из config.yml. Неизменяемый снимок: reload подменяет его целиком. */
public record Settings(
    String prefix,
    Material wandMaterial,
    String wandName,
    List<String> wandLore,
    int maxPoints,
    int defaultHeight,
    int defaultDespawnRadius,
    long particleIntervalTicks,
    double particleSpacing,
    double particleViewDistance,
    int particleMaxPerDraw,
    float particleSize,
    Color regionColor,
    Color selectionColor,
    Color vertexColor,
    long checkIntervalTicks,
    String nameFormat,
    int adoptRadius
) {

    public static final Settings DEFAULTS = new Settings(
        "<dark_gray>[<green>CoreMobs</green>]</dark_gray> ",
        Material.GOLDEN_AXE,
        "<green>Топорик региона</green>",
        List.of("<gray>ЛКМ по блоку — поставить точку</gray>", "<gray>ПКМ по блоку — убрать последнюю точку</gray>",
            "<gray>Shift + ПКМ — сбросить выделение</gray>", "<gray>/mobs region create <имя> — создать регион</gray>"),
        256, 5, 5,
        10L, 0.5, 48.0, 1500, 1.2f,
        Color.fromRGB(0x00C853), Color.fromRGB(0x76FF03), Color.fromRGB(0xFFFFFF),
        20L,
        "<name> <red>❤ <health>/<max_health></red>",
        128);

    public static Settings from(final ConfigurationSection c, final Consumer<String> warn) {
        final Settings d = DEFAULTS;
        Material wand = Material.matchMaterial(c.getString("wand.material", d.wandMaterial().name()));
        if (wand == null || !wand.isItem() || wand.isAir()) {
            warn.accept("wand.material: «" + c.getString("wand.material") + "» не предмет, беру " + d.wandMaterial());
            wand = d.wandMaterial();
        }
        final List<String> lore = c.isList("wand.lore") ? List.copyOf(c.getStringList("wand.lore")) : d.wandLore();
        return new Settings(
            c.getString("messages.prefix", d.prefix()),
            wand,
            c.getString("wand.name", d.wandName()),
            lore,
            clamp(c.getInt("selection.max-points", d.maxPoints()), 3, 4096, "selection.max-points", warn),
            clamp(c.getInt("regions.default-height", d.defaultHeight()), 0, 512, "regions.default-height", warn),
            clamp(c.getInt("regions.default-despawn-radius", d.defaultDespawnRadius()), -1, 512, "regions.default-despawn-radius", warn),
            clamp(c.getInt("particles.interval-ticks", (int) d.particleIntervalTicks()), 2, 200, "particles.interval-ticks", warn),
            clampD(c.getDouble("particles.spacing", d.particleSpacing()), 0.1, 5.0, "particles.spacing", warn),
            clampD(c.getDouble("particles.view-distance", d.particleViewDistance()), 4.0, 256.0, "particles.view-distance", warn),
            clamp(c.getInt("particles.max-per-draw", d.particleMaxPerDraw()), 50, 20000, "particles.max-per-draw", warn),
            (float) clampD(c.getDouble("particles.size", d.particleSize()), 0.2, 4.0, "particles.size", warn),
            color(c.getString("particles.region-color"), d.regionColor(), "particles.region-color", warn),
            color(c.getString("particles.selection-color"), d.selectionColor(), "particles.selection-color", warn),
            color(c.getString("particles.vertex-color"), d.vertexColor(), "particles.vertex-color", warn),
            clamp(c.getInt("spawning.check-interval-ticks", (int) d.checkIntervalTicks()), 5, 1200, "spawning.check-interval-ticks", warn),
            c.getString("mobs.name-format", d.nameFormat()),
            clamp(c.getInt("mobs.adopt-radius", d.adoptRadius()), 16, 512, "mobs.adopt-radius", warn));
    }

    public static Settings parse(final String yaml, final Consumer<String> warn) throws Exception {
        final YamlConfiguration c = new YamlConfiguration();
        c.loadFromString(yaml);
        return from(c, warn);
    }

    private static int clamp(final int v, final int min, final int max, final String key, final Consumer<String> warn) {
        if (v < min || v > max) {
            final int fixed = Math.max(min, Math.min(max, v));
            warn.accept(key + ": " + v + " вне " + min + ".." + max + ", беру " + fixed);
            return fixed;
        }
        return v;
    }

    private static double clampD(final double v, final double min, final double max, final String key,
                                 final Consumer<String> warn) {
        if (!(v >= min && v <= max)) {
            final double fixed = Double.isNaN(v) ? min : Math.max(min, Math.min(max, v));
            warn.accept(key + ": " + v + " вне " + min + ".." + max + ", беру " + fixed);
            return fixed;
        }
        return v;
    }

    /** «#00C853» или «00C853». */
    static Color color(final String raw, final Color fallback, final String key, final Consumer<String> warn) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        final String hex = raw.trim().toLowerCase(Locale.ROOT).replace("#", "");
        try {
            if (hex.length() != 6) {
                throw new NumberFormatException();
            }
            return Color.fromRGB(Integer.parseInt(hex, 16));
        } catch (final NumberFormatException e) {
            warn.accept(key + ": «" + raw + "» не цвет вида #RRGGBB");
            return fallback;
        }
    }
}
