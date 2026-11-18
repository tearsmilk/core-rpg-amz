package core.amazake.systems.coremechanics.doll;

import com.destroystokyo.paper.profile.PlayerProfile;
import core.amazake.systems.api.SystemContext;
import core.amazake.systems.coremechanics.Settings;
import core.amazake.systems.coremechanics.physics.PhysicsLoop;
import core.amazake.systems.coremechanics.physics.RagdollBody;
import core.amazake.systems.coremechanics.physics.RagdollSpec;
import core.amazake.systems.coremechanics.skin.BodyLooks;
import core.amazake.systems.coremechanics.skin.MojangProfiles;
import core.amazake.systems.coremechanics.skin.SkinBoxes;
import core.amazake.systems.coremechanics.skin.SkinService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.Vector;

/**
 * Рэгдоллы игроков на Jolt (перенесено из MagicRuruka RagdollManager): при смерти игрока на месте падает его
 * «тряпичная кукла», {@code /rd ник} кидает куклу перед админом. Shift+ПКМ по части — взять и таскать (кукла
 * волочится), ПКМ — отпустить, ЛКМ — бросить. Голова — настоящая голова игрока; тело — 10 кубиков скина MineSkin, а
 * пока они не готовы — блоки средних цветов скина.
 *
 * <p>Потоки: события игрока — в его потоке; физика ({@link #prepare}/{@link #afterStep}) — в потоке физики; дисплеи —
 * в потоке региона якоря куклы ({@link DollRenderer}). Общие данные — {@link ConcurrentHashMap} и volatile-поля Doll.</p>
 */
public final class RagdollManager implements Listener, PhysicsLoop.Participant {

    private final SystemContext context;
    private final Supplier<Settings> settings;
    private final DollRenderer renderer;
    private final SkinService skins;
    private final Map<UUID, Doll> dolls = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();
    /** Кому сообщить, когда скин тела догенерируется (хэш → админы). */
    private final Map<String, Set<UUID>> notify = new ConcurrentHashMap<>();
    private volatile PhysicsLoop loop;
    private volatile boolean closed;

    public RagdollManager(final SystemContext context, final Supplier<Settings> settings, final DollRenderer renderer,
                          final SkinService skins) {
        this.context = context;
        this.settings = settings;
        this.renderer = renderer;
        this.skins = skins;
    }

    /** Физика готова: начать шаги. */
    public void attach(final PhysicsLoop loop) {
        this.loop = loop;
        loop.start(this);
    }

    public PhysicsLoop loop() {
        return this.loop;
    }

    public boolean ready() {
        return this.loop != null && !this.closed;
    }

    public int count() {
        return this.dolls.size();
    }

    public DollRenderer renderer() {
        return this.renderer;
    }

    /** Для /rd info: кукла, центр туловища, сколько ещё живёт, кто держит. Любой поток (значения — приблизительно). */
    public List<String> describe() {
        final List<String> out = new ArrayList<>();
        for (final Doll d : this.dolls.values()) {
            final double[] c = d.pose.torso();
            out.add(String.format(java.util.Locale.ROOT, " · %s: %s %.2f %.2f %.2f, ещё %d с%s%s", d.name, d.world.getName(), c[0], c[1], c[2],
                Math.max(0, d.life) / 20, d.grabs.isEmpty() ? "" : ", держат: " + d.grabs.size(), d.body3d == null ? " (без тел)" : ""));
        }
        return out;
    }

    // ================================================================== появление

    /**
     * Любой поток: кукла стоя, ступни в feet, лицом по yaw, начальная скорость (блоков/тик), живёт lifeTicks.
     * null — физика недоступна. Дисплеи ставятся задачей региона точки feet, тела — в потоке физики.
     */
    public Doll spawn(final PlayerProfile owner, final Location feet, final float yaw, final double vx, final double vy,
                      final double vz, final int lifeTicks) {
        final PhysicsLoop l = this.loop;
        if (l == null || this.closed || feet.getWorld() == null) {
            return null;
        }
        final UUID id = owner.getId() != null ? owner.getId() : UUID.randomUUID();
        final String name = owner.getName() != null ? owner.getName() : "?";
        final String[] tex = SkinService.texture(owner);
        final World w = feet.getWorld();
        final Doll d = new Doll(true, w, name, id, BodyLooks.head(tex == null ? null : tex[0], tex == null ? null : tex[1]),
            this.skins.body(id), PoseMath.standing(feet.getX(), feet.getY(), feet.getZ(), yaw), feet.getX(), feet.getY() + 1,
            feet.getZ());
        d.setBody(this.skins.body(id), this.skins.cubed(id));
        d.life = lifeTicks;
        d.seq = this.seq.incrementAndGet();
        this.dolls.put(d.id, d);
        this.renderer.schedule(d);
        final double fx = feet.getX(), fy = feet.getY(), fz = feet.getZ();
        l.submit(() -> this.createBody(l, d, fx, fy, fz, yaw, vx, vy, vz));
        if (!this.skins.cubed(id)) {
            this.skins.fetch(owner, true);   // цвета/кубики скина — живые куклы переоденутся сами
        }
        return d;
    }

