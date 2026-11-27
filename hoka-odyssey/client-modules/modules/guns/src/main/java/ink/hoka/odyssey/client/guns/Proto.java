package ink.hoka.odyssey.client.guns;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Протокол с серверным модулем guns (канал «guns», big-endian). Описание полей — в серверном
 * {@code ink.hoka.odyssey.guns.Proto}; здесь — та же раскладка с другой стороны.
 */
final class Proto {
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
    /** Поза стрелка для тех, кто его видит: i32 id сущности, u8 флаги (1 — в руке оружие, 2 — прицел, 4 — перезарядка), f32 с перезарядки, f32 её длина. */
    static final int S_POSE = 8;
    /** Оружие за спиной игрока: i32 id сущности, UTF id оружия («» — ничего). */
    static final int S_BACK = 9;

    static final int POSE_HOLDING = 1;
    static final int POSE_AIM = 2;
    static final int POSE_RELOAD = 4;

    static final int FLAG_RELOADING = 1;
    static final int FLAG_DRAWING = 2;
    static final int FLAG_VACUUM = 4;

    private Proto() {
    }

    /** Вид оружия (третий хвост CONFIG): rifle / sniper / pistol, ручной затвор (с, 0 — нет), оптика. */
    record Kind(String type, float boltTime, boolean scope, float flashScale) {
        static final Kind RIFLE = new Kind("rifle", 0, false, 1);

        boolean pistol() {
            return "pistol".equals(type);
        }
    }

    /** Ключевая точка отдачи. */
    record Key(float time, float min, float max) {}

    /** Оружие, как его описал сервер. */
    record Spec(String id, String name, String model, String hud, String flash, float rpm, boolean auto, boolean semi,
                int magazine, float aimTime, float zoom, float draw, float reloadTactical, float reloadEmpty,
                float speed, float life, int tracerColor, float spreadHip, float spreadAim, List<Key> pitch,
                List<Key> yaw, Map<String, String> sounds) {
        String sound(String key) {
            return sounds.getOrDefault(key, "");
        }

        float intervalMs() {
            return 60000f / Math.max(1, rpm);
        }
    }

    /**
     * CONFIG. playerAnims — сюда файл анимаций тела от третьего лица по id оружия (хвост сообщения после громкости
     * вакуума: u16 n, n раз UTF id и UTF путь; старый сервер его не шлёт). gunItems — сюда id предмета hoitems по id
     * оружия (второй хвост: u16 n, n раз UTF id и UTF предмет).
     */
    static Map<String, Spec> readConfig(DataInputStream in, float[] vacuumVolume, Map<String, String> playerAnims,
                                        Map<String, String> gunItems) throws IOException {
        return readConfig(in, vacuumVolume, playerAnims, gunItems, new HashMap<>());
    }

    /**
     * То же и третий хвост: kinds — вид оружия по id (u16 n, n раз UTF id, UTF вид, f32 затвор, u8 флаги: 1 — оптика,
     * f32 размер вспышки).
     */
    static Map<String, Spec> readConfig(DataInputStream in, float[] vacuumVolume, Map<String, String> playerAnims,
                                        Map<String, String> gunItems, Map<String, Kind> kinds) throws IOException {
        return readConfig(in, vacuumVolume, playerAnims, gunItems, kinds, new HashMap<>());
    }

