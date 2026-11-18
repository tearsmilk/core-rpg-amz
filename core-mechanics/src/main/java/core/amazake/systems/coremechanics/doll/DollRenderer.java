package core.amazake.systems.coremechanics.doll;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import core.amazake.systems.coremechanics.physics.RagdollSpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;

/**
 * Отрисовка кукол дисплеями. ВСЁ, что трогает сущности, выполняется в потоке региона якоря куклы.
 *
 * <p><b>Якорь.</b> Все дисплеи куклы стоят в одной точке (якоре), поза задаётся только трансформацией (смещение части
 * от якоря входит в translation). Каждый тик обновляется лишь трансформация — это метаданные сущности в её же регионе,
 * без телепортов. Когда туловище уходит от якоря дальше {@link #REANCHOR} блоков (бросили, укатилась), все дисплеи
 * переезжают {@code teleportAsync} в новую точку — так кукла переходит границы регионов: сущности сами перейдут в
 * регион нового якоря, следующие задачи отрисовки ставятся уже туда. Позиция (teleportDuration 1) и трансформация
 * (interpolationDuration 1) интерполируются клиентом за один тик, поэтому переезд не дёргается.</p>
 */
public final class DollRenderer {

    /** Метка дисплеев кукол (scoreboard-tag + PDC) — хвосты после аварийного выключения убираются по ней. */
    public static final String TAG = "coremechanics_doll";
    static final double REANCHOR = 8.0;

    private final Plugin plugin;
    private final NamespacedKey key;
    /** UUID живых дисплеев (чтобы чистка хвостов не тронула свои). */
    private final Set<UUID> live = ConcurrentHashMap.newKeySet();

    public DollRenderer(final Plugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey("coremechanics", "doll");
    }

    public boolean isOurs(final Entity e) {
        return e.getScoreboardTags().contains(TAG) || e.getPersistentDataContainer().has(this.key, PersistentDataType.BYTE);
    }

    public boolean isLive(final Entity e) {
        return this.live.contains(e.getUniqueId());
    }

    public int liveCount() {
        return this.live.size();
    }

    /** Любой поток: поставить отрисовку в поток региона якоря (не чаще одной задачи в очереди на куклу). */
    public void schedule(final Doll d) {
        if (!d.renderQueued.compareAndSet(false, true)) {
            return;
        }
        final int cx = (int) Math.floor(d.ax) >> 4, cz = (int) Math.floor(d.az) >> 4;
        try {
            Bukkit.getRegionScheduler().execute(this.plugin, d.world, cx, cz, () -> {
                d.renderQueued.set(false);
                this.render(d);
            });
        } catch (final RuntimeException e) {
            d.renderQueued.set(false);   // плагин/мир выключаются
        }
    }

    /** Поток региона якоря: привести дисплеи к текущей позе. */
    public void render(final Doll d) {
        d.lastRenderNanos = System.nanoTime();
        if (d.removed.get()) {
            this.despawn(d, true);
            return;
        }
        if (d.moving) {
            return;
        }
        final Pose p = d.pose;
        if (p == null) {
            return;
        }
        if (!d.world.isChunkLoaded((int) Math.floor(d.ax) >> 4, (int) Math.floor(d.az) >> 4)) {
            return;   // чанк выгружен — сущности ушли вместе с ним; рэгдолл уберёт поток физики по таймауту
        }
        if (d.spawned && !this.allValid(d)) {
            // дисплеи убиты извне (/kill, чистка) или выгружены: манекен поставим заново, рэгдолл — убрать
            this.despawn(d, true);
            if (d.physical) {
                d.removed.set(true);
                return;
            }
        }
        if (!d.spawned) {
            this.spawnAll(d, p);
            return;
        }
        if (d.shownBody != d.body) {
            this.rebuildBody(d, p);
        }
        final double[] c = p.torso();
        final double dx = c[0] - d.ax, dy = c[1] - d.ay, dz = c[2] - d.az;
        if (dx * dx + dy * dy + dz * dz > REANCHOR * REANCHOR) {
            this.reanchor(d, p, c[0], c[1], c[2]);
            return;
        }
        this.apply(d, p);
    }

