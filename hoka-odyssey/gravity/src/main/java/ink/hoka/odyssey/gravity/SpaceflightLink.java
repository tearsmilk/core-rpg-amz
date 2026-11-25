package ink.hoka.odyssey.gravity;

import ink.hoka.odyssey.spacephys.Body;
import ink.hoka.odyssey.spacephys.Quat;
import ink.hoka.odyssey.spacephys.Vec3d;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minestom.server.entity.Player;
import org.slf4j.Logger;

/**
 * Связь с клиентским модулем spaceflight через модуль clientmods (softdepends). Классов clientmods при сборке нет,
 * а на сервере его может не быть совсем, поэтому только рефлексия: интерфейс ClientModsApi ищем загрузчиком
 * gravity (softdepends даёт доступ к классам clientmods), сервис — через services().
 * <p>
 * Протокол (сервер → клиент, канал модуля «spaceflight», big-endian, как DataOutputStream):
 * <ul>
 *   <li>op 1 — тела карты: u8 op, i32 count, затем на тело f64 x, f64 y, f64 z, f64 mu, f64 radius, f64 softening.
 *       radius &gt; 0 — шар (снаружи μ/r², внутри однородный шар), radius = 0 — Пламмер со смягчением softening;</li>
 *   <li>op 2 — физика игрока вкл/выкл: u8 op, u8 on (1 — клиент считает вакуум сам, 0 — не считает); при on = 1
 *       дальше пределы сервера: f64 thrust (ускорение ранца, б/с²), f64 maxSpeed (предел скорости, б/с; 0 — нет).
 *       Клиент берёт меньшее из своих настроек и этих; старый клиент лишние байты не читает;</li>
 *   <li>op 3 — поворот тела другого игрока: u8 op, i32 entityId, f32 w, x, y, z (кватернион тела без «лёжа» в
 *       соглашении FreeLook/spacephys: свободно — это взгляд, на поверхности — верх по её нормали, лицом по взгляду),
 *       u8 flags (бит 0 — лёжа, бит 1 — поворот действует, биты 2–4 — грань под ногами + 1: 1 +X, 2 −X, 3 +Y,
 *       4 −Y, 5 +Z, 6 −Z; 0 — свободно в пространстве; бит 5 — рамка игрока повёрнута по этой грани: стоит на
 *       стене или потолке, рамка 1.8 вдоль нормали грани и 0.6 поперёк, позиция — середина её нижней стороны, как
 *       у любой рамки; см. {@link PlayerBox});</li>
 *   <li>op 4 — поворот тела другого игрока больше не действует: u8 op, i32 entityId (вышел из вакуума, ушёл).</li>
 * </ul>
 * Клиент → сервер:
 * <ul>
 *   <li>op 3 — свой поворот: u8 op, f32 w, x, y, z, u8 flags (как в S2C op 3, без entityId); ~10 раз в секунду
 *       и только при изменении;</li>
 *   <li>op 5 — возможности клиента: u8 op, u8 caps (бит 0 — умеет сам поворачивать тела игроков, тогда
 *       запасную позу «полёта» ему не шлём);</li>
 *   <li>op 6 — «модуль (пере)загружен»: u8 op. Сервер заново шлёт тела и «физика вкл.» (после /hoclient reload
 *       новый экземпляр модуля ничего не знает, а сервер мог не заметить перезагрузки).</li>
 * </ul>
 * Скорость и позицию клиента — только по обычным пакетам движения.
 */
final class SpaceflightLink {
    static final String MODULE = "spaceflight";
    static final int OP_BODIES = 1;
    static final int OP_PHYSICS = 2;
    static final int OP_ORIENT = 3;
    static final int OP_ORIENT_STOP = 4;
    static final int OP_CAPS = 5;
    static final int OP_HELLO = 6;
    /** Флаги поворота: лёжа (тело вдоль взгляда) и «поворот действует». */
    static final int FLAG_PRONE = 1;
    static final int FLAG_ACTIVE = 2;
    /** Биты 2–4 флагов: грань под ногами + 1 (1..6), 0 — свободно в пространстве; 7 — неверно (отбрасывается). */
    static final int SURFACE_SHIFT = 2;
    static final int SURFACE_MASK = 7 << SURFACE_SHIFT;
    static final int SURFACES = 6;
    /** Бит 5 флагов: рамка повёрнута по грани под ногами (только стены и потолок — на полу она обычная). */
    static final int FLAG_BOX = 1 << 5;
    /** Возможность клиента: сам рисует повёрнутые тела игроков. */
    static final int CAP_AVATAR_ROTATION = 1;
    private static final String API = "ink.hoka.odyssey.clientmods.ClientModsApi";