    /** Поток физики. */
    private void createBody(final PhysicsLoop l, final Doll d, final double fx, final double fy, final double fz, final float yaw,
                            final double vx, final double vy, final double vz) {
        if (d.removed.get()) {
            return;
        }
        final int max = this.settings.get().maxDolls();
        final List<Doll> alive = new ArrayList<>();
        for (final Doll o : this.dolls.values()) {
            if (o != d && !o.removed.get() && o.body3d != null) {
                alive.add(o);
            }
        }
        alive.sort((a, b) -> Long.compare(a.seq, b.seq));
        for (int i = 0; i < alive.size() - (max - 1); i++) {
            this.removeNow(alive.get(i));   // старые — вон
        }
        final RagdollBody body = l.world(d.world).physics.createRagdoll(fx, fy, fz, yaw, vx, vy, vz);
        if (body == null) {
            this.removeNow(d);
            return;
        }
        d.body3d = body;
    }

    /** Любой поток: убрать куклу (тела — в потоке физики, дисплеи — в потоке региона). */
    public void remove(final Doll d) {
        if (d != null) {
            d.removed.set(true);
            if (this.loop == null) {
                this.dolls.remove(d.id);
                this.renderer.schedule(d);
            }
        }
    }

    /** Поток физики. */
    private void removeNow(final Doll d) {
        d.removed.set(true);
        if (d.body3d != null) {
            d.body3d.destroy();
            d.body3d = null;
        }
        this.dolls.remove(d.id);
        this.renderer.schedule(d);
    }

    public boolean alive(final Doll d) {
        return d != null && !d.removed.get() && this.dolls.containsKey(d.id);
    }

    /** Любой поток: убрать все. */
    public int clear() {
        int n = 0;
        for (final Doll d : this.dolls.values()) {
            if (!d.removed.getAndSet(true)) {
                n++;
            }
        }
        return n;
    }

    /** Любой поток: толкнуть все части (скорость, блоки/с) — удар по статуе, взрыв. */
    public void push(final Doll d, final double vx, final double vy, final double vz) {
        final PhysicsLoop l = this.loop;
        if (l != null) {
            l.submit(() -> {
                if (d.body3d != null) {
                    for (int i = 0; i < RagdollSpec.N; i++) {
                        final float m = RagdollSpec.mass(i);
                        d.body3d.impulse(i, vx * m, vy * m, vz * m);
                    }
                }
            });
        }
    }

    // ================================================================== физика (поток физики)

    @Override
    public void prepare(final long tick) {
        final Settings s = this.settings.get();
        for (final Doll d : this.dolls.values()) {
            if (d.removed.get()) {
                this.removeNow(d);
                continue;
            }
            final RagdollBody body = d.body3d;
            if (body == null) {
                continue;   // тела ещё создаются (команда в очереди)
            }
            final double[] c = d.pose.torso();
            if (--d.life <= 0 || c[1] < d.world.getMinHeight() - 20 || d.sinceRenderSeconds() > 10) {
                this.removeNow(d);
                continue;
            }
            final PhysicsLoop.WorldState ws = this.loop.world(d.world);
            final boolean ready = ws.meshes.require(c[0] - 4, c[1] - 4, c[2] - 4, c[0] + 4, c[1] + 4, c[2] + 4, tick,
                s.physicsRequestsPerTick());
            body.hold(!ready);
            if (ready && ws.meshes.changedSince(c[0] - 4, c[1] - 4, c[2] - 4, c[0] + 4, c[1] + 4, c[2] + 4, tick - 1)) {
                body.wake();   // под куклой сломали/поставили блок
            }
            if (!d.grabs.isEmpty()) {
                this.holdStep(d, body, tick, s);
            }
            ws.use();
        }
    }

    @Override
    public void afterStep(final long tick) {
        for (final Doll d : this.dolls.values()) {
            final RagdollBody body = d.body3d;
            if (body == null || d.removed.get()) {
                continue;
            }
            final double[][] pos = new double[RagdollSpec.N][3];
            final float[][] rot = new float[RagdollSpec.N][4];
            body.readBack(pos, rot);
            d.pose = new Pose(pos, rot);
            this.renderer.schedule(d);
        }
    }