    private boolean allValid(final Doll d) {
        for (final Doll.Slot s : d.slots) {
            if (s.entity == null || !s.entity.isValid()) {
                return false;
            }
        }
        return true;
    }

    private void apply(final Doll d, final Pose p) {
        final int glow = d.glowPart;
        for (final Doll.Slot s : d.slots) {
            final Display e = s.entity;
            if (e == null || !Bukkit.isOwnedByCurrentRegion(e)) {
                continue;
            }
            this.transform(e, s.piece, p, d.ax, d.ay, d.az);
            if (glow != d.glowShown) {
                e.setGlowing(glow >= 0 && s.piece.part() == glow);
            }
        }
        d.glowShown = glow;
    }

    private void transform(final Display e, final Piece pc, final Pose p, final double ax, final double ay, final double az) {
        final Transformation t = PieceTransform.of(pc, p.pos()[pc.part()], p.quat(pc.part()), ax, ay, az);
        if (t.equals(e.getTransformation())) {
            return;
        }
        e.setInterpolationDelay(0);
        e.setInterpolationDuration(1);
        e.setTransformation(t);
    }

    private void spawnAll(final Doll d, final Pose p) {
        d.slots.clear();
        final List<Piece> all = new ArrayList<>();
        all.add(d.headPiece);
        final List<Piece> body = d.body;
        all.addAll(body);
        for (final Piece pc : all) {
            final Doll.Slot s = new Doll.Slot(pc);
            s.entity = this.spawn(d, pc, p);
            s.ex = d.ax;
            s.ez = d.az;
            d.slots.add(s);
        }
        d.shownBody = body;
        d.spawned = true;
        d.glowShown = -1;
    }

    /** Тело сменилось (кубики скина готовы): убрать старые куски тела, поставить новые. Голова остаётся. */
    private void rebuildBody(final Doll d, final Pose p) {
        final List<Piece> body = d.body;
        for (final Doll.Slot s : new ArrayList<>(d.slots)) {
            if (s.piece != d.headPiece) {
                this.removeEntity(d, s, true);
                d.slots.remove(s);
            }
        }
        for (final Piece pc : body) {
            final Doll.Slot s = new Doll.Slot(pc);
            s.entity = this.spawn(d, pc, p);
            s.ex = d.ax;
            s.ez = d.az;
            d.slots.add(s);
        }
        d.shownBody = body;
        d.glowShown = -1;
    }

    private Display spawn(final Doll d, final Piece pc, final Pose p) {
        final World w = d.world;
        final Location at = new Location(w, d.ax, d.ay, d.az);
        final Transformation t = PieceTransform.of(pc, p.pos()[pc.part()], p.quat(pc.part()), d.ax, d.ay, d.az);
        final Display e;
        if (pc.head()) {
            final ItemStack item = this.headItem(d, pc);
            e = w.spawn(at, ItemDisplay.class, x -> {
                x.setItemStack(item);
                this.prep(x, t);
            });
        } else {
            final Material m = pc.block();
            e = w.spawn(at, BlockDisplay.class, x -> {
                x.setBlock(m.createBlockData());
                this.prep(x, t);
            });
        }
        this.live.add(e.getUniqueId());
        return e;
    }

    private void prep(final Display x, final Transformation t) {
        x.setPersistent(false);
        x.setTeleportDuration(1);
        x.setInterpolationDuration(1);
        x.addScoreboardTag(TAG);
        x.getPersistentDataContainer().set(this.key, PersistentDataType.BYTE, (byte) 1);
        x.setTransformation(t);
    }