    private final GravityModule mod;
    private final Logger log;
    private Object api;
    private Method isReady;
    private Method send;
    private Method onReady;
    private Method on;
    private Method off;
    private boolean warned;

    SpaceflightLink(GravityModule mod, Logger log) {
        this.mod = mod;
        this.log = log;
    }

    /**
     * Найти сервис clientmods (повторно — если он сменился после перезагрузки clientmods, подписаться заново).
     * ready получает игрока, у которого только что загрузился spaceflight (вызывать из тика — в тик и перекладываем);
     * message — сообщения клиентского модуля (поток clientmods: только быстро разобрать и запомнить).
     */
    void resolve(Consumer<Player> ready, BiConsumer<Player, byte[]> message) {
        Object found;
        Class<?> type;
        try {
            type = Class.forName(API, false, SpaceflightLink.class.getClassLoader());
            found = mod.service(type);
        } catch (ClassNotFoundException | LinkageError e) {
            api = null;
            return;                                            // clientmods нет — все игроки vanilla
        } catch (RuntimeException e) {
            warn(e);
            api = null;
            return;
        }
        if (found == null) {
            api = null;
            return;
        }
        if (found == api) {
            return;
        }
        try {
            isReady = type.getMethod("isReady", Player.class, String.class);
            send = type.getMethod("send", Player.class, String.class, byte[].class);
            onReady = type.getMethod("onReady", String.class, Consumer.class);
            on = type.getMethod("on", String.class, BiConsumer.class);
            off = type.getMethod("off", String.class);
            if (api != null) {
                off.invoke(api, MODULE);                         // старый сервис (clientmods перезагрузили)
            }
            Consumer<Player> cb = p -> mod.runInTick(() -> ready.accept(p));
            onReady.invoke(found, MODULE, cb);
            on.invoke(found, MODULE, message);
            api = found;
        } catch (ReflectiveOperationException | RuntimeException e) {
            warn(e);
            api = null;
        }
    }

    /** Загружен ли у игрока клиентский модуль spaceflight. */
    boolean ready(Player p) {
        Object a = api;
        if (a == null) {
            return false;
        }
        try {
            return (Boolean) isReady.invoke(a, p, MODULE);
        } catch (ReflectiveOperationException | RuntimeException e) {
            warn(e);
            return false;
        }
    }

    void sendBodies(Player p, List<Body> bodies) {
        send(p, bodies(bodies));
    }

    void sendPhysics(Player p, boolean on) {
        send(p, new byte[] {OP_PHYSICS, (byte) (on ? 1 : 0)});
    }

    /** «Физика вкл.» с пределами сервера (ранец, скорость). */
    void sendPhysicsOn(Player p, double thrust, double maxSpeed) {
        send(p, physicsOn(thrust, maxSpeed));
    }

