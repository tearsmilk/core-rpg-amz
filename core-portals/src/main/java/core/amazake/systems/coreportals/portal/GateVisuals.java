package core.amazake.systems.coreportals.portal;

import core.amazake.systems.coreportals.Settings;
import core.amazake.systems.coreportals.anim.GateTimeline;
import core.amazake.systems.coreportals.geo.GateAperture;
import core.amazake.systems.coreportals.geo.Spot;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Дисплеи «Врат междумирья». Все стоят в одной точке ({@link #ANCHOR_Y} под землёй под центром врат, повёрнуты
 * лицом к игроку), смещение и размер — трансформацией:
 * <ul>
 *   <li>{@code gate_frame} — объёмная готическая рама (модель из блоков ванили, без маркера); масштаб 10,
 *       поднимается из-под земли ({@link GateTimeline#reveal(long)});</li>
 *   <li>{@code gate_surface} (маркер {@link Markers#GATE_SURFACE}) — энергетическое кольцо в проёме, размер —
 *       {@link GateAperture};</li>
 *   <li>{@code gate_smoke} (маркеры {@link Markers#GATE_SMOKE} за рамой и {@link Markers#GATE_CLOUDS} перед ней) —
 *       облака 8,4 × 12,5, синий канал — сила появления;</li>
 *   <li>{@code gate_floor} (маркер {@link Markers#GATE_FLOOR}) — свечение 8 × 8 на полу перед вратами.</li>
 * </ul>
 * Рисует их шейдер {@code coreportals:gate_fragment.glsl} из пакета ресурсов.
 */
final class GateVisuals {

    private final List<Entity> entities = new ArrayList<>();
    /** Точка привязки дисплеев относительно земли: на 2 блока ниже (рама поднимается из-под земли). */
    static final float ANCHOR_Y = -2.0f;
    /** Размер рамки отсечения дисплеев (клиент не прячет врата, пока они в кадре). */
    static final float CULL_WIDTH = 18.0f;
    static final float CULL_HEIGHT = 18.0f;
    private ItemDisplay frame;
    private ItemDisplay surface;
    private ItemDisplay smoke;
    private ItemDisplay clouds;
    private ItemDisplay floor;
    /** Последние отправленные байты параметра дыма, облаков и пола: предмет обновляется, только если он сменился. */
    private final int[] bytes = {-1, -1, -1};

    void spawn(final World world, final Spot spot, final Settings s, final String instance, final GateTimeline tl) {
        final Location at = new Location(world, spot.x(), spot.y() + ANCHOR_Y, spot.z(), spot.facing().yaw(), 0.0f);
        this.frame = this.item(world, at, s, instance, "gate_frame", -1);
        this.surface = this.item(world, at, s, instance, "gate_surface", Markers.GATE_SURFACE);
        this.smoke = this.item(world, at, s, instance, "gate_smoke", Markers.GATE_SMOKE);
        this.clouds = this.item(world, at, s, instance, "gate_smoke", Markers.GATE_CLOUDS);
        this.floor = this.item(world, at, s, instance, "gate_floor", Markers.GATE_FLOOR);
        // Облака за рамой (+0,6) и перед ней (−0,6), пол — лежит плашмя на 0,12 над землёй и выдвинут к игроку.
        transform(this.smoke, 0, 0.0f, 7.15f, 0.6f, 0.0f, 8.4f, 12.5f, 1.0f);
        transform(this.clouds, 0, 0.0f, 7.15f, -0.6f, 0.0f, 8.4f, 12.5f, 1.0f);
        transform(this.floor, 0, 0.0f, 2.12f, -0.9f, (float) (-Math.PI / 2), 8.0f, 8.0f, 1.0f);
        this.apply(tl, 0L, 0);
    }

    boolean valid() {
        return !this.entities.isEmpty() && this.entities.stream().allMatch(Entity::isValid);
    }

    /**
     * Кадр анимации. Рама и кольцо двигаются только пока врата раскрываются (и ещё 4 тика — дойти интерполяции) и
     * при закрытии; ключ берётся на {@code interpolation} тиков вперёд — дисплей приходит туда к нужному тику.
     */
    void apply(final GateTimeline tl, final long tick, final int interpolation) {
        if (tick < GateTimeline.OPENED + 4 || tick >= tl.closeStart()) {
            final long key = tick + interpolation;
            final float rise = (float) (-10.0 * (1.0 - tl.reveal(key)));
            transform(this.frame, interpolation, 0.0f, 7.0f + rise, 0.0f, 0.0f, 10.0f, 10.0f, 10.0f);
            final double growth = tl.aperture(key);
            final GateAperture pose = GateAperture.at(growth);
            transform(this.surface, interpolation, 0.0f, (float) pose.centerY() - ANCHOR_Y, 0.0f, 0.0f,
                (float) pose.width(), (float) pose.height(), 1.0f);
        }
        this.marker(this.smoke, "gate_smoke", Markers.GATE_SMOKE, 0, tl.reveal(tick));
        this.marker(this.clouds, "gate_smoke", Markers.GATE_CLOUDS, 1, tl.reveal(tick));
        // Пол светится от раскрытия кольца, но уже при подъёме рамы — до 65 %.
        this.marker(this.floor, "gate_floor", Markers.GATE_FLOOR, 2, Math.max(tl.aperture(tick), tl.reveal(tick) * 0.65));
    }

    /** Сменить параметр маркера (синий канал), если его байт изменился. */
    private void marker(final ItemDisplay d, final String model, final int marker, final int index, final double v) {
        final int b = Markers.paramByte(v);
        if (b != this.bytes[index]) {
            d.setItemStack(Markers.item(model, Markers.paramRaw(marker, b)));
            this.bytes[index] = b;
        }
    }

    void remove() {
        for (final Entity e : this.entities) {
            e.remove();
        }
        this.entities.clear();
    }

    /** Дисплей с моделью {@code coreportals:<model>}; {@code marker} < 0 — без маркера (обычная модель). */
    private ItemDisplay item(final World world, final Location at, final Settings s, final String instance,
                             final String model, final int marker) {
        final ItemDisplay d = world.spawn(at, ItemDisplay.class, e -> {
            // Рама — без маркерных цветов (пустой список tints), остальное — маркер с параметром 1.
            e.setItemStack(marker < 0 ? Markers.item(model, new int[0])
                : Markers.item(model, Markers.param(marker, 1.0)));
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            e.setBrightness(new Display.Brightness(15, 15));
            e.setBillboard(Display.Billboard.FIXED);
            e.setPersistent(false);
            e.setShadowRadius(0.0f);
            e.setShadowStrength(0.0f);
            e.setViewRange(s.viewRange());
            e.setDisplayWidth(CULL_WIDTH);
            e.setDisplayHeight(CULL_HEIGHT);
            e.getPersistentDataContainer().set(PortalVisuals.PART, PersistentDataType.STRING, instance);
        });
        this.entities.add(d);
        return d;
    }

    private static void transform(final Display d, final int duration, final float x, final float y, final float z,
                                  final float rx, final float sx, final float sy, final float sz) {
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(duration);
        d.setTransformation(new Transformation(new Vector3f(x, y, z), new Quaternionf().rotateX(rx),
            new Vector3f(sx, sy, sz), new Quaternionf()));
    }
}
