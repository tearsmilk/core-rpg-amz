package core.amazake.systems.coreskyrift.event;

import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import core.amazake.systems.api.SystemContext;
import core.amazake.systems.coreskyrift.RiftItem;
import core.amazake.systems.coreskyrift.Settings;
import core.amazake.systems.coreskyrift.Text;
import core.amazake.systems.coreskyrift.anim.Timeline;
import core.amazake.systems.coreskyrift.geo.SpawnPicker;
import core.amazake.systems.coreskyrift.mobs.MobSpawner;
import core.amazake.systems.coreskyrift.view.SkyViewer;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;

/**
 * События разлома: запуск (ПКМ слитком или командой), часы и волны мобов в глобальном потоке, игроки в радиусе
 * (каждому — {@link SkyViewer} в его потоке), досрочное закрытие и уборка.
 *
 * <p>Потоки: часы событий, волны, команды core-mobs — глобальный таймер ({@code runGlobalTimer}, каждый тик);
 * поиск игроков в радиусе — раз в {@link #DISCOVER_STEP} тиков, проверка в потоке игрока
 * ({@code runOnceForPlayer}); место спавна — в потоке игрока (его регион) или задачей региона точки; мобы
 * помечаются в событии спавна (поток их региона). Планировщик сущностей не используется.</p>
 */
public final class EventManager implements Listener, MobSpawner.Owner {

    static final int DISCOVER_STEP = 20;
    /** Повторный ПКМ в течение этого времени игнорируется молча (событие приходит для обеих рук). */
    private static final long DEBOUNCE_MS = 250;
    /** Сколько кандидатов места перебрать на одного моба. */
    private static final int CANDIDATES = 12;
    /** Событие держится после конца, пока все игроки не уберут своё (не дольше, тики). */
    private static final long LINGER = 100;

    private final SystemContext context;
    private final Supplier<Settings> settings;
    private final String instance = UUID.randomUUID().toString();
    private final Map<UUID, SkyEvent> events = new ConcurrentHashMap<>();
    /** Игрок → его зритель (не больше одного события на игрока). */
    private final Map<UUID, SkyViewer> viewers = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldownUntil = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastUse = new ConcurrentHashMap<>();
    private final MobSpawner spawner;
    private volatile ScheduledTask clock;
    private long globalTick;

    public EventManager(final SystemContext context, final Supplier<Settings> settings) {
        this.context = context;
        this.settings = settings;
        this.spawner = new MobSpawner(context, settings, this);
    }

    public MobSpawner spawner() {
        return this.spawner;
    }

    public String instance() {
        return this.instance;
    }

    /** Запустить часы. */
    public void start() {
        this.clock = this.context.runGlobalTimer(t -> this.tick(), Duration.ofMillis(50));
    }

    // ------------------------------------------------------------------------------------------- запуск

    /** ПКМ «Разломом небес». Поток игрока. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(final PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        final ItemStack item = event.getItem();
        if (!RiftItem.is(item) || event.getHand() == null) {
            return;
        }
        // Защита территорий (WorldGuard и т. п.) запрещает использование предмета так. isCancelled() не годится:
        // ПКМ по воздуху приходит уже «отменённым» (useInteractedBlock = DENY).
        if (event.useItemInHand() == Event.Result.DENY) {
            return;
        }
        event.setCancelled(true);
        final Player player = event.getPlayer();
        if (event.getHand() == EquipmentSlot.OFF_HAND) {
            return;   // только основная рука
        }
        final long now = System.currentTimeMillis();
        final Long last = this.lastUse.put(player.getUniqueId(), now);
        if (last != null && now - last < DEBOUNCE_MS) {
            return;
        }
        this.cast(player);
    }

    /** Запуск слитком: задержка, лимиты, расход. Поток игрока. */
    private void cast(final Player player) {
        final Settings s = this.settings.get();
        final long now = System.currentTimeMillis();
        final Long until = this.cooldownUntil.get(player.getUniqueId());
        if (until != null && until > now) {
            Text.send(player, s, s.messages().cooldown(),
                Placeholder.unparsed("seconds", Long.toString(Math.max(1, (until - now + 999) / 1000))));
            return;
        }
        final Location loc = player.getLocation();
        final String refused = this.refuse(player.getWorld(), loc.getX(), loc.getZ(), s);
        if (refused != null) {
            Text.send(player, s, refused);
            return;
        }
        final PlayerInventory inv = player.getInventory();
        if (!RiftItem.is(inv.getItemInMainHand())) {
            return;
        }
        // Сначала событие (create ещё раз проверяет лимиты под замком), и только если оно создано — расход и
        // задержка: при гонке двух запусков проигравший ничего не теряет.
        final SkyEvent e = this.create(player.getWorld(), loc.getX(), loc.getY(), loc.getZ(), yaw(s, loc.getYaw()),
            player.getName(), s);
        if (e == null) {
            Text.send(player, s, s.messages().tooMany());
            return;
        }
        if (s.item().consume() && player.getGameMode() != GameMode.CREATIVE) {
            final ItemStack inHand = inv.getItemInMainHand();
            if (inHand.getAmount() > 1) {
                inHand.setAmount(inHand.getAmount() - 1);
                inv.setItemInMainHand(inHand);
            } else {
                inv.setItemInMainHand(null);
            }
        }
        if (s.item().cooldownSeconds() > 0) {
            this.cooldownUntil.put(player.getUniqueId(), now + s.item().cooldownSeconds() * 1000L);
        }
        Text.send(player, s, s.messages().cast());
        this.join(player, e);
    }

