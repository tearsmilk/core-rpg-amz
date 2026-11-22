package ink.hoka.odyssey.client.spaceflight;

import com.mojang.blaze3d.vertex.PoseStack;
import ink.hoka.odyssey.client.api.ClientContext;
import ink.hoka.odyssey.spacephys.Quat;
import ink.hoka.odyssey.spacephys.Vec3d;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.joml.Quaternionf;

import java.io.DataInputStream;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Повёрнутые тела игроков (свои и чужие). Ориентации других игроков присылает сервер (op 3 — кватернион взгляда и
 * флаги, op 4 — «больше не действует»), между пакетами (~10 в секунду) поворот плавно доводится slerp.
 * <p>
 * Рисовать тело повёрнутым в моде 0.17 нечем: нужен крючок {@code onAvatarRotations} (см.
 * docs/mod-0.17-additions). Он ищется рефлексией — появится в моде, и поворот заработает сам. Пока его нет,
 * лежащих игроков показывает сервер запасной позой (поза полёта на элитрах).
 * <p>
 * Тело стоя — верх вдоль верха камеры, лицом по взгляду; лёжа («супермен») — макушка по взгляду, грудь к низу
 * камеры. Голова при этом не поворачивается отдельно от тела. Поворот — вокруг середины тела.
 */
final class BodyPoses {
    static final int OP_ORIENT = 3;
    static final int OP_ORIENT_STOP = 4;
    static final int OP_CAPS = 5;
    static final int FLAG_PRONE = 1;
    static final int FLAG_ACTIVE = 2;
    static final int CAP_AVATAR_ROTATION = 1;
    /** За сколько доводить поворот до присланного (ожидаемый промежуток между пакетами), нс. */
    private static final long BLEND_NS = 110_000_000L;
    /** Нет пакетов дольше — забыть игрока (сервер повторяет раз в секунду). */
    private static final long STALE_NS = 3_000_000_000L;

    /** Лёжа: тело повёрнуто на +90° вокруг поперечной оси (макушка — вперёд по взгляду). */
    private static final Quat PRONE = Quat.axisAngle(new Vec3d(1, 0, 0), Math.PI / 2);
    /** Модель игрока в PoseStack смотрит вдоль −Z, а взгляд FreeLook — вдоль +Z: поворот на 180° вокруг Y. */
    private static final Quat MODEL = Quat.axisAngle(Quat.UP, Math.PI);

    /** Чужое тело: от какого поворота к какому идём и когда начали. */
    private record Track(Quat from, Quat to, float proneFrom, float proneTo, long t0) {
        Quat look(long now) { return FreeLook.slerp(from, to, t(now)); }

        float prone(long now) { return (float) (proneFrom + (proneTo - proneFrom) * t(now)); }

        double t(long now) { return FreeLook.clamp((now - t0) / (double) BLEND_NS, 0, 1); }
    }

    /** Свой игрок (вид от третьего лица): поворот взгляда прямо сейчас и доля «лёжа». */
    interface Self {
        /** null — свой игрок сейчас не в невесомости (рисовать как обычно). */
        Quat look();

        float prone();
    }

    private final ClientContext ctx;
    private final Self self;
    private final Map<Integer, Track> tracks = new ConcurrentHashMap<>();
    private volatile boolean hooked;
    private volatile boolean enabled = true;

    BodyPoses(ClientContext ctx, Self self) {
        this.ctx = ctx;
        this.self = self;
    }

    /** Есть ли в моде крючок поворота тел (тогда сервер не шлёт этому клиенту запасную позу). */
    boolean hooked() { return hooked; }

