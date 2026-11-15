package core.amazake.systems.coreworld.explosion;

import core.amazake.systems.api.SystemContext;
import core.amazake.systems.coreworld.module.WorldModule;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;

/**
 * Модуль {@code explosions}: ТНТ, криперы, дракон (и что ещё в {@code sources}) не ломают блоки. Урон по сущностям и
 * отбрасывание остаются — из взрыва убирается только список блоков. Дракон ломает блоки, пролетая сквозь них, —
 * это тоже {@link EntityExplodeEvent}. Состояние не хранится: события приходят в поток региона, читаем только
 * неизменяемые настройки.
 */
public final class ExplosionModule implements WorldModule, Listener {

    private final AtomicReference<ExplosionSettings> settings = new AtomicReference<>(ExplosionSettings.DEFAULTS);

    @Override
    public String id() {
        return "explosions";
    }

    @Override
    public void enable(final SystemContext context) {
        context.registerListener(this);
    }

    @Override
    public void configure(final ConfigurationSection config, final Consumer<String> warn) {
        this.settings.set(ExplosionSettings.from(config, warn));
    }

    @Override
    public void disable() {
    }

    @Override
    public boolean active() {
        return this.settings.get().enabled();
    }

    @Override
    public List<String> status() {
        final ExplosionSettings s = this.settings.get();
        return List.of(s.enabled()
            ? "<gray>explosions:</gray> <green>не ломают блоки</green> <gray>(" + String.join(", ", s.sources())
                + (s.blockExplosions() ? ", кровати/якоря" : "") + "; " + s.worlds().describe() + ")</gray>"
            : "<gray>explosions:</gray> <yellow>выключен, взрывы ванильные</yellow>");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(final EntityExplodeEvent event) {
        if (this.settings.get().protects(event.getEntity().getWorld().getName(), event.getEntityType().name())) {
            event.blockList().clear();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(final BlockExplodeEvent event) {
        if (this.settings.get().protectsBlockExplosion(event.getBlock().getWorld().getName())) {
            event.blockList().clear();
        }
    }

    /** Дракон и прочие из списка, меняющие блоки сами (не взрывом). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChangeBlock(final EntityChangeBlockEvent event) {
        final Entity e = event.getEntity();
        if (e.getType() != org.bukkit.entity.EntityType.FALLING_BLOCK
            && this.settings.get().protects(e.getWorld().getName(), e.getType().name())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHangingBreak(final HangingBreakByEntityEvent event) {
        final ExplosionSettings s = this.settings.get();
        if (!s.protectHanging() || event.getCause() != HangingBreakEvent.RemoveCause.EXPLOSION) {
            return;
        }
        final Entity remover = event.getRemover();   // при взрыве — сама взорвавшаяся сущность (ТНТ, крипер)
        if (remover != null && s.protects(event.getEntity().getWorld().getName(), remover.getType().name())) {
            event.setCancelled(true);
        }
    }
}