    /**
     * То же и четвёртый хвост: vacuumRecoil — отдача в вакууме по id, блоков за выстрел (u16 n, n раз UTF id и f32;
     * старый сервер хвоста не шлёт — тогда пусто, отдачу ведёт сервер).
     */
    static Map<String, Spec> readConfig(DataInputStream in, float[] vacuumVolume, Map<String, String> playerAnims,
                                        Map<String, String> gunItems, Map<String, Kind> kinds,
                                        Map<String, Float> vacuumRecoil) throws IOException {
        int ver = in.readUnsignedByte();
        if (ver != VERSION) throw new IOException("версия протокола сервера " + ver + ", модуля " + VERSION);
        int n = in.readUnsignedShort();
        Map<String, Spec> out = new HashMap<>();
        for (int i = 0; i < n; i++) {
            String id = in.readUTF();
            String name = in.readUTF();
            String model = in.readUTF();
            String hud = in.readUTF();
            String flash = in.readUTF();
            float rpm = in.readFloat();
            int modes = in.readUnsignedByte();
            int mag = in.readInt();
            float aim = in.readFloat();
            float zoom = in.readFloat();
            float draw = in.readFloat();
            float rt = in.readFloat();
            float re = in.readFloat();
            float speed = in.readFloat();
            float life = in.readFloat();
            int color = in.readInt();
            float sh = in.readFloat();
            float sa = in.readFloat();
            List<Key> pitch = keys(in);
            List<Key> yaw = keys(in);
            int ns = in.readUnsignedByte();
            Map<String, String> sounds = new HashMap<>();
            for (int k = 0; k < ns; k++) sounds.put(in.readUTF(), in.readUTF());
            out.put(id, new Spec(id, name, model, hud, flash, rpm, (modes & 1) != 0, (modes & 2) != 0, mag, aim, zoom,
                    draw, rt, re, speed, life, color, sh, sa, pitch, yaw, sounds));
        }
        vacuumVolume[0] = in.available() >= 4 ? in.readFloat() : 0.35f;
        if (in.available() >= 2) {
            int m = in.readUnsignedShort();
            for (int i = 0; i < m; i++) {
                String id = in.readUTF();
                String path = in.readUTF();
                if (!path.isEmpty()) playerAnims.put(id, path);
            }
        }
        if (in.available() >= 2) {
            int m = in.readUnsignedShort();
            for (int i = 0; i < m; i++) {
                String id = in.readUTF();
                String item = in.readUTF();
                if (!item.isEmpty()) gunItems.put(id, item);
            }
        }
        if (in.available() >= 2) {
            int m = in.readUnsignedShort();
            for (int i = 0; i < m; i++) {
                String id = in.readUTF();
                String type = in.readUTF();
                float bolt = in.readFloat();
                int flags = in.readUnsignedByte();
                float flash = in.readFloat();
                kinds.put(id, new Kind(type, Math.max(0, bolt), (flags & 1) != 0, Float.isFinite(flash) ? flash : 1));
            }
        }
        if (in.available() >= 2) {
            int m = in.readUnsignedShort();
            for (int i = 0; i < m; i++) {
                String id = in.readUTF();
                float d = in.readFloat();
                if (Float.isFinite(d) && d > 0) vacuumRecoil.put(id, Math.min(d, 50f));
            }
        }
        return out;
    }

    private static List<Key> keys(DataInputStream in) throws IOException {
        int n = in.readUnsignedByte();
        List<Key> l = new ArrayList<>(n);
        for (int i = 0; i < n; i++) l.add(new Key(in.readFloat(), in.readFloat(), in.readFloat()));
        return l;
    }

    // ---------------------------------------------------------------- клиент → сервер

    record Shot(int ms, float dx, float dy, float dz) {}

    static byte[] hello() {
        return new byte[] {C_HELLO, VERSION};
    }

    static byte[] reload() {
        return new byte[] {C_RELOAD};
    }

    static byte[] ads(boolean on) {
        return new byte[] {C_ADS, (byte) (on ? 1 : 0)};
    }

    static byte[] fireMode(int mode) {
        return new byte[] {C_FIREMODE, (byte) mode};
    }

    static byte[] fire(int seq, List<Shot> shots) {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(8 + shots.size() * 14);
            DataOutputStream o = new DataOutputStream(bo);
            o.writeByte(C_FIRE);
            o.writeInt(seq);
            o.writeByte(shots.size());
            for (Shot s : shots) {
                o.writeShort(Math.max(0, Math.min(65535, s.ms())));
                o.writeFloat(s.dx());
                o.writeFloat(s.dy());
                o.writeFloat(s.dz());
            }
            o.flush();
            return bo.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
