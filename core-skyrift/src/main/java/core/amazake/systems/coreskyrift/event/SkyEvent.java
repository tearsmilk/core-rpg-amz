package core.amazake.systems.coreskyrift.event;

import core.amazake.systems.coreskyrift.Settings;
import core.amazake.systems.coreskyrift.anim.Timeline;
import core.amazake.systems.coreskyrift.geo.RiftBand;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.joml.Quaternionf;

/**
 * Одно событие разлома. Центр и форма неизменны; часы ({@link #tick}) и {@link #timeline} меняет только
 * глобальный поток, остальные потоки их только читают (volatile).
 */
public final class SkyEvent {

    public final UUID id = UUID.randomUUID();
    /** Короткий номер для журнала и /skyrift list. */
    public final String shortId = this.id.toString().substring(0, 8);
    public final World world;
    public final double x;
    public final double y;
    public final double z;
    /** Высота плоскости разлома (мировая Y). */
    public final double anchorY;
    public final double yawDeg;
    public final double size;
    public final RiftBand band;
    /** Левый поворот дисплея неба (ось и размер для шейдера). */
    public final Quaternionf rotation;
    public final String starter;
    /** Настройки на момент запуска (стадии, размер, радиус не меняются посреди события). */
    public final Settings settings;

    private volatile long tick = -1;
    private volatile Timeline timeline;
    private volatile boolean finished;

    /** Живые мобы события (заполняет слушатель спавна в потоке региона моба). */
    final Map<UUID, Entity> mobs = new ConcurrentHashMap<>();
    final AtomicInteger spawnedTotal = new AtomicInteger();
    final AtomicInteger pending = new AtomicInteger();
    /** Только глобальный поток. */
    long nextWave;
    long finishedAt = -1;

    SkyEvent(final World world, final double x, final double y, final double z, final double yawDeg,
             final String starter, final Settings s) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.anchorY = y + s.rift().height();
        this.yawDeg = yawDeg;
        this.size = RiftBand.quantizedSize(s.rift().size());
        this.band = RiftBand.of(s.rift().size(), yawDeg);
        this.rotation = RiftBand.rotation(yawDeg, s.rift().size());
        this.starter = starter;
        this.settings = s;
        this.timeline = Timeline.of(s.times());
    }

    public long tick() {
        return this.tick;
    }

    public Timeline timeline() {
        return this.timeline;
    }

    public boolean finished() {
        return this.finished;
    }

    /** Глобальный поток. */
    long advance() {
        final long t = this.tick + 1;
        this.tick = t;
        return t;
    }

    /** Закрыть досрочно (из текущего вида). Глобальный поток. Возвращает false, если уже закрывается. */
    boolean requestClose() {
        final Timeline tl = this.timeline;
        final long now = Math.max(0, this.tick);
        if (tl.closing(now)) {
            return false;
        }
        this.timeline = tl.closeAt(now);
        return true;
    }

    void finish() {
        this.finished = true;
    }

    public int alive() {
        return this.mobs.size();
    }

    /** Расстояние по горизонтали от центра. */
    public double horizontalDistance(final double px, final double pz) {
        return Math.hypot(px - this.x, pz - this.z);
    }
}
