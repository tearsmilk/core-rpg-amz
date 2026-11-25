package ink.hoka.odyssey.client.spaceflight;

import ink.hoka.odyssey.client.api.ClientContext;
import ink.hoka.odyssey.spacephys.Quat;
import ink.hoka.odyssey.spacephys.Vec3d;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.io.DataInputStream;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Повёрнутые тела игроков в невесомости — свой (вид от третьего лица) и чужие — через крючок ядра
 * {@code onEntityPose} (мод 0.48+). Поворот других игроков присылает сервер (op 3 — кватернион ТЕЛА без «лёжа» и
 * флаги, op 4 — «больше не действует»), между пакетами (~10 в секунду) он плавно доводится slerp, а смена режима
 * (свободно / на поверхности / лёжа) — плавным переходом {@link #MODE_BLEND_NS}.
 * <p>
 * Правило (см. {@link BodyFrame}): стоит на поверхности — ноги на ней, верх тела по её нормали, лицом по взгляду;
 * свободно в пространстве — тело целиком повёрнуто к прицелу (верх — по верху камеры), вокруг середины тела.
 * <p>
 * Сдвиг модели (ноги на стене, поворот вокруг середины) ядро 0.48 не умеет — оно крутит модель только вокруг ног.
 * Добавка в мод — {@code EntityPose.offset} (docs/mod-additions); есть ли она, ищется рефлексией один раз: есть —
 * модель ставится точно, нет — поворот вокруг ног (как умеет ядро).
 */
final class BodyPoses {
    static final int OP_ORIENT = 3;
    static final int OP_ORIENT_STOP = 4;
    static final int OP_CAPS = 5;
    static final int FLAG_PRONE = BodyFrame.FLAG_PRONE;
    static final int FLAG_ACTIVE = BodyFrame.FLAG_ACTIVE;
    static final int FLAG_BOX = BodyFrame.FLAG_BOX;
    static final int CAP_AVATAR_ROTATION = 1;
    /** За сколько доводить поворот до присланного (ожидаемый промежуток между пакетами), нс. */
    private static final long BLEND_NS = 110_000_000L;
    /** Смена режима (свободно / поверхность / лёжа) — плавно за столько, нс. */
    static final long MODE_BLEND_NS = 150_000_000L;
    /** Нет пакетов дольше — забыть игрока (сервер повторяет раз в секунду). */
    private static final long STALE_NS = 3_000_000_000L;

    /** Конструктор EntityPose с пятым полем offset (добавка в мод); null — ядро без неё. */
    private static final MethodHandle POSE_WITH_OFFSET = findPoseWithOffset();

    /** Свой игрок. */
    interface Self {
        /** Основа тела (без «лёжа») прямо сейчас; null — свой игрок не в невесомости (рисовать как обычно). */
        Quat base();

        /** Грань под ногами (0..5) или −1 — свободно. */
        int surface();

        boolean prone();
    }

    /** Чужое тело: от какого поворота к какому идём, когда начали, флаги последнего пакета. */
    private record Track(Quat from, Quat to, long t0, int flags) {
        Quat base(long now) { return FreeLook.slerp(from, to, FreeLook.clamp((now - t0) / (double) BLEND_NS, 0, 1)); }
    }

    /** Показанная поза и переход между режимами (только поток отрисовки). */
    private static final class Blend {
        int key = Integer.MIN_VALUE;
        Quat fromBody;
        Vec3d fromCentre;
        long t0;
        Quat shownBody;
        Vec3d shownCentre;
        long used;
    }

    private final ClientContext ctx;
    private final Self self;
    private final Map<Integer, Track> tracks = new ConcurrentHashMap<>();
    private final Map<Integer, Blend> blends = new ConcurrentHashMap<>();
    /** Чужие игроки, у которых сменилась рамка (повёрнута / обычная): пересобрать её в тике (setPos). */
    private final java.util.Set<Integer> boxChanged = ConcurrentHashMap.newKeySet();
    private volatile boolean hooked;
    private volatile boolean enabled = true;

    BodyPoses(ClientContext ctx, Self self) {
        this.ctx = ctx;
        this.self = self;
    }

    /** Есть ли в ядре крючок поворота тел (тогда сервер не шлёт этому клиенту запасную позу). */
    boolean hooked() { return hooked; }

    /** Ставит ли ядро модель со сдвигом (добавка EntityPose.offset). */
    static boolean offsetSupported() { return POSE_WITH_OFFSET != null; }

    /** Повесить крючок onEntityPose (в ClientContext он default-метод: в старом ядре — пустой, крючка нет). */
    void install() {
        try {
            var m = ctx.getClass().getMethod("onEntityPose", ClientContext.EntityPoseHook.class);
            if (m.getDeclaringClass().isInterface()) {
                ctx.logger().info("spaceflight: в ядре нет onEntityPose — тела других игроков не поворачиваются, "
                        + "лежащих показывает сервер (поза полёта)");
                return;
            }
            ctx.onEntityPose(this::pose);
            hooked = true;
            ctx.logger().info("spaceflight: тела игроков в невесомости поворачиваются (onEntityPose){}",
                    offsetSupported() ? ", со сдвигом модели (EntityPose.offset)"
                            : "; ядро без EntityPose.offset — поворот вокруг ног (docs/mod-additions)");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            ctx.logger().warn("spaceflight: крючок onEntityPose не подключился: {}", e.toString());
        }
    }

    /** Модуль выгружается: крючок снимет ядро, а до тех пор он ничего не делает. */
    void disable() {
        enabled = false;
        tracks.clear();
        blends.clear();
    }

    void clear() {
        tracks.clear();
        blends.clear();
        boxChanged.clear();
    }

    /** Грань повёрнутой рамки чужого игрока (по флагам op 3) или −1 — рамка обычная. */
    int boxSurface(int entityId) {
        Track t = tracks.get(entityId);
        return t == null ? -1 : BodyFrame.boxOf(t.flags());
    }

    /** Грань под ногами чужого игрока (по флагам op 3) или −1. */
    int surface(int entityId) {
        Track t = tracks.get(entityId);
        return t == null ? -1 : surfaceOf(t.flags());
    }

    /** Отдать (и забыть) тех, чью рамку пора пересобрать. */
    void drainBoxChanges(java.util.function.IntConsumer action) {
        for (var it = boxChanged.iterator(); it.hasNext(); ) {
            int id = it.next();
            it.remove();
            action.accept(id);
        }
    }

    /** Все, у кого рамка сейчас повёрнута (выгрузка модуля — вернуть им обычную). */
    java.util.List<Integer> boxed() {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        tracks.forEach((id, t) -> { if (BodyFrame.boxOf(t.flags()) >= 0) out.add(id); });
        return out;
    }

    private void forget(int id) {
        Track old = tracks.remove(id);
        if (old != null && BodyFrame.boxOf(old.flags()) >= 0) boxChanged.add(id);
    }

    static int flags(boolean active, boolean prone, int surface) { return BodyFrame.flags(active, prone, surface); }

    static int surfaceOf(int flags) { return BodyFrame.surfaceOf(flags); }

    // ---------------------------------------------------------------- сеть

    /** S2C op 3: u8 op (уже прочитан), i32 entityId, f32 w, x, y, z, u8 flags. */
    void readOrientation(DataInputStream in, int available) throws IOException {
        if (available < 4 + 16 + 1) throw new IOException("короткий поворот тела");
        int id = in.readInt();
        double w = in.readFloat(), x = in.readFloat(), y = in.readFloat(), z = in.readFloat();
        int flags = in.readUnsignedByte();
        if (!hooked) return;                                   // рисовать нечем — и хранить незачем
        double n = Math.sqrt(w * w + x * x + y * y + z * z);
        if (!(Double.isFinite(n) && n > 0.5 && n < 2)) return;
        if ((flags & FLAG_ACTIVE) == 0) {
            forget(id);
            return;
        }
        Quat q = new Quat(w / n, x / n, y / n, z / n);
        long now = System.nanoTime();
        Track old = tracks.get(id);
        Track t = old == null || now - old.t0() > STALE_NS || surfaceOf(old.flags()) != surfaceOf(flags)
                ? new Track(q, q, now, flags)                  // новый режим — переход сделает Blend
                : new Track(old.base(now), q, now, flags);
        tracks.put(id, t);
        if (BodyFrame.boxOf(flags) != (old == null ? -1 : BodyFrame.boxOf(old.flags()))) boxChanged.add(id);
    }

    /** S2C op 4: u8 op (уже прочитан), i32 entityId. */
    void readStop(DataInputStream in, int available) throws IOException {
        if (available < 4) throw new IOException("короткий op 4");
        forget(in.readInt());
    }

    /** Раз в секунду: забыть тех, о ком сервер давно молчит (ушёл из виду), и давно не рисованные позы. */
    void prune() {
        long now = System.nanoTime();
        for (var e : tracks.entrySet()) {
            if (now - e.getValue().t0() > STALE_NS) forget(e.getKey());
        }
        blends.values().removeIf(b -> now - b.used > STALE_NS);
    }

    // ---------------------------------------------------------------- отрисовка

    private ClientContext.EntityPose pose(Entity e, float partialTick) {
        if (!enabled || !(e instanceof Player)) return null;
        int id = e.getId();
        Quat base;
        int surface;
        boolean prone;
        long now = System.nanoTime();
        Minecraft mc = Minecraft.getInstance();
        if (e == mc.player) {
            base = self.base();
            surface = self.surface();
            prone = self.prone();
        } else {
            Track t = tracks.get(id);
            base = t == null ? null : t.base(now);
            int f = t == null ? 0 : t.flags();
            surface = surfaceOf(f);
            prone = (f & FLAG_PRONE) != 0;
        }
        if (base == null) {
            blends.remove(id);
            return null;
        }
        double h = e.getBbHeight();
        Quat body = BodyFrame.body(base, prone ? 1 : 0);
        // середина модели — от настоящей рамки (на стене/потолке она повёрнута — SurfaceBox): ноги на поверхности
        AABB bb = e.getBoundingBox();
        Vec3 ep = e.position();
        Vec3d mid = new Vec3d((bb.minX + bb.maxX) / 2 - ep.x, (bb.minY + bb.maxY) / 2 - ep.y, (bb.minZ + bb.maxZ) / 2 - ep.z);
        Vec3d n = BodyFrame.normal(surface);
        double halfN = n == null ? 0
                : (Math.abs(n.x()) * bb.getXsize() + Math.abs(n.y()) * bb.getYsize() + Math.abs(n.z()) * bb.getZsize()) / 2;
        Vec3d centre = BodyFrame.centre(surface, prone, mid, halfN, h);
        Blend b = blends.computeIfAbsent(id, k -> new Blend());
        int key = (surface + 1) * 2 + (prone ? 1 : 0);
        if (b.key == Integer.MIN_VALUE) {                      // только что начали — от обычной позы стоя
            float yaw = e instanceof LivingEntity le ? le.yBodyRot : e.getYRot();
            b.fromBody = Quat.fromYawPitchRoll(yaw, 0, 0);
            b.fromCentre = new Vec3d(0, h / 2, 0);
            b.t0 = now;
            b.key = key;
        } else if (key != b.key) {
            b.fromBody = b.shownBody;
            b.fromCentre = b.shownCentre;
            b.t0 = now;
            b.key = key;
        }
        double s = FreeLook.clamp((now - b.t0) / (double) MODE_BLEND_NS, 0, 1);
        if (s < 1) {
            s = s * s * (3 - 2 * s);                           // мягкий старт и остановка
            body = FreeLook.slerp(b.fromBody, body, s);
            centre = BodyFrame.lerp(b.fromCentre, centre, s);
        }
        b.shownBody = body;
        b.shownCentre = centre;
        b.used = now;

        Vec3 v = e.getViewVector(partialTick);
        float[] head = BodyFrame.head(body, new Vec3d(v.x, v.y, v.z));
        Quaternionf tilt = new Quaternionf((float) body.x(), (float) body.y(), (float) body.z(), (float) body.w());
        Vec3d off = BodyFrame.offset(body, centre, h);
        return make(tilt, head[0], head[1], new Vector3f((float) off.x(), (float) off.y(), (float) off.z()));
    }

    /** EntityPose: bodyYaw = 0 (поворот тела целиком в tilt), голова — в системе тела; offset — если ядро умеет. */
    private static ClientContext.EntityPose make(Quaternionf tilt, float headYaw, float headPitch, Vector3fc offset) {
        MethodHandle h = POSE_WITH_OFFSET;
        if (h != null) {
            try {
                return (ClientContext.EntityPose) h.invoke((Quaternionfc) tilt, 0f, headYaw, headPitch, offset);
            } catch (Throwable ignored) {
                // не вышло — без сдвига
            }
        }
        return new ClientContext.EntityPose(tilt, 0f, headYaw, headPitch);
    }

    private static MethodHandle findPoseWithOffset() {
        try {
            var c = ClientContext.EntityPose.class.getConstructor(Quaternionfc.class, float.class, float.class,
                    float.class, Vector3fc.class);
            return MethodHandles.publicLookup().unreflectConstructor(c);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }
}
