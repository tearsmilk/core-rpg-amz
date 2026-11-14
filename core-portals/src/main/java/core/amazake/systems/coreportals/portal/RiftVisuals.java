package core.amazake.systems.coreportals.portal;

import core.amazake.systems.coreportals.Settings;
import core.amazake.systems.coreportals.anim.RiftTimeline;
import core.amazake.systems.coreportals.geo.RiftPlacement;
import core.amazake.systems.coreportals.geo.Spot;
import java.util.ArrayList;
import java.util.List;
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
 * Сущности лазуритового разлома: два плоских item-дисплея в центре разлома — сам разлом (маркер 80) и дымка чуть
 * позади (81). Размер квадратов не меняется: трещину, разрыв и вихрь рисует шейдер по байту {@code tear} в синем
 * канале маркера, сервер меняет его каждый тик анимации (только когда байт изменился). Не сохраняются в чанк.
 * Поток региона разлома.
 */
final class RiftVisuals {

    /** Квадрат разлома больше овала: по краям — место для свечения (шейдер: CP_RF_MX, CP_RF_MY). */
    static final float RIFT_MX = 1.3f;
    static final float RIFT_MY = 1.2f;
    /** Квадрат дымки (шейдер: CP_HZ_MX, CP_HZ_MY), чуть позади разлома. */
    static final float HAZE_MX = 1.75f;
    static final float HAZE_MY = 1.45f;
    static final float HAZE_BACK = 0.15f;

    private final List<Entity> entities = new ArrayList<>();
    private ItemDisplay rift;
    private ItemDisplay haze;
    private int tearByte = -1;
    private int hazeByte = -1;

    void spawn(final World world, final Spot spot, final RiftPlacement.Size size, final Settings s,
               final RiftTimeline tl, final String instance) {
        final Location at = new Location(world, spot.x(), spot.y() + size.centerUp(), spot.z(), spot.facing().yaw(), 0f);
        final float w = (float) size.width();
        final float h = (float) size.height();
        this.haze = this.item(world, at, s, instance, hazeItem(0),
            new Vector3f(0, 0, HAZE_BACK), new Vector3f(w * HAZE_MX, h * HAZE_MY, 1f));
        this.hazeByte = 0;
        this.rift = this.item(world, at, s, instance, riftItem(0),
            new Vector3f(0, 0, 0), new Vector3f(w * RIFT_MX, h * RIFT_MY, 1f));
        this.tearByte = 0;
        this.apply(tl, 0);
    }

    boolean valid() {
        for (final Entity e : this.entities) {
            if (!e.isValid()) {
                return false;
            }
        }
        return !this.entities.isEmpty();
    }

    /** Вид на тике {@code t}: байты маркеров (предмет меняется, только когда байт изменился). */
    void apply(final RiftTimeline tl, final long t) {
        final int tb = Markers.paramByte(tl.tear(t));
        if (tb != this.tearByte && this.rift != null) {
            this.tearByte = tb;
            this.rift.setItemStack(riftItem(tb));
        }
        final int hb = Markers.paramByte(tl.openness(t));
        if (hb != this.hazeByte && this.haze != null) {
            this.hazeByte = hb;
            this.haze.setItemStack(hazeItem(hb));
        }
    }

    void remove() {
        for (final Entity e : this.entities) {
            e.remove();
        }
        this.entities.clear();
    }

    static ItemStack riftItem(final int tearByte) {
        return Markers.item("portal_rift", Markers.paramRaw(Markers.RIFT, tearByte));
    }

    static ItemStack hazeItem(final int b) {
        return Markers.item("portal_haze", Markers.paramRaw(Markers.RIFT_HAZE, b));
    }

    private ItemDisplay item(final World world, final Location at, final Settings s, final String instance,
                             final ItemStack stack, final Vector3f translation, final Vector3f scale) {
        final ItemDisplay d = world.spawn(at, ItemDisplay.class, e -> {
            e.setItemStack(stack);
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            e.setBrightness(new Display.Brightness(15, 15));
            e.setPersistent(false);
            e.setViewRange(s.viewRange());
            e.setShadowRadius(0f);
            e.setShadowStrength(0f);
            e.setBillboard(Display.Billboard.FIXED);
            e.setInterpolationDelay(0);
            e.setInterpolationDuration(0);
            e.setTransformation(new Transformation(translation, new Quaternionf(), scale, new Quaternionf()));
            e.getPersistentDataContainer().set(PortalVisuals.PART, PersistentDataType.STRING, instance);
        });
        this.entities.add(d);
        return d;
    }
}
