package core.amazake.systems.coremobs.world;

import core.amazake.systems.api.SystemContext;
import core.amazake.systems.coremobs.util.YamlFiles;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;

/** Миры, где ванильные мобы выключены ({@code /mobs world disable}). Хранятся в worlds.yml. */
public final class WorldRules {

    private static final List<String> HEADER = List.of(
        "Миры, где выключены ванильные мобы: остаются только мобы core-mobs (и причины спавна из",
        "worlds.allowed-spawn-reasons в config.yml). Пишут команды /mobs world disable|enable <мир>.");

    private final SystemContext context;
    private final Path file;
    private final Set<String> disabled = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean dirty = new AtomicBoolean();
    private final Object ioLock = new Object();

    public WorldRules(final SystemContext context) {
        this.context = context;
        this.file = context.dataFolder().resolve("worlds.yml");
    }

    public void load() {
        synchronized (this.ioLock) {
            try {
                final YamlConfiguration yaml = YamlFiles.read(this.file);
                this.disabled.clear();
                this.disabled.addAll(yaml.getStringList("vanilla-mobs-disabled"));
            } catch (final Exception e) {
                this.context.logger().log(Level.WARNING, "worlds.yml не прочитан", e);
            }
        }
    }

    public boolean isDisabled(final String world) {
        return this.disabled.contains(world);
    }

    public Set<String> disabled() {
        return Set.copyOf(this.disabled);
    }

    /** true — состояние изменилось. */
    public boolean set(final String world, final boolean vanillaDisabled) {
        final boolean changed = vanillaDisabled ? this.disabled.add(world) : this.disabled.remove(world);
        if (changed) {
            this.dirty.set(true);
            Bukkit.getAsyncScheduler().runNow(this.context.plugin(), t -> this.saveNow());
        }
        return changed;
    }

    public void saveNow() {
        synchronized (this.ioLock) {
            if (!this.dirty.getAndSet(false)) {
                return;
            }
            final YamlConfiguration yaml = new YamlConfiguration();
            yaml.options().setHeader(HEADER);
            yaml.set("vanilla-mobs-disabled", List.copyOf(new TreeSet<>(this.disabled)));
            try {
                YamlFiles.write(this.file, yaml);
            } catch (final Exception e) {
                this.dirty.set(true);
                this.context.logger().log(Level.WARNING, "Не удалось сохранить worlds.yml", e);
            }
        }
    }
}