    /** Почему нельзя открыть разлом здесь (текст сообщения) или {@code null}. Любой поток. */
    public String refuse(final World world, final double x, final double z, final Settings s) {
        int inWorld = 0;
        for (final SkyEvent e : this.events.values()) {
            if (e.finished() || !e.world.equals(world)) {
                continue;
            }
            inWorld++;
            final double d = e.horizontalDistance(x, z);
            if (d < s.event().minDistance()) {
                return s.messages().tooClose().replace("<distance>", Long.toString(Math.round(d)))
                    .replace("<need>", Long.toString(Math.round(s.event().minDistance())));
            }
        }
        return inWorld >= s.event().maxPerWorld() ? s.messages().tooMany() : null;
    }

    /**
     * Создать событие (без проверки лимитов, если её не сделал вызывающий: {@link #create} проверяет ещё раз под
     * замком). Любой поток: событие — только числа, мир не трогается. {@code null} — лимит.
     */
    public synchronized SkyEvent create(final World world, final double x, final double y, final double z,
                                        final double yawDeg, final String starter, final Settings s) {
        if (this.refuse(world, x, z, s) != null) {
            return null;
        }
        final SkyEvent e = new SkyEvent(world, x, y, z, yawDeg, starter, s);
        e.nextWave = 0;
        this.events.put(e.id, e);
        this.context.logger().info("Разлом " + e.shortId + " открыт: " + starter + " в " + world.getName() + " "
            + fmt(x) + " " + fmt(y) + " " + fmt(z) + ", направление " + fmt(yawDeg) + "°, размер " + fmt(e.size)
            + ", " + e.timeline().total() / 20 + " с");
        return e;
    }

    /** Направление разлома по настройке: случайное, поперёк взгляда или заданное. */
    public static double yaw(final Settings s, final float playerYaw) {
        return switch (s.rift().yawMode()) {
            case RANDOM -> ThreadLocalRandom.current().nextDouble(360);
            // Ось разлома (sin a, cos a) перпендикулярна взгляду (−sin yaw, cos yaw): a = 90° − yaw.
            case PLAYER -> Math.floorMod(Math.round(90 - playerYaw), 360);
            case FIXED -> s.rift().yawDegrees();
        };
    }

    // ------------------------------------------------------------------------------------------- зрители

    /** Сделать игрока зрителем события. Поток игрока. */
    private void join(final Player player, final SkyEvent e) {
        final SkyViewer v = new SkyViewer(this.context, player, e, this.instance, this.settings,
            gone -> this.viewers.remove(gone.player().getUniqueId(), gone));
        if (this.viewers.putIfAbsent(player.getUniqueId(), v) != null) {
            return;
        }
        v.start();
    }

