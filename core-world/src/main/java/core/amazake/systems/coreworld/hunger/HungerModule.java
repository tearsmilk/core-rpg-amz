package core.amazake.systems.coreworld.hunger;

import core.amazake.systems.api.SystemContext;
import core.amazake.systems.coreworld.module.WorldModule;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityExhaustionEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * Модуль {@code hunger}: голода нет. Еда не убывает (понижение уровня еды отменяется, повышение — нет, так что есть
 * можно), истощение не копится, шкала еды держится на 20, насыщение — на 20 (если {@code keep-saturation}), поэтому
 * здоровье восстанавливается как у сытого игрока. С {@code clear-saturation} насыщение, наоборот, держится на 0.
 *
 * <p>Потоки: игрока трогают только его события и задачи {@code runForPlayer}/{@code runOnceForPlayer}. Каждому
 * игроку — своя задача раз в секунду, которая подтягивает еду и насыщение (на случай, если их опустил кто-то другой:
 * команда, другой плагин).</p>
 */
public final class HungerModule implements WorldModule, Listener {

    /** Максимум еды и насыщения в ванилле. */
    static final int FULL_FOOD = 20;
    static final float FULL_SATURATION = 20.0f;
    /** Как часто задача игрока подтягивает еду и насыщение, тиков. */
    static final long REFRESH_TICKS = 20L;

    private final AtomicReference<HungerSettings> settings = new AtomicReference<>(HungerSettings.DEFAULTS);
    private final Map<UUID, ScheduledTask> tasks = new ConcurrentHashMap<>();
    private volatile SystemContext context;

    @Override
    public String id() {
        return "hunger";
    }

    @Override
    public void enable(final SystemContext context) {
        this.context = context;
        context.registerListener(this);
    }

    @Override
    public void configure(final ConfigurationSection config, final Consumer<String> warn) {
        final HungerSettings s = HungerSettings.from(config, warn);
        this.settings.set(s);
        if (s.hungerDisabled() && !s.worlds().allWorlds()) {
            for (final String name : s.worlds().worlds()) {
                if (Bukkit.getWorld(name) == null) {
                    warn.accept("hunger.worlds: мир «" + name + "» сейчас не загружен");
                }
            }
        }
        // Глобальный поток: игроков не трогаем, а ставим каждому задачу в его поток.
        for (final Player player : Bukkit.getOnlinePlayers()) {
            if (s.hungerDisabled()) {
                this.start(player);
                this.context.runOnceForPlayer(player, () -> this.apply(player));
            } else {
                this.stop(player.getUniqueId());
            }
        }
        if (!s.hungerDisabled()) {
            this.stopAll();
        }
    }

    @Override
    public void disable() {
        // Еду и насыщение не трогаем: дальше игрок просто снова начнёт голодать с полной шкалы.
        this.stopAll();
    }

    @Override
    public boolean active() {
        return this.settings.get().hungerDisabled();
    }

    @Override
    public List<String> status() {
        final HungerSettings s = this.settings.get();
        if (!s.hungerDisabled()) {
            return List.of("<gray>hunger:</gray> <red>выключен</red> <gray>(обычный голод)</gray>");
        }
        return List.of(
            "<gray>hunger:</gray> <green>включён</green> <gray>— голода нет</gray>",
            "<gray>  миры:</gray> <white>" + s.worlds().describe() + "</white>",
            "<gray>  насыщение держится полным:</gray> <white>" + (s.clearSaturation() ? "нет (всегда 0)" : (s.keepSaturation() ? "да" : "нет")) + "</white>",
            "<gray>  игроков с задачей:</gray> <white>" + this.tasks.size() + "</white>");
    }

    // --- события (поток этого игрока) ---

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFoodChange(final FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && this.affects(player)
            && event.getFoodLevel() < player.getFoodLevel()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExhaustion(final EntityExhaustionEvent event) {
        if (event.getEntity() instanceof Player player && this.affects(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        if (this.settings.get().hungerDisabled()) {
            this.start(player);
            this.apply(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(final PlayerRespawnEvent event) {
        final Player player = event.getPlayer();
        if (this.settings.get().hungerDisabled()) {
            // Еду и насыщение после смерти игра выставляет сама уже после события — подтягиваем следующим тиком.
            this.start(player);
            this.context.runOnceForPlayer(player, () -> this.apply(player));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(final PlayerChangedWorldEvent event) {
        this.apply(event.getPlayer());
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        this.stop(event.getPlayer().getUniqueId());
    }

    // --- игрок (только его поток) ---

    /** Полная еда, полное (или нулевое при clear-saturation) насыщение, нулевое истощение — если игрок в затронутом мире. Поток игрока. */
    void apply(final Player player) {
        if (!player.isOnline() || player.isDead() || !this.affects(player)) {
            return;
        }
        if (player.getFoodLevel() < FULL_FOOD) {
            player.setFoodLevel(FULL_FOOD);
        }
        // clear-saturation важнее keep-saturation: насыщение 0 — еда не падает, но и быстрой регенерации от
        // сытости нет.
        if (this.settings.get().clearSaturation() && player.getSaturation() != 0.0f) {
            player.setSaturation(0.0f);
        } else if (!this.settings.get().clearSaturation() && this.settings.get().keepSaturation()
            && player.getSaturation() < FULL_SATURATION) {
            player.setSaturation(FULL_SATURATION);
        }
        if (player.getExhaustion() > 0.0f) {
            player.setExhaustion(0.0f);
        }
    }

    private boolean affects(final Player player) {
        final World world = player.getWorld();
        return this.settings.get().affects(world.getName());
    }

    // --- задачи ---

    /** Задача игрока раз в {@link #REFRESH_TICKS} тиков; старая задача этого игрока отменяется. Любой поток. */
    private void start(final Player player) {
        final ScheduledTask task = this.context.runForPlayer(player, t -> this.apply(player), REFRESH_TICKS);
        if (task != null) {
            final ScheduledTask old = this.tasks.put(player.getUniqueId(), task);
            if (old != null) {
                old.cancel();
            }
        }
    }

    private void stop(final UUID id) {
        final ScheduledTask task = this.tasks.remove(id);
        if (task != null) {
            task.cancel();
        }
    }

    private void stopAll() {
        for (final UUID id : List.copyOf(this.tasks.keySet())) {
            this.stop(id);
        }
    }
}
