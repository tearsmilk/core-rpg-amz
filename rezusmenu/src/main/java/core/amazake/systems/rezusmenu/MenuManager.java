package core.amazake.systems.rezusmenu;

import core.amazake.systems.api.SystemContext;
import core.amazake.systems.rezusmenu.rpg.RpgScreen;
import core.amazake.systems.rezusmenu.screen.Screen;
import core.amazake.systems.rezusmenu.util.Text;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * Открытые экраны игроков. Всё про игрока — в его потоке: события игрока и задача {@code runForPlayer}.
 */
public final class MenuManager implements Listener {

    /** Открытое меню игрока. */
    private static final class Session {
        final Screen screen;
        final RpgScreen rpg;
        volatile ScheduledTask task;
        int ticks;
        int lastDropTick = -10;

        Session(final Screen screen, final RpgScreen rpg) {
            this.screen = screen;
            this.rpg = rpg;
        }
    }

    private final SystemContext context;
    private final Supplier<Settings> settings;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> dropping = ConcurrentHashMap.newKeySet();

    public MenuManager(final SystemContext context, final Supplier<Settings> settings) {
        this.context = context;
        this.settings = settings;
    }

    public boolean isOpen(final Player player) {
        return this.sessions.containsKey(player.getUniqueId());
    }

    /** Открыть меню по id из списка /menuses. Поток игрока. */
    public void open(final Player player, final String id) {
        if (!"rpg".equals(id)) {
            player.sendMessage(Text.mm(Text.prefix() + "<yellow>Это меню ещё не готово.</yellow>"));
            return;
        }
        this.close(player);
        final Settings s = this.settings.get();
        final Screen screen = Screen.open(this.context.plugin(), player, s.distance());
        if (screen == null) {
            player.sendMessage(Text.mm(Text.prefix() + "<red>Сейчас меню не открыть: слезьте с транспорта или смените режим игры.</red>"));
            return;
        }
        final Session session = new Session(screen, new RpgScreen(screen, s, () -> this.close(player), this.dropping));
        this.sessions.put(player.getUniqueId(), session);
        session.task = this.context.runForPlayer(player, task -> this.tick(player, session), 2L);
        if (session.task == null) {
            this.close(player);
        }
    }

    private void tick(final Player player, final Session session) {
        if (session.screen.closed()) {
            return;
        }
        session.ticks += 2;
        if (!session.screen.placed()) {
            // Первый такт: игрок уже сидит, глаза на месте — ставим экран.
            session.screen.place();
            session.rpg.build();
            player.sendActionBar(Text.mm("<gray>Наведите прицел на кнопку и нажмите ЛКМ · Shift — выйти</gray>"));
            return;
        }
        if (!player.isInsideVehicle() || player.getVehicle() != session.screen.seat()) {
            this.close(player);
            return;
        }
        session.screen.updateHover();
        session.rpg.tick();
        if (session.ticks % this.settings.get().refreshTicks() < 2) {
            session.rpg.refresh();
        }
    }

    /** Закрыть меню игрока. Поток игрока. */
    public void close(final Player player) {
        final Session session = this.sessions.remove(player.getUniqueId());
        if (session == null) {
            return;
        }
        if (session.task != null) {
            session.task.cancel();
        }
        session.screen.close();
    }

    /** Выключение системы: закрыть все меню (каждое — в потоке своего игрока). */
    public void closeAll() {
        for (final UUID id : List.copyOf(this.sessions.keySet())) {
            final Player player = Bukkit.getPlayer(id);
            if (player == null) {
                this.sessions.remove(id);
                continue;
            }
            this.context.runOnceForPlayer(player, () -> this.close(player));
        }
    }

    // --- клики ---

    @EventHandler(priority = EventPriority.LOW)
    public void onSwing(final PlayerAnimationEvent event) {
        final Session s = this.sessions.get(event.getPlayer().getUniqueId());
        if (s == null || event.getAnimationType() != PlayerAnimationType.ARM_SWING || !s.screen.placed()) {
            return;
        }
        // Q (выбросить) тоже машет рукой: такой взмах — не клик.
        if (Bukkit.getCurrentTick() - s.lastDropTick <= 1) {
            return;
        }
        s.screen.click();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(final PlayerInteractEvent event) {
        if (this.isOpen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(final PlayerInteractEntityEvent event) {
        if (this.isOpen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(final PlayerDropItemEvent event) {
        final Session s = this.sessions.get(event.getPlayer().getUniqueId());
        if (s != null && !this.dropping.contains(event.getPlayer().getUniqueId())) {
            s.lastDropTick = Bukkit.getCurrentTick();
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAttack(final EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof final Player p && this.isOpen(p)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(final EntityDamageEvent event) {
        if (event.getEntity() instanceof final Player p && this.isOpen(p) && this.settings.get().invulnerable()) {
            event.setCancelled(true);
        }
    }

    // --- выход ---

    /** Shift — слезть с сиденья — закрыть меню. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDismount(final EntityDismountEvent event) {
        if (event.getEntity() instanceof final Player p && this.isOpen(p)) {
            this.close(p);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(final PlayerTeleportEvent event) {
        if (this.isOpen(event.getPlayer())) {
            this.close(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(final PlayerGameModeChangeEvent event) {
        if (event.getNewGameMode() == GameMode.SPECTATOR) {
            this.close(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(final PlayerDeathEvent event) {
        this.close(event.getPlayer());
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        this.close(event.getPlayer());
    }
}