    /** Поток игрока: есть ли рядом событие, к которому он ещё не подключён. */
    private void discover(final Player p) {
        if (!p.isOnline() || this.viewers.containsKey(p.getUniqueId())) {
            return;
        }
        final Location eye = p.getEyeLocation();
        for (final SkyEvent e : this.events.values()) {
            if (!e.finished() && e.world.equals(p.getWorld())
                && e.horizontalDistance(eye.getX(), eye.getZ()) <= e.settings.event().radius()
                && !e.timeline().done(e.tick() + 1)) {
                this.join(p, e);
                return;
            }
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        final UUID id = event.getPlayer().getUniqueId();
        this.lastUse.remove(id);
        final SkyViewer v = this.viewers.get(id);
        if (v != null) {
            v.leave();
        }
    }

    // --------------------------------------------------------------------------------------------- часы

    /** Глобальный поток, каждый тик. */
    private void tick() {
        final long g = ++this.globalTick;
        this.spawner.expire();   // и без событий: точки, чей моб не пришёл, не должны висеть вечно
        if (this.events.isEmpty()) {
            return;
        }
        for (final SkyEvent e : List.copyOf(this.events.values())) {
            final long t = e.advance();
            final Timeline tl = e.timeline();
            if (!e.finished() && tl.done(t)) {
                this.end(e);
            }
            if (e.finished()) {
                if (t - e.finishedAt > LINGER || !this.hasViewers(e)) {
                    this.events.remove(e.id, e);
                }
                continue;
            }
            if (tl.wavesActive(t, e.settings.mobs().startAtOpen()) && t >= e.nextWave) {
                final Settings live = this.settings.get();
                e.nextWave = t + live.mobs().waveIntervalSeconds() * 20L;
                this.wave(e, live);
            }
        }
        if (g % DISCOVER_STEP == 0) {
            for (final Player p : Bukkit.getOnlinePlayers()) {
                if (!this.viewers.containsKey(p.getUniqueId())) {
                    this.context.runOnceForPlayer(p, () -> this.discover(p));
                }
            }
        }
    }

    private boolean hasViewers(final SkyEvent e) {
        for (final SkyViewer v : this.viewers.values()) {
            if (v.event() == e) {
                return true;
            }
        }
        return false;
    }

    private List<Player> viewersOf(final SkyEvent e) {
        final List<Player> out = new ArrayList<>();
        for (final SkyViewer v : this.viewers.values()) {
            if (v.event() == e && !v.closed() && v.player().isOnline()) {
                out.add(v.player());
            }
        }
        return out;
    }

    /** Событие кончилось: зрители уберут своё сами, мобы — по настройке. Глобальный поток. */
    private void end(final SkyEvent e) {
        e.finish();
        e.finishedAt = e.tick();
        if (e.settings.mobs().despawnOnEnd()) {
            this.spawner.despawn(Map.copyOf(e.mobs));
        }
        this.context.logger().info("Разлом " + e.shortId + " закрылся: мобов за событие " + e.spawnedTotal.get()
            + ", живых осталось " + e.alive() + (e.settings.mobs().despawnOnEnd() ? " (убраны)" : ""));
    }

    // ------------------------------------------------------------------------------------------- мобы

    /** Волна: сколько можно, кому и где. Глобальный поток. */
    private void wave(final SkyEvent e, final Settings live) {
        final Settings.Mobs m = live.mobs();
        if (!m.enabled() || m.list().isEmpty()) {
            return;
        }
        final List<Player> players = this.viewersOf(e);
        if (players.isEmpty()) {
            return;
        }
        final RandomGenerator rnd = ThreadLocalRandom.current();
        final int busy = e.alive() + e.pending.get();
        final int byAlive = m.aliveCap(players.size()) - busy;
        final int byTotal = m.maxTotal() - e.spawnedTotal.get() - e.pending.get();
        final int n = Math.min(SpawnPicker.between(m.perWaveMin(), m.perWaveMax(), rnd), Math.min(byAlive, byTotal));
        if (n <= 0) {
            return;
        }
        final List<String> ids = List.copyOf(m.list().keySet());
        final int[] weights = m.list().values().stream().mapToInt(Integer::intValue).toArray();
        final double r = e.timeline().rift(e.tick());
        if (m.spawnMode() == Settings.SpawnMode.UNDER_RIFT) {
            final List<double[]> points = SpawnPicker.underRift(e.band, r, e.settings.event().radius(), rnd, n);
            for (final double[] p : points) {
                final String id = ids.get(Math.max(0, SpawnPicker.weighted(weights, rnd)));
                e.pending.incrementAndGet();
                final double wx = e.x + p[0];
                final double wz = e.z + p[1];
                Bukkit.getRegionScheduler().execute(this.context.plugin(), e.world, (int) Math.floor(wx) >> 4,
                    (int) Math.floor(wz) >> 4, () -> this.placeAt(e, id, List.of(new double[] {wx, wz}), m));
            }
            return;
        }
        Collections.shuffle(players, ThreadLocalRandom.current());
        for (int i = 0; i < n; i++) {
            final Player p = players.get(i % players.size());
            final String id = ids.get(Math.max(0, SpawnPicker.weighted(weights, rnd)));
            e.pending.incrementAndGet();
            if (!this.context.runOnceForPlayer(p, () -> this.placeNear(e, id, p, m, r))) {
                e.pending.decrementAndGet();
            }
        }
    }

    /** Место рядом с игроком (лучше — под разломом). Поток игрока (его регион). */
    private void placeNear(final SkyEvent e, final String id, final Player p, final Settings.Mobs m, final double r) {
        if (!p.isOnline() || !p.getWorld().equals(e.world) || e.finished()) {
            e.pending.decrementAndGet();
            return;
        }
        final Location l = p.getLocation();
        final List<double[]> rel = SpawnPicker.nearPlayer(l.getX() - e.x, l.getZ() - e.z, e.band, r, m.minDistance(),
            m.maxDistance(), ThreadLocalRandom.current(), CANDIDATES);
        final List<double[]> abs = new ArrayList<>(rel.size());
        for (final double[] q : rel) {
            abs.add(new double[] {e.x + q[0], e.z + q[1]});
        }
        this.placeAt(e, id, abs, m);
    }

    /** Первый подходящий столбец из кандидатов → команда core-mobs. Поток региона кандидатов. */
    private void placeAt(final SkyEvent e, final String id, final List<double[]> candidates, final Settings.Mobs m) {
        if (e.finished()) {
            e.pending.decrementAndGet();
            return;
        }
        for (final double[] c : candidates) {
            final Location at = MobSpawner.column(e.world, c[0], c[1], m.dropHeight());
            if (at != null) {
                // pending уже учтён: точку ждёт спавнер, а счётчик вернётся в arrived/expired.
                this.spawner.spawn(e.id, e.world, id, at.getX(), at.getY(), at.getZ());
                return;
            }
        }
        e.pending.decrementAndGet();
    }

    @Override
    public void arrived(final UUID eventId, final Entity mob) {
        final SkyEvent e = this.events.get(eventId);
        if (e == null) {
            // Событие уже убрано (конец, stop), а команда спавна была в пути: моб без хозяина — убрать.
            if (this.settings.get().mobs().despawnOnEnd()) {
                this.spawner.despawn(Map.of(mob.getUniqueId(), mob));
            }
            return;
        }
        e.pending.updateAndGet(v -> Math.max(0, v - 1));
        e.spawnedTotal.incrementAndGet();
        e.mobs.put(mob.getUniqueId(), mob);
        if (e.finished() && e.settings.mobs().despawnOnEnd()) {
            this.spawner.despawn(Map.of(mob.getUniqueId(), mob));   // пришёл после конца
        }
    }

    @Override
    public boolean live(final UUID eventId) {
        final SkyEvent e = this.events.get(eventId);
        return e != null && !e.finished();
    }

    @Override
    public void expired(final UUID eventId) {
        final SkyEvent e = this.events.get(eventId);
        if (e != null) {
            e.pending.updateAndGet(v -> Math.max(0, v - 1));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(final EntityDeathEvent event) {
        this.forget(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRemove(final EntityRemoveFromWorldEvent event) {
        this.forget(event.getEntity());
    }

    private void forget(final Entity entity) {
        final String tag = entity.getPersistentDataContainer().get(MobSpawner.EVENT_KEY, PersistentDataType.STRING);
        if (tag == null) {
            return;
        }
        try {
            final SkyEvent e = this.events.get(UUID.fromString(tag));
            if (e != null) {
                e.mobs.remove(entity.getUniqueId());
            }
        } catch (final IllegalArgumentException ignored) {
            // чужая метка
        }
    }

    // ---------------------------------------------------------------------------------------- управление

    /** Закрыть все события (с анимацией). Любой поток → глобальный. Возвращает, сколько закрывается. */
    public int stopAll() {
        int n = 0;
        for (final SkyEvent e : this.events.values()) {
            if (!e.finished() && !e.timeline().closing(Math.max(0, e.tick()))) {
                n++;
                Bukkit.getGlobalRegionScheduler().execute(this.context.plugin(), e::requestClose);
            }
        }
        return n;
    }

    /** Закрыть событие, в котором игрок (зритель), или ближайшее к нему в его мире. 0/1. Поток игрока. */
    public int stopFor(final Player p) {
        SkyEvent target = null;
        final SkyViewer v = this.viewers.get(p.getUniqueId());
        if (v != null) {
            target = v.event();
        } else {
            double best = Double.MAX_VALUE;
            final Location l = p.getLocation();
            for (final SkyEvent e : this.events.values()) {
                if (!e.finished() && e.world.equals(p.getWorld())) {
                    final double d = e.horizontalDistance(l.getX(), l.getZ());
                    if (d < best) {
                        best = d;
                        target = e;
                    }
                }
            }
        }
        if (target == null || target.finished() || target.timeline().closing(Math.max(0, target.tick()))) {
            return 0;
        }
        Bukkit.getGlobalRegionScheduler().execute(this.context.plugin(), target::requestClose);
        return 1;
    }

    /** Строки для /skyrift list. Любой поток. */
    public List<String> describe() {
        final List<String> out = new ArrayList<>();
        for (final SkyEvent e : this.events.values()) {
            final long t = Math.max(0, e.tick());
            final Timeline tl = e.timeline();
            int watching = 0;
            for (final SkyViewer v : this.viewers.values()) {
                if (v.event() == e) {
                    watching++;
                }
            }
            out.add(e.shortId + " " + e.starter + " " + e.world.getName() + " " + fmt(e.x) + " " + fmt(e.y) + " "
                + fmt(e.z) + " — " + tl.stage(t).name().toLowerCase(Locale.ROOT) + " " + t / 20 + "/" + tl.total() / 20
                + " с, раскрыт " + Math.round(tl.rift(t) * 100) + " %, игроков " + watching + ", мобов "
                + e.alive() + " (всего " + e.spawnedTotal.get() + ")");
        }
        return out;
    }

    public int size() {
        return this.events.size();
    }

    /**
     * Выключение системы: часы, зрители (дисплеи, боссбары, время), следы, мобы событий (по настройке). Любой
     * поток (обычно глобальный).
     */
    public void shutdown() {
        final ScheduledTask c = this.clock;
        if (c != null) {
            c.cancel();
        }
        for (final SkyViewer v : List.copyOf(this.viewers.values())) {
            v.shutdown();
        }
        this.viewers.clear();
        for (final SkyEvent e : List.copyOf(this.events.values())) {
            e.finish();
            if (e.settings.mobs().despawnOnEnd()) {
                this.spawner.despawn(Map.copyOf(e.mobs));
            }
        }
        this.events.clear();
        this.spawner.shutdown();
    }

    /**
     * Хвосты прошлых запусков рядом с игроком: дисплеи неба с меткой другого экземпляра и (при
     * {@code despawn-on-end}) мобы событий, которых больше нет. Поток игрока.
     */
    public void sweepStale(final Player player) {
        final boolean mobs = this.settings.get().mobs().despawnOnEnd();
        this.context.runOnceForPlayer(player, () -> {
            for (final Entity e : player.getNearbyEntities(64, 64, 64)) {
                if (!Bukkit.isOwnedByCurrentRegion(e)) {
                    continue;
                }
                final var pdc = e.getPersistentDataContainer();
                final String mark = pdc.get(SkyViewer.PART, PersistentDataType.STRING);
                if (mark != null && !mark.equals(this.instance)) {
                    e.remove();
                    continue;
                }
                final String event = pdc.get(MobSpawner.EVENT_KEY, PersistentDataType.STRING);
                if (mobs && event != null && !this.isActive(event)) {
                    e.remove();
                }
            }
        });
    }

    private boolean isActive(final String eventId) {
        try {
            final SkyEvent e = this.events.get(UUID.fromString(eventId));
            return e != null && !e.finished();
        } catch (final IllegalArgumentException ex) {
            return false;
        }
    }

    /** Запуск командой у игрока (без расхода и задержки). Поток игрока. {@code null} — запущено, иначе текст отказа. */
    public String startAt(final Player player) {
        final Settings s = this.settings.get();
        final Location l = player.getLocation();
        final String refused = this.refuse(player.getWorld(), l.getX(), l.getZ(), s);
        if (refused != null) {
            return refused;
        }
        final SkyEvent e = this.create(player.getWorld(), l.getX(), l.getY(), l.getZ(), yaw(s, l.getYaw()),
            player.getName(), s);
        if (e == null) {
            return s.messages().tooMany();
        }
        if (!this.viewers.containsKey(player.getUniqueId())) {
            this.join(player, e);
        }
        return null;
    }

    static String fmt(final double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : String.format(Locale.ROOT, "%.1f", v);
    }
}
