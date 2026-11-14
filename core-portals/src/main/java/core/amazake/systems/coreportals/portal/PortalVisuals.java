package core.amazake.systems.coreportals.portal;

import core.amazake.systems.coreportals.Settings;
import core.amazake.systems.coreportals.anim.Timeline;
import core.amazake.systems.coreportals.geo.Spot;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Сущности одного портала: тёмная рама из трёх блок-дисплеев и четыре item-дисплея с маркерными моделями
 * (кромки, пламя, трещина, поверхность). Все стоят в одной точке — середине основания портала, повёрнуты по yaw
 * и двигаются только трансформацией с интерполяцией. Не сохраняются в чанк. Поток региона портала.
 *
 * <p>Модели кромок, пламени и трещины нарисованы в масштабе 1:3 (16 px модели = 3 блока) и растягиваются
 * дисплеем ×3; поверхность — единичный квадрат, растянутый на проём.</p>
 */
final class PortalVisuals {

    /** Метка «это часть портала»: значение — id экземпляра системы, чтобы убрать хвосты прошлых запусков. */
    static final NamespacedKey PART = new NamespacedKey("coreportals", "part");

    private static final float HALF = 1.0f;          // полуширина рамы
    private static final float PILLAR = 0.18f;       // толщина стоек и перекладины
    private static final float DEPTH = 0.3f;         // толщина рамы
    private static final float HEIGHT = 3.0f;        // высота рамы
    private static final float INNER_TOP = HEIGHT - PILLAR;
    private static final float SINK = 3.25f;         // на сколько рама уходит под землю
    private static final float MODEL_SCALE = 3.0f;   // масштаб моделей 1:3
    private static final float MIN_SCALE = 0.001f;

    private final List<Entity> entities = new ArrayList<>();
    private BlockDisplay left;
    private BlockDisplay right;
    private BlockDisplay top;
    private ItemDisplay edges;
    private ItemDisplay flames;
    private ItemDisplay ground;
    private ItemDisplay surface;

    /** Поставить все части в начальном виде (рама под землёй, поверхность — линия). */
    void spawn(final World world, final Spot spot, final Settings s, final Timeline tl, final String instance) {
        final Location at = new Location(world, spot.x(), spot.y(), spot.z(), spot.facing().yaw(), 0f);
        Material frame = Material.matchMaterial(s.frameBlock());
        if (frame == null || !frame.isBlock()) {
            frame = Material.POLISHED_BLACKSTONE;
        }
        final BlockData block = frame.createBlockData();
        this.left = this.block(world, at, block, s, instance);
        this.right = this.block(world, at, block, s, instance);
        this.top = this.block(world, at, block, s, instance);
        this.edges = this.item(world, at, s, instance, Markers.item("portal_edges", Markers.EDGE_VERTICAL, Markers.EDGE_HORIZONTAL));
        this.flames = this.item(world, at, s, instance, Markers.item("portal_flames", Markers.FLAMES));
        this.ground = this.item(world, at, s, instance, Markers.item("portal_ground", Markers.GROUND));
        this.surface = this.item(world, at, s, instance, Markers.item("portal_surface", Markers.SURFACE));
        this.apply(tl, 0, 0);
    }

    /** Все части на месте (никто не убил дисплей командой). */
    boolean valid() {
        for (final Entity e : this.entities) {
            if (!e.isValid()) {
                return false;
            }
        }
        return !this.entities.isEmpty();
    }

    /** Ключевой кадр: значения расписания на тике {@code t}, клиент доведёт их за {@code duration} тиков. */
    void apply(final Timeline tl, final long t, final int duration) {
        final float off = (float) ((tl.rise(t) - 1) * SINK);
        set(this.left, duration, new Vector3f(-HALF, off, -DEPTH / 2), new Vector3f(PILLAR, HEIGHT, DEPTH));
        set(this.right, duration, new Vector3f(HALF - PILLAR, off, -DEPTH / 2), new Vector3f(PILLAR, HEIGHT, DEPTH));
        set(this.top, duration, new Vector3f(-HALF + PILLAR, INNER_TOP + off, -DEPTH / 2),
            new Vector3f(2 * (HALF - PILLAR), PILLAR, DEPTH));
        set(this.edges, duration, new Vector3f(0, MODEL_SCALE / 2 + off, 0),
            new Vector3f(MODEL_SCALE, MODEL_SCALE, MODEL_SCALE));
        final float f = Math.max((float) tl.flames(t), MIN_SCALE);
        set(this.flames, duration, new Vector3f(0, MODEL_SCALE / 2 * f, 0),
            new Vector3f(MODEL_SCALE, MODEL_SCALE * f, MODEL_SCALE));
        final float g = Math.max((float) tl.ground(t), MIN_SCALE);
        set(this.ground, duration, new Vector3f(0, MODEL_SCALE / 2, 0),
            new Vector3f(MODEL_SCALE * g, MODEL_SCALE, MODEL_SCALE * g));
        final float o = Math.max((float) tl.surface(t), MIN_SCALE);
        set(this.surface, duration, new Vector3f(0, (float) Spot.SURFACE_HEIGHT / 2, 0),
            new Vector3f((float) (2 * Spot.SURFACE_HALF_WIDTH) * o, (float) Spot.SURFACE_HEIGHT, 1f));
    }

    /** Убрать все части. Поток региона портала. */
    void remove() {
        for (final Entity e : this.entities) {
            e.remove();
        }
        this.entities.clear();
    }

    private static void set(final Display d, final int duration, final Vector3f translation, final Vector3f scale) {
        if (d == null) {
            return;
        }
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(duration);
        d.setTransformation(new Transformation(translation, new Quaternionf(), scale, new Quaternionf()));
    }

    private BlockDisplay block(final World world, final Location at, final BlockData data, final Settings s,
                               final String instance) {
        final BlockDisplay d = world.spawn(at, BlockDisplay.class, e -> {
            e.setBlock(data);
            this.common(e, s, instance);
        });
        this.entities.add(d);
        return d;
    }

    private ItemDisplay item(final World world, final Location at, final Settings s, final String instance,
                             final org.bukkit.inventory.ItemStack stack) {
        final ItemDisplay d = world.spawn(at, ItemDisplay.class, e -> {
            e.setItemStack(stack);
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            e.setBrightness(new Display.Brightness(15, 15));
            this.common(e, s, instance);
        });
        this.entities.add(d);
        return d;
    }

    private void common(final Display e, final Settings s, final String instance) {
        e.setPersistent(false);
        e.setViewRange(s.viewRange());
        e.setShadowRadius(0f);
        e.setShadowStrength(0f);
        e.setBillboard(Display.Billboard.FIXED);
        e.setInterpolationDelay(0);
        e.setInterpolationDuration(0);
        e.getPersistentDataContainer().set(PART, PersistentDataType.STRING, instance);
    }
}
