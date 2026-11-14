package core.amazake.systems.coreworld.module;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Какие миры затрагивает модуль: пустой список — все миры, иначе только перечисленные. Имена сравниваются без учёта
 * регистра (как {@code Bukkit.getWorld}). Неизменяемый, можно читать из любого потока.
 */
public record WorldFilter(Set<String> worlds) {

    /** Все миры. */
    public static final WorldFilter ALL = new WorldFilter(Set.of());

    public WorldFilter {
        worlds = Set.copyOf(worlds);
    }

    /** Фильтр из списка имён; пустые строки пропускаются, регистр и пробелы по краям не важны. */
    public static WorldFilter of(final Collection<String> names) {
        final Set<String> out = new LinkedHashSet<>();
        for (final String name : names) {
            final String key = key(name);
            if (key != null) {
                out.add(key);
            }
        }
        return out.isEmpty() ? ALL : new WorldFilter(out);
    }

    /** Затронут ли мир с таким именем. */
    public boolean affects(final String worldName) {
        if (this.worlds.isEmpty()) {
            return true;
        }
        final String key = key(worldName);
        return key != null && this.worlds.contains(key);
    }

    public boolean allWorlds() {
        return this.worlds.isEmpty();
    }

    /** «все миры» или «world, world_nether». */
    public String describe() {
        return this.worlds.isEmpty() ? "все миры" : String.join(", ", this.worlds.stream().sorted().toList());
    }

    private static String key(final String name) {
        if (name == null) {
            return null;
        }
        final String trimmed = name.trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
    }
}