    /**
     * Каждая удерживаемая часть тянется к точке перед глазами своего игрока (скорость ∝ расстоянию). Несколько игроков
     * за одну часть — их точки усредняются; за разные части — куклу растягивают в разные стороны.
     */
    private void holdStep(final Doll d, final RagdollBody body, final long tick, final Settings s) {
        final double[][] sum = new double[RagdollSpec.N][4];
        final Pose p = d.pose;
        for (final Map.Entry<UUID, Doll.Grab> e : d.grabs.entrySet()) {
            final Doll.Grab g = e.getValue();
            final double[] eye = g.eye;
            if (eye == null || tick - g.eyeAt > 40) {
                d.grabs.remove(e.getKey(), g);   // игрок вышел/умер/сменил мир — его задача не обновляет взгляд
                continue;
            }
            final double tx = eye[0] + eye[3] * g.dist, ty = eye[1] + eye[4] * g.dist, tz = eye[2] + eye[5] * g.dist;
            final double[] c = p.pos()[g.part];
            final double dx = tx - c[0], dy = ty - c[1], dz = tz - c[2];
            if (dx * dx + dy * dy + dz * dz > 100) {
                d.grabs.remove(e.getKey(), g);   // слишком далеко — выскользнула из рук
                continue;
            }
            sum[g.part][0] += tx;
            sum[g.part][1] += ty;
            sum[g.part][2] += tz;
            sum[g.part][3]++;
        }
        final double k = s.holdStiffness(), max = s.holdMaxSpeed();
        for (int part = 0; part < RagdollSpec.N; part++) {
            if (sum[part][3] == 0) {
                continue;
            }
            final double n = sum[part][3];
            final double[] c = p.pos()[part];
            body.setVelocity(part, clamp((sum[part][0] / n - c[0]) * k, max), clamp((sum[part][1] / n - c[1]) * k, max),
                clamp((sum[part][2] / n - c[2]) * k, max));
        }
    }

    static double clamp(final double v, final double m) {
        return Math.max(-m, Math.min(m, v));
    }

    // ================================================================== команда /rd ник

    /** Поток админа: кукла перед ним, лицом к нему, с небольшим подбросом. Возвращает сообщение. */
    public String spawnCommand(final Player admin, final String name) {
        if (!this.ready()) {
            return "Физдвижок недоступен или нет места.";
        }
        final Player t = Bukkit.getPlayerExact(name);
        Vector f = admin.getEyeLocation().getDirection().setY(0);
        if (f.lengthSquared() < 1e-4) {
            f = new Vector(0, 0, 1);
        }
        f.normalize();
        final Location at = admin.getLocation().add(f.clone().multiply(2.5)).add(0, 0.6, 0);
        final float yaw = admin.getLocation().getYaw() + 180f;
        final double vx = f.getX() * 0.05, vz = f.getZ() * 0.05;
        final int life = this.settings.get().commandLifeTicks();
        if (t != null) {
            final boolean ok = this.spawn(t.getPlayerProfile(), at, yaw, vx, 0.25, vz, life) != null;
            return ok ? "Рэгдолл " + t.getName() + " заспавнен. Shift+ПКМ по части — таскать, ЛКМ — бросить."
                + this.skinStatus(admin, t.getUniqueId()) : "Физдвижок недоступен или нет места.";
        }
        // не в сети — профиль и скин у Mojang (асинхронно), потом спавн
        final boolean lookup = this.settings.get().mojangLookup();
        Bukkit.getAsyncScheduler().runNow(this.context.plugin(), task -> {
            final PlayerProfile prof = lookup ? MojangProfiles.lookup(name) : null;
            final PlayerProfile pp = prof != null ? prof : MojangProfiles.offline(name);
            final boolean ok = !this.closed && this.spawn(pp, at, yaw, vx, 0.25, vz, life) != null;
            this.context.runOnceForPlayer(admin, () -> admin.sendMessage(Component.text(!ok ? "Физдвижок недоступен или нет места."
                    : "Рэгдолл " + pp.getName() + " заспавнен" + (prof == null ? " (скин у Mojang не найден — Стив)" : "")
                    + ". Shift+ПКМ по части — таскать, ЛКМ — бросить." + this.skinStatus(admin, pp.getId()),
                ok ? NamedTextColor.GREEN : NamedTextColor.RED)));
        });
        return "Игрок " + name + " не в сети — ищу его скин у Mojang…";
    }

