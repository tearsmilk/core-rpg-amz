package core.amazake.systems.coremechanics.doll;

import core.amazake.systems.coremechanics.physics.RagdollBody;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.World;
import org.bukkit.entity.Display;

/**
 * Кукла: рэгдолл (физика Jolt) или манекен статуи (позу задаёт код). Состояние разложено по потокам:
 * <ul>
 *   <li>{@link #pose}, якорь, флаги — volatile/atomic: пишут и читают разные потоки;</li>
 *   <li>{@link #slots} и всё про сущности — только поток региона якоря (DollRenderer);</li>
 *   <li>{@link #body}, {@link #life}, {@link #held} — только поток физики;</li>
 *   <li>{@link #grabs} — потоки игроков (взять/бросить) и поток физики (тянуть).</li>
 * </ul>
 */
public final class Doll {

    /** Дисплей куска + где он стоит (мир и якорь на момент спавна/телепорта — чтобы убрать его в нужном регионе). */
    static final class Slot {
        final Piece piece;
        Display entity;
        volatile double ex, ez;

        Slot(final Piece piece) {
            this.piece = piece;
        }
    }

    /** Кто держит куклу: часть, дистанция, последний взгляд (x, y, z глаз и направление) и тик его снятия. */
    public static final class Grab {
        public final int part;
        public final double dist;
        public volatile double[] eye;
        public volatile long eyeAt;

        public Grab(final int part, final double dist) {
            this.part = part;
            this.dist = dist;
        }
    }

    public final UUID id = UUID.randomUUID();
    public final boolean physical;
    public final World world;
    public final String name;
    public final UUID owner;
    final Piece headPiece;

    public volatile Pose pose;
    /** Якорь — точка, где стоят все дисплеи куклы (поза — трансформацией относительно неё). */
    volatile double ax, ay, az;
    /** Желаемое тело (блоки/кубики): DollRenderer пересоздаст куски, если сменилось. */
    volatile List<Piece> body;
    volatile boolean cubed;
    /** Подсветить часть (-1 — нет). */
    public volatile int glowPart = -1;
    /** Подсветка, которую хотят включить/выключить. */
    final AtomicBoolean renderQueued = new AtomicBoolean();
    public final AtomicBoolean removed = new AtomicBoolean();
    volatile long lastRenderNanos = System.nanoTime();
    volatile boolean moving;

    // ---- поток региона якоря
    final List<Slot> slots = new ArrayList<>();
    boolean spawned;
    boolean bodyShown;   // куски тела соответствуют this.body
    List<Piece> shownBody;
    boolean snap;
    int glowShown = -1;

    // ---- поток физики
    public RagdollBody body3d;
    public int life;
    public long seq;

    public final Map<UUID, Grab> grabs = new ConcurrentHashMap<>();

    public Doll(final boolean physical, final World world, final String name, final UUID owner, final Piece head,
                final List<Piece> body, final Pose pose, final double ax, final double ay, final double az) {
        this.physical = physical;
        this.world = world;
        this.name = name;
        this.owner = owner;
        this.headPiece = head;
        this.body = body;
        this.pose = pose;
        this.ax = ax;
        this.ay = ay;
        this.az = az;
    }

    /** Сменить тело (кубики скина готовы) — перерисуется в потоке региона. */
    public void setBody(final List<Piece> pieces, final boolean cubed) {
        this.body = pieces;
        this.cubed = cubed;
    }

    public boolean cubed() {
        return this.cubed;
    }

    /** Все куски в текущем виде (голова + тело) — для /summon. */
    public List<Piece> pieces() {
        final List<Piece> out = new ArrayList<>();
        out.add(this.headPiece);
        out.addAll(this.body);
        return out;
    }

    public double anchorX() {
        return this.ax;
    }

    public double anchorZ() {
        return this.az;
    }

    /** Секунд с последней отрисовки (регион не тикает/чанк выгружен — кукла «потерялась»). */
    public double sinceRenderSeconds() {
        return (System.nanoTime() - this.lastRenderNanos) / 1e9;
    }
}
