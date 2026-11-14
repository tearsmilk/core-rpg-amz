package core.amazake.systems.coreportals.portal;

import core.amazake.systems.coreportals.Settings;
import core.amazake.systems.coreportals.anim.RingTimeline;
import core.amazake.systems.coreportals.geo.RingPlacement;
import core.amazake.systems.coreportals.geo.Spot;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Сущности изумрудного портала: 3 плоских item-дисплея (туманность чуть позади, кольцо-воронка, точка света
 * спереди) и {@link #SHARDS} осколков. Все стоят в одной точке — центре кольца, повёрнуты по yaw и двигаются только
 * трансформацией с интерполяцией. Не сохраняются в чанк. Поток региона портала.
 *
 * <p>Локальные оси дисплея: x — вбок, y — вверх, z — от игрока, открывшего портал (по направлению взгляда).</p>
 */
final class RingVisuals {

    /** Полуразмер квадрата кольца, блоков (шейдер: CP_VX_HALF). */
    static final float VORTEX_HALF = 2.4f;
    /** Полуразмер квадрата туманности, блоков (шейдер: CP_NB_HALF). */
    static final float NEBULA_HALF = 3.4f;
    /** Размер точки света при spark = 1, блоков. */
    static final float SPARK_SIZE = 0.9f;
    /** Туманность чуть позади кольца, точка — чуть впереди. */
    static final float NEBULA_BACK = 0.12f;
    static final float SPARK_FRONT = -0.06f;
    /** Осколков на портал (всего сущностей: 3 + SHARDS). */
    static final int SHARDS = 5;
    /** Орбита осколков: радиус (блоков), сплюснута по вертикали, чтобы нижние не уходили в землю. */
    private static final double ORBIT_MIN = 1.85;
    private static final double ORBIT_MAX = 2.2;
    private static final double ORBIT_FLAT = 0.72;
    /** Угловая скорость орбиты, рад/с (в ту же сторону, что вращение кольца). */
    private static final double ORBIT_SPEED = 0.16;
    private static final float MIN_SCALE = 0.001f;

    /** Параметры одного осколка (случайные на портал). */
    private record Shard(ItemDisplay display, double angle, double radius, double depth, float size,
                         Vector3f axis, double tumble, Quaternionf base, double bob) {
    }

    private final List<Entity> entities = new ArrayList<>();
    private final List<Shard> shards = new ArrayList<>();
    private ItemDisplay nebula;
    private ItemDisplay vortex;
    private ItemDisplay spark;
    private int phaseByte = -1;

    /** Поставить все части в начальном виде (всё — точка). */
    void spawn(final World world, final Spot spot, final Settings s, final RingTimeline tl, final String instance) {
        final Location at = new Location(world, spot.x(), spot.y() + RingPlacement.CENTER_UP, spot.z(),
            spot.facing().yaw(), 0f);
        this.nebula = this.item(world, at, s, instance, Markers.item("portal_nebula", Markers.NEBULA),
            Display.Billboard.FIXED);
        this.vortex = this.item(world, at, s, instance, vortexItem(1.0), Display.Billboard.FIXED);
        this.phaseByte = 255;
        this.spark = this.item(world, at, s, instance, Markers.item("portal_spark", Markers.SPARK),
            Display.Billboard.CENTER);
        final ThreadLocalRandom r = ThreadLocalRandom.current();
        final Color[] shades = new Color[Markers.SHARD_SHADES.length];
        for (int i = 0; i < shades.length; i++) {
            shades[i] = Markers.param(Markers.SHARD, Markers.SHARD_SHADES[i] / 255.0);
        }
        final double start = r.nextDouble(Math.PI * 2);
        for (int i = 0; i < SHARDS; i++) {
            final ItemDisplay d = this.item(world, at, s, instance, Markers.item("portal_shard", shades),
                Display.Billboard.FIXED);
            final Vector3f axis = new Vector3f((float) r.nextGaussian(), (float) r.nextGaussian(),
                (float) r.nextGaussian());
            if (axis.lengthSquared() < 1e-4f) {
                axis.set(0, 1, 0);
            }
            axis.normalize();
            final Quaternionf base = new Quaternionf().rotateXYZ((float) r.nextDouble(-0.6, 0.6),
                (float) r.nextDouble(Math.PI * 2), (float) r.nextDouble(-0.6, 0.6));
            this.shards.add(new Shard(d, start + i * (Math.PI * 2 / SHARDS) + r.nextDouble(-0.35, 0.35),
                r.nextDouble(ORBIT_MIN, ORBIT_MAX), r.nextDouble(-0.3, 0.3), (float) r.nextDouble(0.36, 0.58),
                axis, r.nextDouble(0.35, 0.8) * (r.nextBoolean() ? 1 : -1), base, r.nextDouble(Math.PI * 2)));
        }
        this.apply(tl, 0, 0, true);
    }

    /** Все части на месте. */
    boolean valid() {
        for (final Entity e : this.entities) {
            if (!e.isValid()) {
                return false;
            }
        }
        return !this.entities.isEmpty();
    }

    /**
     * Ключевой кадр: значения расписания на тике {@code t}, клиент доведёт их за {@code duration} тиков.
     *
     * @param full false — только осколки (открытый портал: кольцо, туманность и точка стоят)
     */
    void apply(final RingTimeline tl, final long t, final int duration, final boolean full) {
        if (full) {
            final float ring = Math.max((float) tl.ring(t), MIN_SCALE);
            final float side = 2 * VORTEX_HALF * ring;
            set(this.vortex, duration, new Vector3f(0, 0, 0), new Quaternionf().rotateZ((float) tl.spin(t)),
                new Vector3f(side, side, 1f));
            final int pb = (int) Math.round(Math.max(0, Math.min(1, tl.shaderPhase(t))) * 255);
            if (pb != this.phaseByte && this.vortex != null) {
                this.phaseByte = pb;
                this.vortex.setItemStack(vortexItem(pb / 255.0));
            }
            final float glow = Math.max((float) tl.glow(t), MIN_SCALE);
            set(this.nebula, duration, new Vector3f(0, 0, NEBULA_BACK), new Quaternionf(),
                new Vector3f(2 * NEBULA_HALF * glow, 2 * NEBULA_HALF * glow, 1f));
            final float sp = Math.max((float) (SPARK_SIZE * tl.spark(t)), MIN_SCALE);
            set(this.spark, duration, new Vector3f(0, 0, SPARK_FRONT), new Quaternionf(), new Vector3f(sp, sp, 1f));
        }
        final double sec = t / 20.0;
        final double rf = tl.shardRadius(t);
        final double size = tl.shardSize(t);
        // При закрытии осколки закручиваются по спирали внутрь.
        final double swirl = tl.closing(t) ? 2.2 * (1 - rf / Math.max(1e-3, tl.shardRadius(tl.closeStart()))) : 0;
        for (final Shard sh : this.shards) {
            final double a = sh.angle() + ORBIT_SPEED * sec + swirl;
            final double rad = sh.radius() * rf;
            final double x = Math.cos(a) * rad;
            final double y = Math.sin(a) * rad * ORBIT_FLAT + 0.07 * Math.sin(sec * 1.3 + sh.bob());
            final double z = (sh.depth() + 0.22 * Math.cos(a)) * rf;
            final Quaternionf rot = new Quaternionf().rotateAxis((float) (sh.tumble() * sec), sh.axis())
                .mul(sh.base());
            final float sc = Math.max((float) (sh.size() * size), MIN_SCALE);
            set(sh.display(), duration, new Vector3f((float) x, (float) y, (float) z), rot, new Vector3f(sc, sc, sc));
        }
    }

    /** Убрать все части. Поток региона портала. */
    void remove() {
        for (final Entity e : this.entities) {
            e.remove();
        }
        this.entities.clear();
        this.shards.clear();
    }

    /** Кольцо с фазой в синем канале маркера. */
    static ItemStack vortexItem(final double phase) {
        return Markers.item("portal_vortex", Markers.param(Markers.VORTEX, phase));
    }

    private static void set(final Display d, final int duration, final Vector3f translation, final Quaternionf rotation,
                            final Vector3f scale) {
        if (d == null) {
            return;
        }
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(duration);
        d.setTransformation(new Transformation(translation, rotation, scale, new Quaternionf()));
    }

    private ItemDisplay item(final World world, final Location at, final Settings s, final String instance,
                             final ItemStack stack, final Display.Billboard billboard) {
        final ItemDisplay d = world.spawn(at, ItemDisplay.class, e -> {
            e.setItemStack(stack);
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            e.setBrightness(new Display.Brightness(15, 15));
            e.setPersistent(false);
            e.setViewRange(s.viewRange());
            e.setShadowRadius(0f);
            e.setShadowStrength(0f);
            e.setBillboard(billboard);
            e.setInterpolationDelay(0);
            e.setInterpolationDuration(0);
            e.getPersistentDataContainer().set(PortalVisuals.PART, PersistentDataType.STRING, instance);
        });
        this.entities.add(d);
        return d;
    }
}
