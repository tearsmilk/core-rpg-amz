package core.amazake.systems.corerpg.combat;

import core.amazake.systems.api.SystemContext;
import core.amazake.systems.corerpg.CoreRpgSystem;
import core.amazake.systems.corerpg.aspect.Aspect;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Регенерация по аспекту {@code regen} (HP/с): задача в потоке каждого игрока. Стартует для онлайн-игроков при
 * включении и при входе; кончается при выходе (ядро) или выключении системы.
 */
final class RegenTasks implements Listener {

    private final CoreRpgSystem system;
    private final CombatModule module;
    private final Map<UUID, ScheduledTask> tasks = new ConcurrentHashMap<>();
    private volatile long period;

    RegenTasks(final CoreRpgSystem system, final CombatModule module) {
        this.system = system;
        this.module = module;
    }

    /** Запустить для всех онлайн. Глобальный поток. */
    void startAll(final long periodTicks) {
        this.period = periodTicks;
        for (final Player player : Bukkit.getOnlinePlayers()) {
            this.start(player);
        }
    }

    /** Перезапустить, если поменялся период. */
    void reload(final long periodTicks) {
        if (periodTicks == this.period && !this.tasks.isEmpty()) {
            return;
        }
        this.stopAll();
        this.startAll(periodTicks);
    }

    void stopAll() {
        // По одной записи: отменяем ровно то, что сняли. clear() мог бы выкинуть задачу, которую onJoin в потоке
        // игрока положил между обходом и очисткой, не отменив её (двойная регенерация).
        for (final UUID id : this.tasks.keySet()) {
            final ScheduledTask task = this.tasks.remove(id);
            if (task != null) {
                task.cancel();
            }
        }
    }

    private void start(final Player player) {
        final SystemContext context = this.system.context();
        final long periodTicks = this.period;
        final ScheduledTask task = context.runForPlayer(player, t -> this.tick(player, periodTicks), periodTicks);
        if (task != null) {
            final ScheduledTask old = this.tasks.put(player.getUniqueId(), task);
            if (old != null) {
                old.cancel();
            }
        }
    }

    /** Поток игрока. */
    private void tick(final Player player, final long periodTicks) {
        final CombatSettings.Regen regen = this.module.settings().regen();
        if (!regen.enabled() || !player.isOnline() || player.isDead()) {
            return;
        }
        final double perSecond = this.system.aspects().get(player.getUniqueId()).get(Aspect.REGEN);
        if (!(perSecond > 0.0)) {
            return;
        }
        final double health = player.getHealth();
        if (health <= 0.0) {
            return;
        }
        final double next = CombatMath.regenHealth(health, CombatListener.maxHealth(player), perSecond, periodTicks);
        if (next > health) {
            player.setHealth(next);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        this.start(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        final ScheduledTask task = this.tasks.remove(event.getPlayer().getUniqueId());
        if (task != null) {
            task.cancel();
        }
    }
}
