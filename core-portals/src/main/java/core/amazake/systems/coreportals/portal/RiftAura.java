package core.amazake.systems.coreportals.portal;

import core.amazake.systems.api.SystemContext;
import core.amazake.systems.coreportals.Settings;
import core.amazake.systems.coreportals.anim.RiftTimeline;
import core.amazake.systems.coreportals.fx.AuraMath;
import core.amazake.systems.coreportals.geo.RiftPlacement;
import core.amazake.systems.coreportals.geo.Spot;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * «Аура» лазуритового разлома для игроков в радиусе: свечение по краям экрана (сильнее ближе), тонкие трещины на
 * экране, пока разлом раскрывается, и мягкое затягивание к разлому.
 *
 * <p><b>Экран.</b> Каждому игроку в радиусе — свой item-дисплей (модель {@code coreportals:portal_screen}, два
 * квада: свечение — маркер 82, трещины — 83, параметр в синем канале). Он невидим для всех
 * ({@code setVisibleByDefault(false)}) и показывается только этому игроку ({@code showEntity} в его потоке).
 * Вершинный шейдер ставит эти квады на весь экран (в clip space, у ближней плоскости), так что где стоит дисплей —
 * неважно, лишь бы клиент его рисовал: дисплей стоит в центре разлома (в радиусе видимости), а трансформация
 * переносит квад к глазам игрока, чтобы при сортировке прозрачного он рисовался последним. Яркость плавно идёт к
 * цели ({@link AuraMath#approach}); погасло — дисплей убирается.</p>
 *
 * <p><b>Затягивание</b> — мягкий дрейф, который не мешает ходить и прыгать. Раз в {@link #PULL_STEP} тика игроку,
 * который спокойно стоит или идёт по земле ({@link AuraMath#grounded}: на земле {@link AuraMath#GROUND_TICKS} тика
 * подряд без смещения по Y, клавиша прыжка не нажата, скорость по Y не вверх) и видит разлом, к его текущей
 * горизонтальной скорости (смещение за тик × трение под ногами) <b>добавляется</b> маленький толчок к центру
 * разлома ({@link AuraMath#pullVelocity}); к разлому не быстрее {@code pull.max-speed}. Вертикальная скорость не
 * трогается: Y ставится тот, что у игрока сейчас, а в прыжке, в воздухе и сразу после приземления толчка нет
 * вообще. Скорость ставится в потоке игрока ({@code runOnceForPlayer}), там же условия проверяются ещё раз.</p>
 *
 * <p>Потоки: всё, кроме {@code showEntity} и скорости, — в потоке региона разлома (его задача); игроки берутся
 * только принадлежащие этому региону. Если у игрока рядом несколько разломов, экран рисует один (первый занявший,
 * {@code screenOwners}).</p>
 */
final class RiftAura {

    /** Раз во столько тиков — толчок затягивания (маленький, но чаще — плавнее). */
    static final int PULL_STEP = 2;
    /** Раз во столько тиков — проверка прямой видимости (луч). */
    static final int SIGHT_STEP = 4;
    /** Раз во столько тиков дисплей экрана догоняет глаза игрока (с интерполяцией). */
    static final int FOLLOW_STEP = 2;
    /** Дальше радиуса на столько — дисплей убирается сразу, без затухания. */
    private static final double DROP_MARGIN = 6;
    /** Смещение больше этого за тик — телепорт, а не ходьба: толчка нет. */
    private static final double MAX_STEP = 0.8;
    private static final float SCREEN_SCALE = 0.01f;

    /** Один игрок в радиусе. Только поток региона разлома (кроме {@link #shown}). */
    private static final class Viewer {
        final Player player;
        ItemDisplay display;
        double glow;
        double cracks;
        int glowByte = -1;
        int crackByte = -1;
        volatile boolean shown;
        long showAsked = Long.MIN_VALUE;
        boolean sight = true;
        long sightTick = Long.MIN_VALUE;
        Vector lastPos;
        long lastPosTick = Long.MIN_VALUE;
        /** Тиков подряд на земле без смещения по Y (после прыжка — 0). */
        int groundTicks;

        Viewer(final Player player) {
            this.player = player;
        }
    }

    private final SystemContext context;
    private final World world;
    private final Spot spot;
    private final RiftPlacement.Size size;
    private final String instance;
    private final ConcurrentMap<UUID, Object> screenOwners;
    private final Map<UUID, Viewer> viewers = new HashMap<>();
    private final Location center;

    RiftAura(final SystemContext context, final World world, final Spot spot, final RiftPlacement.Size size,
             final String instance, final ConcurrentMap<UUID, Object> screenOwners) {
        this.context = context;
        this.world = world;
        this.spot = spot;
        this.size = size;
        this.instance = instance;
        this.screenOwners = screenOwners;
        final double[] c = spot.toWorld(0, size.centerUp(), 0);
        this.center = new Location(world, c[0], c[1], c[2], 0f, 0f);
    }

    /** Тик ауры. Поток региона разлома. */
    void tick(final long t, final Settings s, final RiftTimeline tl) {
        final Settings.Lapis l = s.lapis();
        final double radius = l.radius();
        final double open = tl.openness(t);
        final double cracks = l.screenCracks() ? tl.screenCracks(t) : 0;
        final Set<UUID> seen = new HashSet<>();
        if (radius > 0) {
            final double scan = radius + Math.max(this.size.halfWidth(), this.size.halfHeight()) + 1;
            for (final Player p : this.world.getNearbyPlayers(this.center, scan)) {
                if (!Bukkit.isOwnedByCurrentRegion(p) || p.isDead() || p.getGameMode() == GameMode.SPECTATOR) {
                    continue;
                }
                final Location eye = p.getEyeLocation();
                final double d = RiftPlacement.distance(this.spot, this.size, eye.getX(), eye.getY(), eye.getZ());
                if (d >= radius) {
                    continue;
                }
                final UUID id = p.getUniqueId();
                seen.add(id);
                final Viewer v = this.viewers.computeIfAbsent(id, k -> new Viewer(p));
                if (v.sightTick == Long.MIN_VALUE || t - v.sightTick >= SIGHT_STEP) {
                    v.sight = this.sight(eye);
                    v.sightTick = t;
                }
                final double target = l.screenGlow() ? AuraMath.glowTarget(d, radius, open, v.sight) : 0;
                v.glow = AuraMath.approach(v.glow, target, 1);
                v.cracks = cracks;
                this.pull(v, p, t, d, radius, open, l);
            }
        }
        for (final Iterator<Map.Entry<UUID, Viewer>> it = this.viewers.entrySet().iterator(); it.hasNext(); ) {
            final Map.Entry<UUID, Viewer> e = it.next();
            final Viewer v = e.getValue();
            if (!seen.contains(e.getKey())) {
                v.lastPos = null;
                if (this.gone(v.player, radius)) {
                    this.dropScreen(e.getKey(), v);
                    it.remove();
                    continue;
                }
                v.glow = AuraMath.approach(v.glow, 0, 1);
                v.cracks = 0;
            }
            if (!this.updateScreen(e.getKey(), v, t, s) && !seen.contains(e.getKey())) {
                it.remove();
            }
        }
    }

    /** Игрок вышел, умер, ушёл в другой мир или далеко (или он не в этом регионе): экран убрать сразу. */
    private boolean gone(final Player p, final double radius) {
        if (!p.isOnline() || !Bukkit.isOwnedByCurrentRegion(p) || p.isDead() || !p.getWorld().equals(this.world)) {
            return true;
        }
        final Location eye = p.getEyeLocation();
        return RiftPlacement.distance(this.spot, this.size, eye.getX(), eye.getY(), eye.getZ()) > radius + DROP_MARGIN;
    }

    /**
     * Дисплей экрана по текущим значениям: поставить, обновить, убрать. Возвращает {@code false}, если экран погас
     * и убран.
     */
    private boolean updateScreen(final UUID id, final Viewer v, final long t, final Settings s) {
        final int gb = AuraMath.toByte(v.glow);
        final int cb = AuraMath.toByte(v.cracks);
        if (gb == 0 && cb == 0) {
            this.dropScreen(id, v);
            return v.glow > AuraMath.EPSILON;
        }
        final Player p = v.player;
        if (v.display == null || !v.display.isValid()) {
            final Object owner = this.screenOwners.putIfAbsent(id, this);
            if (owner != null && owner != this) {
                return true;   // экран этому игроку уже рисует другой разлом
            }
            if (!Bukkit.isOwnedByCurrentRegion(p)) {
                return true;
            }
            v.display = this.spawnScreen(p, gb, cb, s);
            v.glowByte = gb;
            v.crackByte = cb;
            v.shown = false;
            v.showAsked = Long.MIN_VALUE;
        } else if (gb != v.glowByte || cb != v.crackByte) {
            v.glowByte = gb;
            v.crackByte = cb;
            v.display.setItemStack(screenItem(gb, cb));
        }
        if (!v.shown && (v.showAsked == Long.MIN_VALUE || t - v.showAsked >= 10)) {
            v.showAsked = t;
            final ItemDisplay d = v.display;
            this.context.runOnceForPlayer(p, () -> {
                if (d.isValid() && Bukkit.isOwnedByCurrentRegion(d)) {
                    p.showEntity(this.context.plugin(), d);
                    v.shown = true;
                }
            });
        }
        if (t % FOLLOW_STEP == 0 && Bukkit.isOwnedByCurrentRegion(p)) {
            setTranslation(v.display, this.toEye(p), FOLLOW_STEP);
        }
        return true;
    }

    private ItemDisplay spawnScreen(final Player p, final int gb, final int cb, final Settings s) {
        final Vector3f eye = this.toEye(p);
        return this.world.spawn(this.center, ItemDisplay.class, e -> {
            e.setVisibleByDefault(false);
            e.setItemStack(screenItem(gb, cb));
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            e.setBrightness(new Display.Brightness(15, 15));
            e.setPersistent(false);
            e.setViewRange(s.viewRange());
            e.setShadowRadius(0f);
            e.setShadowStrength(0f);
            e.setBillboard(Display.Billboard.FIXED);
            e.setInterpolationDelay(0);
            e.setInterpolationDuration(0);
            e.setTransformation(new Transformation(eye, new Quaternionf(),
                new Vector3f(SCREEN_SCALE, SCREEN_SCALE, SCREEN_SCALE), new Quaternionf()));
            e.getPersistentDataContainer().set(PortalVisuals.PART, PersistentDataType.STRING, this.instance);
        });
    }

    /** Глаза игрока относительно дисплея экрана (yaw 0: оси дисплея = оси мира). */
    private Vector3f toEye(final Player p) {
        final Location eye = p.getEyeLocation();
        return new Vector3f((float) (eye.getX() - this.center.getX()), (float) (eye.getY() - this.center.getY()),
            (float) (eye.getZ() - this.center.getZ()));
    }

    private void dropScreen(final UUID id, final Viewer v) {
        if (v.display != null) {
            v.display.remove();
            v.display = null;
        }
        v.glowByte = -1;
        v.crackByte = -1;
        v.shown = false;
        this.screenOwners.remove(id, this);
    }

    /**
     * Затягивание: каждый тик — учёт смещения и «стоит ли на земле», раз в {@link #PULL_STEP} тика — толчок
     * (добавка к горизонтальной скорости, Y не трогается). Скорость ставится в потоке игрока.
     */
    private void pull(final Viewer v, final Player p, final long t, final double d, final double radius,
                      final double open, final Settings.Lapis l) {
        final Location at = p.getLocation();
        final Vector pos = at.toVector();
        final Vector prev = v.lastPos;
        final long prevTick = v.lastPosTick;
        v.lastPos = pos;
        v.lastPosTick = t;
        if (prev == null || t - prevTick != 1) {
            v.groundTicks = 0;
            return;
        }
        final double dy = pos.getY() - prev.getY();
        final boolean onGround = onGround(p) && !p.isFlying() && !p.isGliding() && !p.isInsideVehicle()
            && !p.isSwimming() && !p.isInWater();
        v.groundTicks = onGround && Math.abs(dy) <= AuraMath.FLAT_DY ? Math.min(v.groundTicks + 1, 1000) : 0;
        if (t % PULL_STEP != 0) {
            return;
        }
        final double nudge = AuraMath.pull(d, radius, open, v.sight, l.pullStrength());
        if (nudge <= 0) {
            return;
        }
        final double vy = p.getVelocity().getY();
        if (!AuraMath.grounded(onGround, v.groundTicks, dy, vy, jumpKey(p))) {
            return;
        }
        final double dx = pos.getX() - prev.getX();
        final double dz = pos.getZ() - prev.getZ();
        if (dx * dx + dz * dz > MAX_STEP * MAX_STEP) {
            return;   // телепорт, а не ходьба
        }
        final double dirX = this.center.getX() - pos.getX();
        final double dirZ = this.center.getZ() - pos.getZ();
        if (dirX * dirX + dirZ * dirZ < 0.04) {
            return;
        }
        final double[] nv = AuraMath.pullVelocity(dx, vy, dz, this.friction(at), dirX, dirZ, nudge,
            l.pullMaxSpeed(), true);
        if (nv == null) {
            return;
        }
        this.context.runOnceForPlayer(p, () -> {
            if (!p.isOnline() || !p.getWorld().equals(this.world) || !onGround(p) || jumpKey(p)) {
                return;
            }
            // Y — тот, что у игрока сейчас (не наш): летит вверх — не трогаем вовсе.
            final double nowY = p.getVelocity().getY();
            if (nowY > 0) {
                return;
            }
            p.setVelocity(new Vector(nv[0], nowY, nv[2]));
        });
    }

    /** Трение под ногами: скользкость блока × 0,91 (лёд скользит дольше). Поток региона игрока. */
    @SuppressWarnings("deprecation")
    private double friction(final Location at) {
        try {
            final float slip = this.world.getBlockAt(at.getBlockX(), (int) Math.floor(at.getY() - 0.5),
                at.getBlockZ()).getType().getSlipperiness();
            return slip > 0 ? slip * 0.91 : AuraMath.GROUND_FRICTION;
        } catch (final RuntimeException e) {
            return AuraMath.GROUND_FRICTION;
        }
    }

    /** Нажата ли клавиша прыжка (ввод клиента, 1.21.2+). */
    private static boolean jumpKey(final Player p) {
        try {
            return p.getCurrentInput().isJump();
        } catch (final RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** Прямая видимость от глаз до разлома: до середины ближней части или до центра. */
    private boolean sight(final Location eye) {
        final double[] n = RiftPlacement.nearest(this.spot, new RiftPlacement.Size(this.size.width() * 0.6,
            this.size.height() * 0.6, this.size.centerUp()), eye.getX(), eye.getY(), eye.getZ());
        return this.clear(eye, n[0], n[1], n[2]) || this.clear(eye, this.center.getX(), this.center.getY(),
            this.center.getZ());
    }

    private boolean clear(final Location eye, final double x, final double y, final double z) {
        final Vector dir = new Vector(x - eye.getX(), y - eye.getY(), z - eye.getZ());
        final double len = dir.length();
        if (len < 0.5) {
            return true;
        }
        try {
            final RayTraceResult hit = this.world.rayTraceBlocks(eye, dir.multiply(1 / len), len - 0.3,
                FluidCollisionMode.NEVER, true);
            return hit == null;
        } catch (final RuntimeException e) {
            return false;   // луч зашёл в чужой регион: считаем, что не видно
        }
    }

    /** Убрать все дисплеи экрана. Поток региона разлома. */
    void removeAll() {
        for (final Map.Entry<UUID, Viewer> e : this.viewers.entrySet()) {
            this.dropScreen(e.getKey(), e.getValue());
        }
        this.viewers.clear();
    }

    /** Стоит на земле (по словам клиента — для мягкого эффекта этого достаточно). */
    @SuppressWarnings("deprecation")
    private static boolean onGround(final Player p) {
        return p.isOnGround();
    }

    static ItemStack screenItem(final int glowByte, final int crackByte) {
        return Markers.item("portal_screen", Markers.paramRaw(Markers.SCREEN_GLOW, glowByte),
            Markers.paramRaw(Markers.SCREEN_CRACKS, crackByte));
    }

    private static void setTranslation(final ItemDisplay d, final Vector3f translation, final int duration) {
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(duration);
        d.setTransformation(new Transformation(translation, new Quaternionf(),
            new Vector3f(SCREEN_SCALE, SCREEN_SCALE, SCREEN_SCALE), new Quaternionf()));
    }
}
