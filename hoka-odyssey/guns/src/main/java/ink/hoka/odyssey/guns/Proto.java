package ink.hoka.odyssey.guns;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Протокол между серверным и клиентским модулем guns (канал «guns» модуля clientmods, big-endian как
 * DataOutputStream). Первый байт — op.
 *
 * <p>Клиент → сервер:
 * <ul>
 *   <li>1 HELLO: u8 версия протокола — клиентский модуль загрузился (сервер шлёт CONFIG и STATE);</li>
 *   <li>2 FIRE: i32 seq, u8 n, затем n раз: u16 мс от прошлого выстрела, f32 dx, dy, dz — единичный вектор взгляда
 *       (уже с отдачей), раз в тик пачкой;</li>
 *   <li>3 RELOAD: без данных;</li>
 *   <li>4 FIREMODE: u8 режим (0 — очередь, 1 — одиночный);</li>
 *   <li>5 ADS: u8 1/0 — прицел (ПКМ) нажат/отпущен (клиентский модуль забирает ПКМ себе, «использования» нет).</li>
 * </ul>
 * Сервер → клиент:
 * <ul>
 *   <li>1 CONFIG: u8 версия, u16 оружий, на каждое — см. {@link #config};</li>
 *   <li>2 STATE: UTF id оружия в руке («» — нет), i32 патроны, i32 запас (−1 — бесконечно), u8 режим, u8 флаги
 *       (1 — перезарядка, 2 — достаёт, 4 — вакуум), i32 последний принятый seq;</li>
 *   <li>3 TRACER: i32 id стрелка, f64 x, y, z, f32 vx, vy, vz (блоков/с), f32 жизнь (с), i32 цвет RGB, u8 флаги
 *       (1 — вакуум);</li>
 *   <li>4 IMPACT: f64 x, y, z, f32 nx, ny, nz, u8 вид (0 — блок, 1 — тело, 2 — голова);</li>
 *   <li>5 RELOAD_START: u8 вид (1 — тактическая, 2 — пустой магазин), f32 секунд до готовности;</li>
 *   <li>6 REJECT: i32 seq, u8 причина ({@link FireValidator.Result#code});</li>
 *   <li>7 HIT: u8 вид (1 — попадание, 2 — в голову, 3 — убийство).</li>
 * </ul>
 */
final class Proto {
    static final String CHANNEL = "guns";
    static final int VERSION = 1;

    static final int C_HELLO = 1;
    static final int C_FIRE = 2;
    static final int C_RELOAD = 3;
    static final int C_FIREMODE = 4;
    static final int C_ADS = 5;

    static final int S_CONFIG = 1;
    static final int S_STATE = 2;
    static final int S_TRACER = 3;
    static final int S_IMPACT = 4;
    static final int S_RELOAD = 5;
    static final int S_REJECT = 6;
    static final int S_HIT = 7;

    static final int FLAG_RELOADING = 1;
    static final int FLAG_DRAWING = 2;
    static final int FLAG_VACUUM = 4;

    static final int MAX_SHOTS = 16;

    private Proto() {
    }

    record Shot(int ms, float dx, float dy, float dz) {}

    record Fire(int seq, List<Shot> shots) {}

    /** Сообщение клиента: op и (для FIRE/FIREMODE) данные. null — битое. */
    record Msg(int op, Fire fire, int mode) {}

    static Msg decode(byte[] b) {
        if (b == null || b.length == 0) {
            return null;
        }
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(b));
            int op = in.readUnsignedByte();
            switch (op) {
                case C_HELLO -> {
                    return new Msg(op, null, b.length > 1 ? in.readUnsignedByte() : 0);
                }
                case C_RELOAD -> {
                    return new Msg(op, null, 0);
                }
                case C_FIREMODE, C_ADS -> {
                    return new Msg(op, null, in.readUnsignedByte());
                }
                case C_FIRE -> {
                    int seq = in.readInt();
                    int n = in.readUnsignedByte();
                    if (n == 0 || n > MAX_SHOTS) {
                        return null;
                    }
                    List<Shot> shots = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) {
                        int ms = in.readUnsignedShort();
                        float x = in.readFloat();
                        float y = in.readFloat();
                        float z = in.readFloat();
                        double len = Math.sqrt((double) x * x + (double) y * y + (double) z * z);
                        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z) || len < 0.5 || len > 1.5) {
                            return null;
                        }
                        shots.add(new Shot(ms, (float) (x / len), (float) (y / len), (float) (z / len)));
                    }
                    return new Msg(op, new Fire(seq, shots), 0);
                }
                default -> {
                    return null;
                }
            }
        } catch (IOException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- сервер → клиент

    static byte[] config(Collection<GunDef> guns, float vacuumVolume) {
        return write(o -> {
            o.writeByte(S_CONFIG);
            o.writeByte(VERSION);
            o.writeShort(guns.size());
            for (GunDef g : guns) {
                o.writeUTF(g.id());
                o.writeUTF(g.name());
                o.writeUTF(g.model());
                o.writeUTF(g.hud());
                o.writeUTF(g.flash());
                o.writeFloat((float) g.rpm());
                o.writeByte((g.auto() ? 1 : 0) | (g.semi() ? 2 : 0));
                o.writeInt(g.magazine());
                o.writeFloat((float) g.aimTime());
                o.writeFloat((float) g.zoom());
                o.writeFloat((float) g.draw());
                o.writeFloat((float) g.tactical().total());
                o.writeFloat((float) g.empty().total());
                o.writeFloat((float) g.speed());
                o.writeFloat((float) g.life());
                o.writeInt(g.tracerColor());
                o.writeFloat((float) g.spreadStand());
                o.writeFloat((float) g.spreadAim());
                keys(o, g.recoilPitch());
                keys(o, g.recoilYaw());
                o.writeByte(g.sounds().size());
                for (Map.Entry<String, String> e : g.sounds().entrySet()) {
                    o.writeUTF(e.getKey());
                    o.writeUTF(e.getValue());
                }
            }
            o.writeFloat(vacuumVolume);
        });
    }

    private static void keys(DataOutputStream o, List<GunDef.RecoilKey> keys) throws IOException {
        o.writeByte(Math.min(255, keys.size()));
        for (int i = 0; i < Math.min(255, keys.size()); i++) {
            GunDef.RecoilKey k = keys.get(i);
            o.writeFloat((float) k.time());
            o.writeFloat((float) k.min());
            o.writeFloat((float) k.max());
        }
    }

    static byte[] state(String gun, int ammo, int reserve, int mode, int flags, int ackSeq) {
        return write(o -> {
            o.writeByte(S_STATE);
            o.writeUTF(gun == null ? "" : gun);
            o.writeInt(ammo);
            o.writeInt(reserve);
            o.writeByte(mode);
            o.writeByte(flags);
            o.writeInt(ackSeq);
        });
    }

    static byte[] tracer(int shooter, double x, double y, double z, double vx, double vy, double vz, double life,
                         int color, boolean vacuum) {
        return write(o -> {
            o.writeByte(S_TRACER);
            o.writeInt(shooter);
            o.writeDouble(x);
            o.writeDouble(y);
            o.writeDouble(z);
            o.writeFloat((float) vx);
            o.writeFloat((float) vy);
            o.writeFloat((float) vz);
            o.writeFloat((float) life);
            o.writeInt(color);
            o.writeByte(vacuum ? 1 : 0);
        });
    }

    static byte[] impact(double x, double y, double z, double nx, double ny, double nz, int kind) {
        return write(o -> {
            o.writeByte(S_IMPACT);
            o.writeDouble(x);
            o.writeDouble(y);
            o.writeDouble(z);
            o.writeFloat((float) nx);
            o.writeFloat((float) ny);
            o.writeFloat((float) nz);
            o.writeByte(kind);
        });
    }

    static byte[] reloadStart(int kind, double seconds) {
        return write(o -> {
            o.writeByte(S_RELOAD);
            o.writeByte(kind);
            o.writeFloat((float) seconds);
        });
    }

    static byte[] reject(int seq, int reason) {
        return write(o -> {
            o.writeByte(S_REJECT);
            o.writeInt(seq);
            o.writeByte(reason);
        });
    }

    static byte[] hit(int kind) {
        return write(o -> {
            o.writeByte(S_HIT);
            o.writeByte(kind);
        });
    }

    // ---------------------------------------------------------------- клиент → сервер (для тестов)

    static byte[] fire(int seq, List<Shot> shots) {
        return write(o -> {
            o.writeByte(C_FIRE);
            o.writeInt(seq);
            o.writeByte(shots.size());
            for (Shot s : shots) {
                o.writeShort(s.ms());
                o.writeFloat(s.dx());
                o.writeFloat(s.dy());
                o.writeFloat(s.dz());
            }
        });
    }

    private interface Body {
        void write(DataOutputStream o) throws IOException;
    }

    private static byte[] write(Body body) {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(64);
            DataOutputStream o = new DataOutputStream(bo);
            body.write(o);
            o.flush();
            return bo.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
