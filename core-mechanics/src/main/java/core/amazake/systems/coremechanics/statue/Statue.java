package core.amazake.systems.coremechanics.statue;

import core.amazake.systems.coremechanics.doll.Doll;
import core.amazake.systems.coremechanics.doll.Pose;
import core.amazake.systems.coremechanics.doll.PoseMath;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Кукла-статуя. Описание (поза, анимация, кадры, место) — volatile-поля, массивы позы заменяются целиком (копия при
 * записи), поэтому команды и кукловод меняют их из потока игрока. Всё, что трогает сущности (манекен, обмякание),
 * — в тике статуи в потоке её региона; команды, которым это нужно, кладут действие в {@link #ops}.
 */
final class Statue {

    final UUID id;
    volatile String name;
    volatile UUID owner;
    volatile String tex, sig;
    volatile String worldName;
    volatile double x, y, z;
    volatile float yaw;
    volatile float[] pose = DollPoses.pose("stand");
    volatile String anim;
    final List<float[]> frames = new CopyOnWriteArrayList<>();
    volatile int frameTicks = 10;
    volatile boolean playing;

    // ---- поток региона статуи (тик)
    volatile Doll puppet;
    volatile Doll doll;
    int dollTimer;
    float[] cur;
    int t;
    double walk;
    int walkDir = 1;
    float lastYaw = Float.NaN;
    /** Поза, которую сейчас показывает манекен (для выбора лучом из потоков игроков). */
    volatile Pose shown;
    volatile boolean removed;
    final ConcurrentLinkedQueue<Consumer<Statue>> ops = new ConcurrentLinkedQueue<>();
    final AtomicBoolean queued = new AtomicBoolean();

    Statue(final UUID id) {
        this.id = id;
    }

    World world() {
        return Bukkit.getWorld(this.worldName);
    }

    int cx() {
        return (int) Math.floor(this.x) >> 4;
    }

    int cz() {
        return (int) Math.floor(this.z) >> 4;
    }

    /** Поставить анимацию/позу с нуля. */
    void setPose(final float[] p) {
        this.pose = p;
        this.anim = null;
        this.playing = false;
    }

    // ---------------------------------------------------------------- сохранение

    void save(final ConfigurationSection c) {
        c.set("name", this.name);
        c.set("owner", this.owner.toString());
        c.set("tex", this.tex);
        c.set("sig", this.sig);
        c.set("world", this.worldName);
        c.set("x", this.x);
        c.set("y", this.y);
        c.set("z", this.z);
        c.set("yaw", (double) this.yaw);
        c.set("pose", toList(this.pose));
        c.set("anim", this.anim);
        final List<List<Float>> fr = new ArrayList<>();
        for (final float[] f : this.frames) {
            fr.add(toList(f));
        }
        c.set("frames", fr);
        c.set("frameTicks", this.frameTicks);
        c.set("playing", this.playing);
    }

    static Statue load(final String key, final ConfigurationSection c) {
        final Statue s = new Statue(UUID.fromString(key));
        s.name = c.getString("name", "?");
        s.owner = UUID.fromString(c.getString("owner", UUID.randomUUID().toString()));
        s.tex = c.getString("tex");
        s.sig = c.getString("sig");
        s.worldName = c.getString("world", "world");
        s.x = c.getDouble("x");
        s.y = c.getDouble("y");
        s.z = c.getDouble("z");
        s.yaw = (float) c.getDouble("yaw");
        final float[] ps = fromList(c.getList("pose"));
        if (ps != null) {
            s.pose = ps;
        }
        s.anim = c.getString("anim");
        if (s.anim != null && !DollPoses.ANIMS.containsKey(s.anim)) {
            s.anim = null;
        }
        for (final Object o : c.getList("frames", List.of())) {
            final float[] f = o instanceof List<?> l ? fromList(l) : null;
            if (f != null) {
                s.frames.add(f);
            }
        }
        s.frameTicks = Math.max(1, Math.min(200, c.getInt("frameTicks", 10)));
        s.playing = c.getBoolean("playing");
        return s;
    }

    static List<Float> toList(final float[] v) {
        final List<Float> l = new ArrayList<>();
        for (final float f : v) {
            l.add(f);
        }
        return l;
    }

    static float[] fromList(final List<?> l) {
        if (l == null || l.size() != PoseMath.POSE_LEN) {
            return null;
        }
        final float[] v = new float[l.size()];
        for (int i = 0; i < v.length; i++) {
            if (!(l.get(i) instanceof Number n)) {
                return null;
            }
            v[i] = n.floatValue();
        }
        return v;
    }
}
