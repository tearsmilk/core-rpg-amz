package core.amazake.systems.coreworld.spawn;

import core.amazake.systems.coreworld.module.WorldFilter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;

/**
 * Раздел {@code spawn} config.yml. Неизменяемый снимок.
 *
 * @param blocked        {@code spawn.blocked}: какие мобы не появляются
 * @param worlds         {@code spawn.worlds}: где; пустой список — везде
 * @param allowReasons   {@code spawn.allow-reasons}: причины появления, которые всё равно пропускаются
 * @param removeExisting {@code spawn.remove-existing}: убирать уже живущих таких мобов при загрузке чанка
 */
public record SpawnSettings(Set<EntityType> blocked, WorldFilter worlds, Set<SpawnReason> allowReasons,
                            boolean removeExisting) {

    public static final SpawnSettings DEFAULTS = new SpawnSettings(Set.of(EntityType.BAT), WorldFilter.ALL,
        Set.of(SpawnReason.COMMAND, SpawnReason.CUSTOM), true);

    public SpawnSettings {
        blocked = blocked.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(blocked));
        allowReasons = allowReasons.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(allowReasons));
    }

    /** Нужно ли отменить появление моба {@code type} по причине {@code reason} в мире {@code world}. */
    public boolean blocks(final EntityType type, final SpawnReason reason, final String world) {
        return this.blocked.contains(type) && !this.allowReasons.contains(reason) && this.worlds.affects(world);
    }

    /** Убрать ли уже живущего моба при загрузке чанка. */
    public boolean removes(final EntityType type, final String world) {
        return this.removeExisting && this.blocked.contains(type) && this.worlds.affects(world);
    }

    public static SpawnSettings from(final ConfigurationSection config, final Consumer<String> warn) {
        final SpawnSettings d = DEFAULTS;
        if (config.isSet("spawn") && !config.isConfigurationSection("spawn")) {
            warn.accept("spawn: должен быть разделом (blocked, worlds, allow-reasons, remove-existing), беру значения по умолчанию");
            return d;
        }
        final Set<EntityType> blocked = config.isSet("spawn.blocked")
            ? names(config, "spawn.blocked", EntityType.class, warn) : d.blocked();
        final Set<SpawnReason> reasons = config.isSet("spawn.allow-reasons")
            ? names(config, "spawn.allow-reasons", SpawnReason.class, warn) : d.allowReasons();
        final List<String> worlds = new ArrayList<>();
        for (final Object o : config.getList("spawn.worlds", List.of())) {
            if (o != null) {
                worlds.add(o.toString());
            }
        }
        boolean remove = d.removeExisting();
        if (config.isSet("spawn.remove-existing")) {
            if (config.isBoolean("spawn.remove-existing")) {
                remove = config.getBoolean("spawn.remove-existing");
            } else {
                warn.accept("spawn.remove-existing: не true/false, беру " + remove);
            }
        }
        return new SpawnSettings(blocked, WorldFilter.of(worlds), reasons, remove);
    }

    public static SpawnSettings parse(final String yaml, final Consumer<String> warn) throws Exception {
        final YamlConfiguration c = new YamlConfiguration();
        c.loadFromString(yaml);
        return from(c, warn);
    }

    private static <E extends Enum<E>> Set<E> names(final ConfigurationSection c, final String key,
                                                    final Class<E> type, final Consumer<String> warn) {
        final Set<E> out = EnumSet.noneOf(type);
        for (final String raw : c.getStringList(key)) {
            try {
                out.add(Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT)));
            } catch (final IllegalArgumentException e) {
                warn.accept(key + ": «" + raw + "» — нет такого, пропускаю");
            }
        }
        return out;
    }
}