    /** Пакет op 2 «вкл.» с пределами: u8 op, u8 1, f64 thrust, f64 maxSpeed. */
    static byte[] physicsOn(double thrust, double maxSpeed) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(18);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(OP_PHYSICS);
            out.writeByte(1);
            out.writeDouble(thrust);
            out.writeDouble(maxSpeed);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bytes.toByteArray();
    }

    void sendOrientation(Player viewer, int entityId, Orientation o) {
        send(viewer, orientation(entityId, o));
    }

    void sendOrientationStop(Player viewer, int entityId) {
        send(viewer, orientationStop(entityId));
    }

    /** Отписаться (onDisable). */
    void close() {
        Object a = api;
        api = null;
        if (a != null) {
            try {
                off.invoke(a, MODULE);
            } catch (ReflectiveOperationException | RuntimeException e) {
                warn(e);
            }
        }
    }

    private void send(Player p, byte[] data) {
        Object a = api;
        if (a == null) {
            return;
        }
        try {
            send.invoke(a, p, MODULE, data);
        } catch (ReflectiveOperationException | RuntimeException e) {
            warn(e);
        }
    }

    private void warn(Throwable e) {
        if (!warned) {
            warned = true;
            log.warn("gravity: связь с clientmods не работает: {}", e.toString());
        }
    }

    /** Пакет op 1: тела карты. */
    static byte[] bodies(List<Body> bodies) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(5 + bodies.size() * 48);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(OP_BODIES);
            out.writeInt(bodies.size());
            for (Body b : bodies) {
                out.writeDouble(b.x());
                out.writeDouble(b.y());
                out.writeDouble(b.z());
                out.writeDouble(b.mu());
                out.writeDouble(b.radius());
                // у дыры в протоколе нет горизонта: шлём как точечную массу со смягчением = горизонт
                out.writeDouble(b.isHole() ? b.horizon() : b.softening());
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);              // ByteArrayOutputStream не бросает
        }
        return bytes.toByteArray();
    }

    /**
     * Поворот тела игрока без «лёжа» (кватернион spacephys/FreeLook: лицо +Z, верх +Y) и флаги. Кватернион единичный.
     */
    record Orientation(float w, float x, float y, float z, int flags) {
        boolean prone() {
            return (flags & FLAG_PRONE) != 0;
        }

        /** Грань под ногами: 0..5 (+X, −X, +Y, −Y, +Z, −Z) или −1 — свободно в пространстве. */
        int surface() {
            int v = (flags & SURFACE_MASK) >>> SURFACE_SHIFT;
            return v >= 1 && v <= SURFACES ? v - 1 : -1;
        }

        boolean active() {
            return (flags & FLAG_ACTIVE) != 0;
        }

        /** Грань, по которой повёрнута рамка игрока (стена/потолок), или −1 — рамка обычная. */
        int boxSurface() {
            int s = surface();
            return (flags & FLAG_BOX) != 0 && PlayerBox.turned(s) ? s : -1;
        }

        /** Куда смотрит «макушка» тела: лёжа — вдоль взгляда, стоя — верх камеры. */
        Vec3d bodyUp() {
            Quat q = new Quat(w, x, y, z);
            return prone() ? q.forward() : q.up();
        }

        /** Наклон тела от мирового верха, градусы (0 — стоит прямо, 90 — лежит, 180 — вниз головой). */
        double tilt() {
            double c = bodyUp().y();
            return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, c))));
        }

        /** Угол между двумя поворотами, градусы (без учёта флагов). */
        double angleTo(Orientation o) {
            double d = Math.abs((double) w * o.w + (double) x * o.x + (double) y * o.y + (double) z * o.z);
            return Math.toDegrees(2 * Math.acos(Math.min(1, d)));
        }
    }

    /**
     * Разобрать C2S op 3 (свой поворот). null — не op 3, короткое, не числа или нулевой кватернион. Кватернион
     * нормируется, неизвестные биты флагов отбрасываются (грань под ногами 7 — тоже: «свободно»). Лишние байты в
     * конце допускаются (новая версия клиента).
     */
    static Orientation decodeOrientation(byte[] data) {
        if (data == null || data.length < 18 || (data[0] & 0xFF) != OP_ORIENT) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data, 1, data.length - 1))) {
            double w = in.readFloat();
            double x = in.readFloat();
            double y = in.readFloat();
            double z = in.readFloat();
            int flags = sanitizeFlags(in.readUnsignedByte());
            if (!(Double.isFinite(w) && Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z))) {
                return null;
            }
            double n = Math.sqrt(w * w + x * x + y * y + z * z);
            if (!(n > 0.5 && n < 2)) {
                return null;                                  // не поворот — мусор
            }
            return new Orientation((float) (w / n), (float) (x / n), (float) (y / n), (float) (z / n), flags);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Оставить только известные биты флагов; неверная грань под ногами (7) — «свободно»; бит рамки — только вместе
     * со стеной или потолком.
     */
    static int sanitizeFlags(int raw) {
        int flags = raw & (FLAG_PRONE | FLAG_ACTIVE | SURFACE_MASK | FLAG_BOX);
        int surface = (flags & SURFACE_MASK) >>> SURFACE_SHIFT;
        if (surface > SURFACES) {
            flags &= ~SURFACE_MASK;
            surface = 0;
        }
        if (!PlayerBox.turned(surface - 1)) {
            flags &= ~FLAG_BOX;
        }
        return flags;
    }

    /** Разобрать C2S op 5 (возможности клиента); −1 — не op 5 или короткое. */
    static int decodeCaps(byte[] data) {
        if (data == null || data.length < 2 || (data[0] & 0xFF) != OP_CAPS) {
            return -1;
        }
        return data[1] & 0xFF;
    }

    /** Пакет S2C op 3: поворот тела игрока entityId. */
    static byte[] orientation(int entityId, Orientation o) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(22);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(OP_ORIENT);
            out.writeInt(entityId);
            out.writeFloat(o.w());
            out.writeFloat(o.x());
            out.writeFloat(o.y());
            out.writeFloat(o.z());
            out.writeByte(o.flags());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bytes.toByteArray();
    }

    /** Пакет S2C op 4: поворот тела игрока entityId больше не действует. */
    static byte[] orientationStop(int entityId) {
        return new byte[] {OP_ORIENT_STOP, (byte) (entityId >>> 24), (byte) (entityId >>> 16), (byte) (entityId >>> 8),
            (byte) entityId};
    }
}