    private ItemStack headItem(final Doll d, final Piece pc) {
        final ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        if (it.getItemMeta() instanceof SkullMeta sm) {
            final PlayerProfile pp;
            if (pc == d.headPiece) {
                pp = Bukkit.createProfile(d.owner, d.name);
            } else {
                pp = Bukkit.createProfile(UUID.nameUUIDFromBytes(pc.texture().getBytes(StandardCharsets.UTF_8)), "rd");
            }
            if (pc.texture() != null) {
                pp.setProperty(new ProfileProperty("textures", pc.texture(), pc.signature()));
            }
            sm.setPlayerProfile(pp);
            it.setItemMeta(sm);
        }
        return it;
    }

    /** Переезд всех дисплеев в новый якорь (возможно — в другой регион). */
    private void reanchor(final Doll d, final Pose p, final double nx, final double ny, final double nz) {
        d.moving = true;
        final AtomicInteger left = new AtomicInteger(d.slots.size());
        final AtomicBoolean failed = new AtomicBoolean();
        final Location to = new Location(d.world, nx, ny, nz);
        for (final Doll.Slot s : d.slots) {
            final Display e = s.entity;
            e.teleportAsync(to).whenComplete((ok, ex) -> {
                if (Boolean.TRUE.equals(ok) && ex == null) {
                    s.ex = nx;
                    s.ez = nz;
                    if (Bukkit.isOwnedByCurrentRegion(e)) {
                        // в том же тике, что и телепорт: клиент интерполирует позицию и трансформацию вместе
                        this.transform(e, s.piece, d.pose, nx, ny, nz);
                    }
                } else {
                    failed.set(true);
                }
                if (left.decrementAndGet() == 0) {
                    d.ax = nx;
                    d.ay = ny;
                    d.az = nz;
                    d.moving = false;
                    if (failed.get()) {
                        // телепорт не удался (чанк назначения не загрузился) — куклу убрать, хвостов не оставлять
                        d.removed.set(true);
                    }
                    this.schedule(d);
                }
            });
        }
        if (d.slots.isEmpty()) {
            d.ax = nx;
            d.ay = ny;
            d.az = nz;
            d.moving = false;
        }
    }

    /**
     * Убрать все дисплеи куклы. Свои (этого региона) — сразу; чужие при queue=true — задачей их региона (при выключении
     * queue=false: задачи регионов ядро отменяет после disable — такие хвосты несохраняемые и уберутся сами).
     *
     * @return сколько дисплеев не удалось убрать сразу
     */
    public int despawn(final Doll d, final boolean queue) {
        int left = 0;
        for (final Doll.Slot s : d.slots) {
            if (!this.removeEntity(d, s, queue)) {
                left++;
            }
        }
        d.slots.clear();
        d.spawned = false;
        d.shownBody = null;
        return left;
    }

    private boolean removeEntity(final Doll d, final Doll.Slot s, final boolean queue) {
        final Display e = s.entity;
        s.entity = null;
        if (e == null) {
            return true;
        }
        if (!e.isValid()) {
            this.live.remove(e.getUniqueId());
            return true;
        }
        if (Bukkit.isOwnedByCurrentRegion(e)) {
            e.remove();
            this.live.remove(e.getUniqueId());
            return true;
        }
        if (queue) {
            Bukkit.getRegionScheduler().execute(this.plugin, d.world, (int) Math.floor(s.ex) >> 4, (int) Math.floor(s.ez) >> 4, () -> {
                if (e.isValid()) {
                    e.remove();
                }
                this.live.remove(e.getUniqueId());
            });
            return true;
        }
        return false;
    }

    /** Часть куклы под этим дисплеем (для подсветки/отладки), -1 — не наш. */
    public static int partOf(final Doll d, final Entity e) {
        for (final Doll.Slot s : d.slots) {
            if (s.entity != null && s.entity.getUniqueId().equals(e.getUniqueId())) {
                return s.piece.part();
            }
        }
        return RagdollSpec.N;
    }
}