    /** Хвост сообщения: скин тела ещё генерируется — сколько готово и сколько ждать; админа уведомим по готовности. */
    public String skinStatus(final Player admin, final UUID owner) {
        final String hash = owner == null ? null : this.skins.hash(owner);
        if (hash == null || !this.skins.cubes().available() || this.skins.cubes().get(hash) != null) {
            return "";
        }
        this.notify.computeIfAbsent(hash, h -> ConcurrentHashMap.newKeySet()).add(admin.getUniqueId());
        final int k = this.skins.cubes().done(hash), ahead = this.skins.cubes().queuedBefore(hash);
        final int sec = (int) Math.ceil((SkinBoxes.COUNT - k) * 3.3 + Math.min(ahead, 1) * 5);
        return "\nСкин тела генерируется через MineSkin (" + k + "/" + SkinBoxes.COUNT + ", ~" + sec
            + " с) — пока тело цветное, кукла переоденется сама.";
    }

    /** Поток MineSkin: кубики скина готовы — живые куклы этого скина переодеть, админам сказать. */
    public void upgrade(final String hash, final String[][] tex) {
        final Set<UUID> who = this.notify.remove(hash);
        if (who != null) {
            for (final UUID id : who) {
                final Player a = Bukkit.getPlayer(id);
                if (a != null) {
                    this.context.runOnceForPlayer(a, () -> a.sendMessage(Component.text("Скин тела рэгдолла готов — кукла переодета.",
                        NamedTextColor.GREEN)));
                }
            }
        }
        for (final Doll d : this.dolls.values()) {
            if (!d.cubed() && hash.equals(this.skins.hash(d.owner))) {
                d.setBody(BodyLooks.cubes(tex), true);
                this.renderer.schedule(d);
            }
        }
    }

    // ================================================================== события

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(final PlayerDeathEvent e) {
        final Settings s = this.settings.get();
        if (!s.onDeath() || !this.ready()) {
            return;
        }
        final Player p = e.getEntity();
        final Vector v = p.getVelocity();
        final Location l = p.getLocation();
        this.spawn(p.getPlayerProfile(), l, l.getYaw(), v.getX() * 0.8 + (Math.random() - 0.5) * 0.1, Math.max(0.05, v.getY() * 0.5),
            v.getZ() * 0.8, s.deathLifeTicks());
    }

    /** Поток игрока: луч из глаз — ближайшая часть куклы. */
    private Object[] pick(final Player p) {
        final Location eye = p.getEyeLocation();
        final Vector dir = eye.getDirection();
        Doll best = null;
        Picking.Hit bestHit = null;
        double bestT = this.settings.get().grabReach();
        for (final Doll d : this.dolls.values()) {
            if (!d.world.equals(p.getWorld()) || d.removed.get()) {
                continue;
            }
            final Picking.Hit h = Picking.pick(d.pose, eye.getX(), eye.getY(), eye.getZ(), dir.getX(), dir.getY(), dir.getZ(), bestT, 0.08);
            if (h != null && h.t() < bestT) {
                bestT = h.t();
                best = d;
                bestHit = h;
            }
        }
        return best == null ? null : new Object[] {best, bestHit};
    }

    private Doll heldBy(final UUID player) {
        for (final Doll d : this.dolls.values()) {
            if (d.grabs.containsKey(player)) {
                return d;
            }
        }
        return null;
    }

