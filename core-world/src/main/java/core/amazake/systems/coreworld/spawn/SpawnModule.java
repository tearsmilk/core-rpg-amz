package core.amazake.systems.coreworld.spawn;

import core.amazake.systems.api.SystemContext;
import core.amazake.systems.coreworld.module.WorldModule;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.world.EntitiesLoadEvent;

/**
 * Модуль {@code spawn}: запрет появления выбранных мобов (по умолчанию летучих мышей) на всём сервере.
 * Отменяется любое появление, кроме причин из {@code allow-reasons} (команда /summon, плагины). Уже живущие такие
 * мобы убираются, когда загружается их чанк. Оба события приходят в потоке региона этих сущностей — там их и трогаем.
 */
public final class SpawnModule implements WorldModule, Listener {

    private final AtomicReference<SpawnSettings> settings = new AtomicReference<>(SpawnSettings.DEFAULTS);

    @Override
    public String id() {
        return "spawn";
    }

    @Override
    public void enable(final SystemContext context) {
        context.registerListener(this);
    }

    @Override
    public void configure(final ConfigurationSection config, final Consumer<String> warn) {
        this.settings.set(SpawnSettings.from(config, warn));
    }

    @Override
    public void disable() {
        // Своих задач и состояния у игроков нет.
    }

    @Override
    public boolean active() {
        return !this.settings.get().blocked().isEmpty();
    }

    @Override
    public List<String> status() {
        final SpawnSettings s = this.settings.get();
        if (s.blocked().isEmpty()) {
            return List.of("<gray>spawn:</gray> <yellow>ничего не запрещено</yellow>");
        }
        final String mobs = s.blocked().stream().map(t -> t.name().toLowerCase()).sorted().collect(Collectors.joining(", "));
        return List.of("<gray>spawn:</gray> <green>не появляются: " + mobs + "</green> <gray>(" + s.worlds().describe()
            + (s.removeExisting() ? ", старые убираются" : "") + ")</gray>");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpawn(final CreatureSpawnEvent event) {
        if (this.settings.get().blocks(event.getEntityType(), event.getSpawnReason(), event.getLocation().getWorld().getName())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onEntitiesLoad(final EntitiesLoadEvent event) {
        final SpawnSettings s = this.settings.get();
        if (!s.removeExisting() || s.blocked().isEmpty()) {
            return;
        }
        final String world = event.getWorld().getName();
        for (final Entity entity : event.getEntities()) {
            if (!(entity instanceof Player) && s.removes(entity.getType(), world) && entity.customName() == null) {
                entity.remove();
            }
        }
    }
}
