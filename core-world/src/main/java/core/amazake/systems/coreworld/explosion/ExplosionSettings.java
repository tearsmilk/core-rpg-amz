package core.amazake.systems.coreworld.explosion;

import core.amazake.systems.coreworld.module.WorldFilter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Раздел {@code explosions} config.yml.
 *
 * @param enabled         {@code explosions.protect-blocks}: взрывы и мобы из {@code sources} не ломают блоки
 * @param sources         {@code explosions.sources}: типы сущностей (EntityType, в верхнем регистре)
 * @param blockExplosions {@code explosions.block-explosions}: и взрывы блоков (кровать в аду, якорь возрождения)
 * @param protectHanging  {@code explosions.protect-hanging}: картины и рамки тоже не срываются этими взрывами
 * @param worlds          {@code explosions.worlds}: где; пустой список — везде
 */
public record ExplosionSettings(boolean enabled, Set<String> sources, boolean blockExplosions, boolean protectHanging,
                                WorldFilter worlds) {

    public static final List<String> DEFAULT_SOURCES = List.of("TNT", "TNT_MINECART", "CREEPER", "ENDER_DRAGON");

    public static final ExplosionSettings DEFAULTS =
        new ExplosionSettings(true, Set.copyOf(DEFAULT_SOURCES), false, true, WorldFilter.ALL);

    public ExplosionSettings {
        sources = Set.copyOf(sources);
    }

    /** Защищены ли блоки мира {@code world} от сущности типа {@code entityType} (имя EntityType). */
    public boolean protects(final String world, final String entityType) {
        return this.enabled && entityType != null && this.sources.contains(entityType.toUpperCase(Locale.ROOT))
            && this.worlds.affects(world);
    }

    /** Защищены ли блоки мира от взрыва блока (кровать, якорь возрождения). */
    public boolean protectsBlockExplosion(final String world) {
        return this.enabled && this.blockExplosions && this.worlds.affects(world);
    }

    public static ExplosionSettings from(final ConfigurationSection c, final Consumer<String> warn) {
        final ExplosionSettings d = DEFAULTS;
        if (c.isSet("explosions") && !c.isConfigurationSection("explosions")) {
            warn.accept("explosions: должен быть разделом (protect-blocks, sources, ...), беру значения по умолчанию");
            return d;
        }
        final boolean enabled = bool(c, "explosions.protect-blocks", d.enabled(), warn);
        final boolean blocks = bool(c, "explosions.block-explosions", d.blockExplosions(), warn);
        final boolean hanging = bool(c, "explosions.protect-hanging", d.protectHanging(), warn);
        final Set<String> sources = new LinkedHashSet<>();
        if (c.isSet("explosions.sources")) {
            for (final Object o : c.getList("explosions.sources", List.of())) {
                final String s = o == null ? "" : o.toString().trim().toUpperCase(Locale.ROOT).replace("MINECRAFT:", "");
                if (!s.isEmpty()) {
                    sources.add(s);
                }
            }
        } else {
            sources.addAll(DEFAULT_SOURCES);
        }
        final List<String> worlds = new ArrayList<>();
        for (final Object o : c.getList("explosions.worlds", List.of())) {
            if (o != null) {
                worlds.add(o.toString());
            }
        }
        return new ExplosionSettings(enabled, sources, blocks, hanging, WorldFilter.of(worlds));
    }

    private static boolean bool(final ConfigurationSection c, final String path, final boolean def,
                                final Consumer<String> warn) {
        if (!c.isSet(path)) {
            return def;
        }
        if (c.isBoolean(path)) {
            return c.getBoolean(path);
        }
        warn.accept(path + ": не true/false, беру " + def);
        return def;
    }

    public static ExplosionSettings parse(final String yaml, final Consumer<String> warn) throws Exception {
        final YamlConfiguration c = new YamlConfiguration();
        c.loadFromString(yaml);
        return from(c, warn);
    }
}