    /**
     * Повесить крючок onAvatarRotations, если он есть в моде. Сигнатура (как в старом патче ядра):
     * {@code void onAvatarRotations(AvatarRotHook)}, {@code boolean apply(int entityId, AvatarRenderState s,
     * PoseStack pose, float bodyRot, float scale)}; true — ванильный поворот не делать.
     */
    void install() {
        Method reg = null;
        for (Method m : ClientContext.class.getMethods()) {
            if (m.getName().equals("onAvatarRotations") && m.getParameterCount() == 1
                    && m.getParameterTypes()[0].isInterface()) {
                reg = m;
                break;
            }
        }
        if (reg == null) {
            ctx.logger().info("spaceflight: в моде нет крючка onAvatarRotations — повёрнутые тела других игроков "
                    + "не рисуются, лежащих показывает сервер (поза полёта)");
            return;
        }
        Class<?> hook = reg.getParameterTypes()[0];
        InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
                case "equals" -> { return args != null && args.length == 1 && proxy == args[0]; }
                case "hashCode" -> { return System.identityHashCode(proxy); }
                case "toString" -> { return "spaceflight:onAvatarRotations"; }
                default -> { }
            }
            boolean r = false;
            if (args != null && args.length == 5 && args[0] instanceof Integer id
                    && args[1] instanceof AvatarRenderState s && args[2] instanceof PoseStack pose) {
                r = apply(id, s, pose);
            }
            return method.getReturnType() == boolean.class ? (Object) r : null;
        };
        try {
            Object proxy = Proxy.newProxyInstance(hook.getClassLoader(), new Class<?>[] {hook}, h);
            reg.invoke(ctx, proxy);
            hooked = true;
            ctx.logger().info("spaceflight: крючок onAvatarRotations найден — тела игроков в невесомости поворачиваются");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            ctx.logger().warn("spaceflight: крючок onAvatarRotations не подключился: {}", e.toString());
        }
    }

    /** Модуль выгружается: крючок снимет ядро, а до тех пор он ничего не делает. */
    void disable() {
        enabled = false;
        tracks.clear();
    }

    void clear() { tracks.clear(); }

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
            tracks.remove(id);
            return;
        }
        Quat q = new Quat(w / n, x / n, y / n, z / n);
        float prone = (flags & FLAG_PRONE) != 0 ? 1 : 0;
        long now = System.nanoTime();
        Track old = tracks.get(id);
        Track t = old == null || now - old.t0() > STALE_NS
                ? new Track(q, q, prone, prone, now)
                : new Track(old.look(now), q, old.prone(now), prone, now);
        tracks.put(id, t);
    }

    /** S2C op 4: u8 op (уже прочитан), i32 entityId. */
    void readStop(DataInputStream in, int available) throws IOException {
        if (available < 4) throw new IOException("короткий op 4");
        tracks.remove(in.readInt());
    }

    /** Раз в секунду: забыть тех, о ком сервер давно молчит (ушёл из виду). */
    void prune() {
        long now = System.nanoTime();
        tracks.values().removeIf(t -> now - t.t0() > STALE_NS);
    }

    /** Тело по взгляду и доле «лёжа» (0 — стоя, 1 — лёжа), соглашение FreeLook. */
    static Quat body(Quat look, float prone) {
        if (prone <= 0) return look;
        Quat lying = look.mul(PRONE);
        return prone >= 1 ? lying : FreeLook.slerp(look, lying, prone);
    }

    /** Крючок рисования: true — повернули сами, ванильный поворот не нужен. */
    private boolean apply(int id, AvatarRenderState s, PoseStack pose) {
        if (!enabled) return false;
        Quat look;
        float prone;
        var me = net.minecraft.client.Minecraft.getInstance().player;
        if (me != null && me.getId() == id) {
            look = self.look();
            prone = self.prone();
        } else {
            Track t = tracks.get(id);
            if (t == null) return false;
            long now = System.nanoTime();
            look = t.look(now);
            prone = t.prone(now);
        }
        if (look == null) return false;
        Quat r = body(look, prone).mul(MODEL);
        float half = s.boundingBoxHeight * 0.5f;
        pose.translate(0, half, 0);
        pose.mulPose(new Quaternionf((float) r.x(), (float) r.y(), (float) r.z(), (float) r.w()));
        pose.translate(0, -half, 0);
        // голова — вместе с телом: стоя прямо, лёжа запрокинута вперёд по взгляду
        s.yRot = 0;
        s.xRot = -90f * prone;
        return true;
    }
}
