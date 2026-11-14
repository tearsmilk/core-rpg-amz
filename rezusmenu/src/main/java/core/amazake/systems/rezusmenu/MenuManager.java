package core.amazake.systems.rezusmenu;

import com.destroystokyo.paper.event.player.PlayerStartSpectatingEntityEvent;
import core.amazake.systems.api.SystemContext;
import core.amazake.systems.rezusmenu.rpg.RpgScreen;
import core.amazake.systems.rezusmenu.rpg.SkinService;
import core.amazake.systems.rezusmenu.screen.CalibrationScreen;
import core.amazake.systems.rezusmenu.screen.Screen;
import core.amazake.systems.rezusmenu.util.Text;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
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
    private final SkinService skins;

    public MenuManager(final SystemContext context, final Supplier<Settings> settings, final PlayerStore store) {
        this.context = context;
        this.settings = settings;
        this.store = store;
        this.skins = new SkinService(context.plugin(), context.logger());
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
        this.close(player, true);
        final Screen.Calibration cal = this.store.calibration(player.getUniqueId());
        if (cal == null) {
            this.openCalibration(player);
        } else {
            this.start(player, cal, false);
        }
    }

    /** Калибровка экрана (первый вход или кнопка «Калибровка»). */
    public void openCalibration(final Player player) {
        this.close(player, true);
        this.start(player, Screen.Calibration.DEFAULT, true);
    }

    private void start(final Player player, final Screen.Calibration cal, final boolean calibrate) {
        final Settings s = this.settings.get();
        final Screen screen = Screen.open(this.context.plugin(), player, cal, s.sensitivity(), s.glyphOffsetX(),
            s.glyphOffsetY());
        if (screen == null) {
            player.sendMessage(Text.mm(Text.prefix() + "<red>Сейчас меню не открыть: слезьте с транспорта.</red>"));
            this.showHud(player);
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
            session.rpg = new RpgScreen(screen, s, this.skins, r -> this.context.runOnceForPlayer(player, r),
                () -> this.close(player),
                () -> this.context.runOnceForPlayer(player, () -> this.openCalibration(player)));
        }
        this.sessions.put(player.getUniqueId(), session);
        this.hideHud(player);
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
        if (session.screen.moved()) {
            // Камера должна стоять на месте: кто-то сдвинул игрока — меню закрывается.
            this.close(session.screen.player());
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

    /** Закрыть меню игрока и вернуть ему режим, место и HUD. Поток игрока. */
    public void close(final Player player) {
        this.close(player, false);
    }

    /** {@code keepHud} — сразу откроется другое меню: HUD не возвращать, чтобы не мигал. */
    private void close(final Player player, final boolean keepHud) {
        final Session session = this.sessions.remove(player.getUniqueId());
        if (session != null) {
            if (session.task != null) {
                session.task.cancel();
            }
            session.screen.close();
            this.store.forget(player.getUniqueId());
        }
        if (!keepHud) {
            this.showHud(player);
        }
    }

    // --- чужой HUD (MythicHUD и т. п.) ---

    private void hideHud(final Player player) {
        if (!this.store.isHudHidden(player.getUniqueId())) {
            this.store.hudHidden(player.getUniqueId(), true);
            this.runCommands(this.settings.get().hudHideCommands(), player.getName(), false);
        }
    }

    private void showHud(final Player player) {
        if (this.store.isHudHidden(player.getUniqueId())) {
            this.store.hudHidden(player.getUniqueId(), false);
            this.runCommands(this.settings.get().hudShowCommands(), player.getName(), false);
        }
    }

    /**
     * Вернуть HUD тому, кому его спрятали и не вернули (вышел посреди меню, упал сервер): при входе, с задержкой —
     * пусть HUD-плагин сначала загрузит игрока.
     */
    public void restoreHud(final Player player) {
        if (!this.store.isHudHidden(player.getUniqueId())) {
            return;
        }
        this.store.hudHidden(player.getUniqueId(), false);
        final List<String> commands = this.settings.get().hudShowCommands();
        if (commands.isEmpty()) {
            return;
        }
        final String name = player.getName();
        Bukkit.getGlobalRegionScheduler().runDelayed(this.context.plugin(),
            t -> this.dispatch(commands, name), 40L);
    }

    /** Команды консоли — в глобальном потоке; {@code now} — мы уже в нём (выключение системы). */
    private void runCommands(final List<String> commands, final String name, final boolean now) {
        if (commands.isEmpty()) {
            return;
        }
        if (now) {
            this.dispatch(commands, name);
        } else {
            Bukkit.getGlobalRegionScheduler().execute(this.context.plugin(), () -> this.dispatch(commands, name));
        }
    }

    private void dispatch(final List<String> commands, final String name) {
        for (final String cmd : commands) {
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("{player}", name));
            } catch (final RuntimeException e) {
                this.context.logger().warning("hud: команда «" + cmd + "» упала: " + e);
            }
        }
    }

    /** Выключение системы: закрыть все меню (каждое — в потоке своего игрока). */
    public void closeAll() {
        // disable() — в глобальном потоке: HUD вернуть сейчас же, задачи системы после выключения уже не выполнятся.
        for (final Player p : Bukkit.getOnlinePlayers()) {
            if (this.store.isHudHidden(p.getUniqueId())) {
                this.store.hudHidden(p.getUniqueId(), false);
                this.runCommands(this.settings.get().hudShowCommands(), p.getName(), true);
            }
        }
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
     * Клик. Наблюдатель шлёт ЛКМ по сущности (невидимая коробка вокруг глаз) — сервер пытается «вселить» его в неё:
     * отменяем и жмём кнопку под курсором. ПКМ по коробке и взмах рукой (не наблюдатель) — тоже клик, но не дважды
     * за раз. {@code source} — для rpg.debug-clicks.
     */
    private void click(final Session s, final String source) {
        final int now = Bukkit.getCurrentTick();
        final boolean debug = this.settings.get().debugClicks();
        if (!s.built || now - s.lastClickTick <= 2) {
            if (debug) {
                this.context.logger().info("click " + source + " от " + s.screen.player().getName() + ": пропущен ("
                    + (s.built ? "повтор" : "меню ещё строится") + ")");
            }
            return;
        }
        s.lastClickTick = now;
        s.screen.update();
        final Player p = s.screen.player();
        if (debug) {
            final Screen.Button h = s.screen.hovered();
            this.context.logger().info(String.format(java.util.Locale.ROOT,
                "click %s от %s: курсор %.1f, %.1f px холста (%s), кнопка %s", source, p.getName(),
                s.screen.cursorX(), s.screen.cursorY(), s.screen.cursorMode(), h == null ? "—" : h.name()));
        }
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.35f, 1.5f);
        s.screen.click();
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onSwing(final PlayerAnimationEvent event) {
        final Session s = this.sessions.get(event.getPlayer().getUniqueId());
        if (s != null && event.getAnimationType() == PlayerAnimationType.ARM_SWING) {
            this.click(s, "swing");
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
            this.click(s, "spectate");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(final PlayerInteractEvent event) {
        if (this.isOpen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** ПКМ по коробке экрана (приходит дважды — на каждую руку и ещё «interact at»): тоже клик. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(final PlayerInteractEntityEvent event) {
        final Session s = this.sessions.get(event.getPlayer().getUniqueId());
        if (s == null) {
            return;
        }
        event.setCancelled(true);
        if (s.screen.isTarget(event.getRightClicked())) {
            this.click(s, "right-click");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(final PlayerDropItemEvent event) {
        if (this.isOpen(event.getPlayer())) {
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

    /** Вышел посреди меню: HUD вернём при следующем входе (сейчас команда уже не найдёт игрока). */
    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        this.close(event.getPlayer(), true);
    }

    /** Умер (например, /kill): закрыть меню, вернуть режим и HUD. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(final org.bukkit.event.entity.PlayerDeathEvent event) {
        this.close(event.getPlayer());
    }

    /** Сервер упал, пока игрок был в меню: вернуть ему режим, скорость и место. */
    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        final Screen.Saved saved = this.store.takePending(event.getPlayer().getUniqueId());
        if (saved != null && !this.isOpen(event.getPlayer())) {
            Screen.restore(event.getPlayer(), saved);
        }
        this.restoreHud(event.getPlayer());
    }
}
