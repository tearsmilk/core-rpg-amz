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
 * <p>Модели кромок и трещины нарисованы в масштабе 1:3 (16 px модели = 3 блока) и растягиваются дисплеем ×3;
 * поверхность — единичный квадрат, растянутый на проём.</p>
 */
final class PortalVisuals {

    /** Метка «это часть портала»: значение — id экземпляра системы, чтобы убрать хвосты прошлых запусков. */
    static final NamespacedKey PART = new NamespacedKey("coreportals", "part");

    private static final float HALF = 1.25f;         // полуширина рамы (проём 2.5 блока)
    private static final float PILLAR = (float) Spot.PILLAR;   // толщина стоек и перекладины (тонкая рама)
    static final float DEPTH = 0.14f;                // толщина рамы
    private static final float HEIGHT = (float) Spot.HEIGHT;   // высота рамы (3.5)
    private static final float INNER_TOP = HEIGHT - PILLAR;
    private static final float SINK = HEIGHT + 0.25f;          // на сколько рама уходит под землю
    private static final float MODEL_SCALE = 3.0f;   // масштаб моделей 1:3 (16 px модели = 3 блока)
    /**
     * Модель кромок нарисована в масштабе 1:3 по всем осям точно по раме: середины полос лежат на внешней и
     * внутренней границе стоек и перекладины, плоскости — на передней и задней гранях рамы (см. README).
     * Центр модели (8 px) — на высоте {@link #EDGES_CENTER} над землёй.
     */
    private static final float EDGES_CENTER = MODEL_SCALE / 2;
    /** Ширина трещины на земле: модель растягивается по ширине проёма. */
    private static final float GROUND_X = 1.3f;
    /** Щель при закрытии: самая узкая видимая доля проёма. */
    private static final float SEAM = 0.015f;
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
        set(this.edges, duration, new Vector3f(0, EDGES_CENTER + off, 0),
            new Vector3f(MODEL_SCALE, MODEL_SCALE, MODEL_SCALE));
        final float g = Math.max((float) tl.ground(t), MIN_SCALE);
        set(this.ground, duration, new Vector3f(0, MODEL_SCALE / 2, 0),
            new Vector3f(MODEL_SCALE * GROUND_X * g, MODEL_SCALE, MODEL_SCALE * g));
        // Закрытие: поверхность сходится в светящуюся щель, щель стягивается к середине в точку.
        final float hf = (float) tl.surfaceHeight(t);
        float o = (float) tl.surface(t);
        if (tl.closing(t) && hf > 0) {
            o = Math.max(o, SEAM);
        }
        o = Math.max(o, MIN_SCALE);
        final float h = Math.max((float) Spot.SURFACE_HEIGHT * hf, MIN_SCALE);
        set(this.surface, duration, new Vector3f(0, (float) Spot.SURFACE_HEIGHT / 2, 0),
            new Vector3f((float) (2 * Spot.SURFACE_HALF_WIDTH) * o, h, 1f));
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