    private static double[] eye(final Player p) {
        final Location eye = p.getEyeLocation();
        final Vector dir = eye.getDirection();
        return new double[] {eye.getX(), eye.getY(), eye.getZ(), dir.getX(), dir.getY(), dir.getZ()};
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(final PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK)) {
            return;
        }
        final PhysicsLoop l = this.loop;
        if (l == null || this.dolls.isEmpty()) {
            return;
        }
        final Player p = e.getPlayer();
        final UUID pid = p.getUniqueId();
        final Doll held = this.heldBy(pid);
        if (held != null) {
            held.grabs.remove(pid);
            e.setCancelled(true);
            return;
        }
        if (!p.isSneaking()) {
            return;
        }
        final Object[] hit = this.pick(p);
        if (hit == null) {
            return;
        }
        final Doll d = (Doll) hit[0];
        final Picking.Hit h = (Picking.Hit) hit[1];
        final Doll.Grab g = new Doll.Grab(h.part(), Math.max(1.5, Math.min(4.5, h.t())));
        g.eye = eye(p);
        g.eyeAt = l.tick();
        d.grabs.put(pid, g);
        final int life = this.settings.get().commandLifeTicks();
        l.submit(() -> d.life = Math.max(d.life, life));
        // взгляд держащего — в его потоке каждый тик (поток физики мир/игроков не читает)
        this.context.runForPlayer(p, task -> {
            if (d.removed.get() || d.grabs.get(pid) != g || this.closed) {
                task.cancel();
                return;
            }
            g.eye = eye(p);
            g.eyeAt = l.tick();
        }, 1);
        p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.7f, 1.2f);
        final int others = d.grabs.size() - 1;
        p.sendActionBar(Component.text("Тащишь " + d.name + (others > 0 ? " вместе с ещё " + others : "")
            + " · ПКМ — отпустить · ЛКМ — бросить", NamedTextColor.GRAY));
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwing(final PlayerAnimationEvent e) {
        final Player p = e.getPlayer();
        final Doll d = this.heldBy(p.getUniqueId());
        final PhysicsLoop l = this.loop;
        if (d == null || l == null) {
            return;
        }
        final Doll.Grab g = d.grabs.remove(p.getUniqueId());
        if (g == null) {
            return;
        }
        final Vector dir = p.getEyeLocation().getDirection().multiply(this.settings.get().throwStrength());
        // бросок от одного из держащих — сила делится, если куклу держат ещё другие
        final double share = 1.0 / (1 + d.grabs.size());
        final int part = g.part;
        l.submit(() -> {
            if (d.body3d == null) {
                return;
            }
            for (int i = 0; i < RagdollSpec.N; i++) {
                final double k = (i == part ? 1.0 : 0.6) * share * RagdollSpec.mass(i);
                d.body3d.impulse(i, dir.getX() * k, (dir.getY() + 3) * k, dir.getZ() * k);
            }
        });
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.7f, 0.8f);
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent e) {
        for (final Doll d : this.dolls.values()) {
            d.grabs.remove(e.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent e) {
        if (!this.settings.get().skinFetchOnJoin()) {
            return;
        }
        final PlayerProfile prof = e.getPlayer().getPlayerProfile();
        Bukkit.getAsyncScheduler().runDelayed(this.context.plugin(), t -> {
            if (!this.closed) {
                this.skins.fetch(prof, false);
            }
        }, 3, TimeUnit.SECONDS);
    }

    // ---- изменения мира → пересборка меша секций (события — в потоке региона, сама работа — в потоке физики)

    private void changed(final Block b) {
        final PhysicsLoop l = this.loop;
        if (l != null && !this.dolls.isEmpty()) {
            l.blockChanged(b.getWorld(), b.getX(), b.getY(), b.getZ());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(final BlockBreakEvent e) {
        this.changed(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(final BlockPlaceEvent e) {
        this.changed(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(final EntityExplodeEvent e) {
        e.blockList().forEach(this::changed);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(final BlockExplodeEvent e) {
        e.blockList().forEach(this::changed);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(final BlockPistonExtendEvent e) {
        e.getBlocks().forEach(b -> {
            this.changed(b);
            this.changed(b.getRelative(e.getDirection()));
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(final BlockPistonRetractEvent e) {
        e.getBlocks().forEach(b -> {
            this.changed(b);
            this.changed(b.getRelative(e.getDirection()));
        });
    }

    @EventHandler
    public void onWorldUnload(final WorldUnloadEvent e) {
        final World w = e.getWorld();
        for (final Doll d : this.dolls.values()) {
            if (d.world.equals(w)) {
                d.removed.set(true);
            }
        }
        final PhysicsLoop l = this.loop;
        if (l != null) {
            final UUID id = w.getUID();
            l.submit(() -> {
                for (final Doll d : this.dolls.values()) {
                    if (d.world.getUID().equals(id)) {
                        this.removeNow(d);
                    }
                }
                l.dropWorld(id);
            });
        }
    }

    // ================================================================== выключение

    /**
     * Глобальный поток (disable): остановить физику (тела — в её потоке), убрать дисплеи, которые этот поток может
     * убрать. Возвращает, сколько дисплеев остались в чужих регионах (несохраняемые — пропадут с выгрузкой чанка).
     */
    public int shutdown() {
        this.closed = true;
        final PhysicsLoop l = this.loop;
        if (l != null) {
            final boolean ok = l.stop(() -> {
                for (final Doll d : this.dolls.values()) {
                    if (d.body3d != null) {
                        d.body3d.destroy();
                        d.body3d = null;
                    }
                }
            }, 5000);
            if (!ok) {
                this.context.logger().warning("поток физики не остановился за 5 с");
            }
        }
        int left = 0;
        for (final Doll d : this.dolls.values()) {
            d.removed.set(true);
            left += this.renderer.despawn(d, false);
        }
        this.dolls.clear();
        return left;
    }
}
