package core.amazake.systems.rezusmenu;

import com.destroystokyo.paper.event.player.PlayerStartSpectatingEntityEvent;
import core.amazake.systems.api.SystemContext;
import core.amazake.systems.rezusmenu.rpg.RpgScreen;
import core.amazake.systems.rezusmenu.screen.CalibrationScreen;
import core.amazake.systems.rezusmenu.screen.Screen;
import core.amazake.systems.rezusmenu.util.Text;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * Открытые экраны игроков. Всё про игрока — в его потоке: события игрока и задача {@code runForPlayer} (каждый тик).
 */
public final class MenuManager implements Listener {

    /** Открытый экран игрока: RPG-меню или калибровка. */
    private static final class Session {
        final Screen screen;
        RpgScreen rpg;
        CalibrationScreen calibration;
        volatile ScheduledTask task;
        boolean built;
        int ticks;
        int lastClickTick = -10;

        Session(final Screen screen) {
            this.screen = screen;
        }
    }

    private final SystemContext context;
    private final Supplier<Settings> settings;
    private final PlayerStore store;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Set<UUID> dropping = ConcurrentHashMap.newKeySet();

    public MenuManager(final SystemContext context, final Supplier<Settings> settings, final PlayerStore store) {
        this.context = context;
        this.settings = settings;
        this.store = store;
    }

    public boolean isOpen(final Player player) {
        return this.sessions.containsKey(player.getUniqueId());
    }

    /** Открыть меню по id из списка /menuses. Поток игрока. Без калибровки — сначала она. */
    public void open(final Player player, final String id) {
        if (!"rpg".equals(id)) {
            player.sendMessage(Text.mm(Text.prefix() + "<yellow>Это меню ещё не готово.</yellow>"));
            return;
        }
        this.close(player);
        final Screen.Calibration cal = this.store.calibration(player.getUniqueId());
        if (cal == null) {
            this.openCalibration(player);
        } else {
            this.start(player, cal, false);
        }
    }

    /** Калибровка экрана (первый вход или кнопка «Калибровка»). */
    public void openCalibration(final Player player) {
        this.close(player);
        this.start(player, Screen.Calibration.DEFAULT, true);
    }

    private void start(final Player player, final Screen.Calibration cal, final boolean calibrate) {
        final Settings s = this.settings.get();
        final Screen screen = Screen.open(this.context.plugin(), player, cal, s.eyeOffset(), s.sensitivity());
        if (screen == null) {
            player.sendMessage(Text.mm(Text.prefix() + "<red>Сейчас меню не открыть: слезьте с транспорта.</red>"));
            return;
        }
        this.store.remember(player.getUniqueId(), screen.saved());
        final Session session = new Session(screen);
        if (calibrate) {
            session.calibration = new CalibrationScreen(screen, result -> {
                this.store.calibrate(player.getUniqueId(), result);
                player.sendActionBar(Text.mm("<green>Экран откалиброван</green>"));
                // Переоткрыть меню уже под этот экран — в следующем такте, не изнутри клика.
                this.context.runOnceForPlayer(player, () -> this.open(player, "rpg"));
            });
        } else {
            session.rpg = new RpgScreen(screen, s, () -> this.close(player), this.dropping,
                () -> this.context.runOnceForPlayer(player, () -> this.openCalibration(player)));
        }
        this.sessions.put(player.getUniqueId(), session);
        session.task = this.context.runForPlayer(player, task -> this.tick(session), 1L);
        if (session.task == null) {
            this.close(player);
        }
    }

    private void tick(final Session session) {
        if (session.screen.closed()) {
            return;
        }
        session.ticks++;
        if (!session.built) {
            session.built = true;
            if (session.rpg != null) {
                session.rpg.build();
            } else {
                session.calibration.build();
            }
            return;
        }
        session.screen.update();
        if (session.rpg != null) {
            session.rpg.tick();
            if (session.ticks % this.settings.get().refreshTicks() == 0) {
                session.rpg.refresh();
            }
        }
    }

    /** Закрыть меню игрока и вернуть ему режим и место. Поток игрока. */
    public void close(final Player player) {
        final Session session = this.sessions.remove(player.getUniqueId());
        if (session == null) {
            return;
        }
        if (session.task != null) {
            session.task.cancel();
        }
        session.screen.close();
        this.store.forget(player.getUniqueId());
    }

    /** Выключение системы: закрыть все меню (каждое — в потоке своего игрока). */
    public void closeAll() {
        for (final UUID id : List.copyOf(this.sessions.keySet())) {
            final Player player = Bukkit.getPlayer(id);
            // Вернём сейчас же из задачи игрока: запись «что вернуть» можно снять заранее.
            this.store.forget(id);
            if (player == null) {
                this.sessions.remove(id);
                continue;
            }
            this.context.runOnceForPlayer(player, () -> this.close(player));
        }
        this.store.saveNow();
    }

    // --- ввод ---

    /**
     * ЛКМ. Наблюдатель шлёт клик по сущности (невидимая коробка вокруг глаз) — сервер пытается «вселить» его в неё:
     * отменяем и жмём кнопку под курсором. Взмах рукой тоже считаем кликом (другие клиенты), но не дважды за раз.
     */
    private void click(final Session s) {
        final int now = Bukkit.getCurrentTick();
        if (!s.built || now - s.lastClickTick <= 2) {
            return;
        }
        s.lastClickTick = now;
        s.screen.update();
        s.screen.click();
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onSwing(final PlayerAnimationEvent event) {
        final Session s = this.sessions.get(event.getPlayer().getUniqueId());
        if (s != null && event.getAnimationType() == PlayerAnimationType.ARM_SWING) {
            this.click(s);
        }
    }

    /** Пробел — курсор в центр, Shift — выйти. */
    @EventHandler
    public void onInput(final PlayerInputEvent event) {
        final Session s = this.sessions.get(event.getPlayer().getUniqueId());
        if (s == null) {
            return;
        }
        if (event.getInput().isSneak()) {
            this.close(event.getPlayer());
        } else if (event.getInput().isJump()) {
            s.screen.recenter();
        }
    }

    /** Наблюдатель не «вселяется» в сущности, пока в меню; клик по коробке экрана — это ЛКМ. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onSpectate(final PlayerStartSpectatingEntityEvent event) {
        final Session s = this.sessions.get(event.getPlayer().getUniqueId());
        if (s == null) {
            return;
        }
        event.setCancelled(true);
        if (s.screen.isTarget(event.getNewSpectatorTarget())) {
            this.click(s);
        }
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
        if (this.isOpen(event.getPlayer()) && !this.dropping.contains(event.getPlayer().getUniqueId())) {
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

    /** Телепорт наблюдателя (меню наблюдателя на цифрах) — запретить; любой другой — закрыть меню. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onTeleport(final PlayerTeleportEvent event) {
        if (!this.isOpen(event.getPlayer())) {
            return;
        }
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.SPECTATE) {
            event.setCancelled(true);
            return;
        }
        this.close(event.getPlayer());
    }

    /** Кто-то сменил режим игры, пока игрок в меню, — выйти из меню. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(final PlayerGameModeChangeEvent event) {
        if (this.isOpen(event.getPlayer())) {
            this.close(event.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        this.close(event.getPlayer());
    }

    /** Сервер упал, пока игрок был в меню: вернуть ему режим, скорость и место. */
    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        final Screen.Saved saved = this.store.takePending(event.getPlayer().getUniqueId());
        if (saved != null && !this.isOpen(event.getPlayer())) {
            Screen.restore(event.getPlayer(), saved);
        }
    }
}
