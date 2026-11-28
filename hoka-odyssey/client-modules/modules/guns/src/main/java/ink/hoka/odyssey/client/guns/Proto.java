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
    /** Окно обвеса (Y): u8 1/0 — открыто/закрыто (пока открыто, сервер не даёт стрелять и целиться). */
    static final int C_REFIT = 6;
    /** Поставить обвес: u8 слот обвеса, u8 слот инвентаря (0–35). */
    static final int C_ATTACH = 7;
    /** Снять обвес: u8 слот. */
    static final int C_DETACH = 8;
    /** Поставить встроенный обвес оружия: u8 слот, UTF id. */
    static final int C_BUILTIN = 9;
    /** Планка прицела: u8 слот (0), UTF id («» — без неё). */
    static final int C_ADAPTER = 10;
    /** Цвет луча ЛЦУ: u8 слот, i32 RGB. */
    static final int C_LASER = 11;
    /** Кратность прицела: u8 номер. */
    static final int C_ZOOM = 12;

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
    /** Обвес своего оружия в руке и характеристики с ним ({@link #readLoadout}). */
    static final int S_LOADOUT = 10;
    /** Обвес оружия в руке игрока: i32 id, UTF id оружия, UTF «слот=id[:rrggbb]» через запятую и «adapter=id». */
    static final int S_GEAR = 11;
    /** Итог действия с обвесом: u8 код (0 — готово), UTF подсказка. */
    static final int S_REFIT_RESULT = 12;
    /** Флаг TRACER: выстрел с глушителем (вспышки у дула нет). */
    static final int TRACER_SILENCED = 2;
    /** Флаг TRACER: плазменный сгусток; за флагами — f32 его длина (блоков). */
    static final int TRACER_PLASMA = 4;
    /** Вид IMPACT: блок (1 — тело, 2 — голова). */
    static final int IMPACT_BLOCK = 0;
    /** Хвост IMPACT (u8 флаги, i32 цвет трассера): 1 — плазма (подпалина вместо отверстия). */
    static final int IMPACT_PLASMA = 1;

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

    /**
     * Пятый хвост CONFIG: пуль за выстрел (дробь), свой прицел на экране, перезарядка по одному патрону (null — магазином):
     * {intro, introEmpty, introEmptyFeed, loop, loopFeed, end}, секунды.
     */
    record Extra(int pellets, boolean crosshair, float[] shell) {
        static final Extra NONE = new Extra(1, true, null);

        /** Перезарядка по одному патрону: сколько циклов loop и её длина (с) — как план сервера (GunDef.shellPlan). */
        int shellLoops(int ammo, int target, int reserve) {
            int n = Math.max(0, target - ammo);
            if (reserve >= 0) n = Math.min(n, reserve);
            return ammo <= 0 && n > 0 ? n - 1 : n;
        }

        float shellTotal(boolean empty, int loops) {
            return (empty ? shell[1] : shell[0]) + loops * shell[3] + shell[5];
        }
    }

    /**
     * Шестой хвост CONFIG: вид трассера — plasma (короткий светящийся сгусток, голубая вспышка и искры) или пуля, и
     * длина сгустка (блоков, 0 — по умолчанию).
     */
    record Tracer(boolean plasma, float length) {
        static final Tracer BULLET = new Tracer(false, 0);
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
        return readConfig(in, vacuumVolume, playerAnims, gunItems, kinds, vacuumRecoil, new HashMap<>());
    }

    /** То же и пятый хвост: extras — дробь, свой прицел, перезарядка по одному патрону ({@link Extra}). */
    static Map<String, Spec> readConfig(DataInputStream in, float[] vacuumVolume, Map<String, String> playerAnims,
                                        Map<String, String> gunItems, Map<String, Kind> kinds,
                                        Map<String, Float> vacuumRecoil, Map<String, Extra> extras) throws IOException {
        return readConfig(in, vacuumVolume, playerAnims, gunItems, kinds, vacuumRecoil, extras, new HashMap<>());
    }

    /** То же и шестой хвост: tracers — вид трассера по id ({@link Tracer}; старый сервер не шлёт — пуля). */
    static Map<String, Spec> readConfig(DataInputStream in, float[] vacuumVolume, Map<String, String> playerAnims,
                                        Map<String, String> gunItems, Map<String, Kind> kinds,
                                        Map<String, Float> vacuumRecoil, Map<String, Extra> extras,
                                        Map<String, Tracer> tracers) throws IOException {
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
        if (in.available() >= 2) {
            int m = in.readUnsignedShort();
            for (int i = 0; i < m; i++) {
                String id = in.readUTF();
                int pellets = Math.max(1, in.readUnsignedByte());
                int flags = in.readUnsignedByte();
                float[] shell = null;
                if ((flags & 2) != 0) {
                    shell = new float[6];
                    for (int k = 0; k < 6; k++) {
                        float v = in.readFloat();
                        shell[k] = Float.isFinite(v) ? Math.max(0, Math.min(10, v)) : 0;
                    }
                    shell[3] = Math.max(0.05f, shell[3]);
                }
                extras.put(id, new Extra(pellets, (flags & 1) == 0, shell));
            }
        }
        if (in.available() >= 2) {
            int m = in.readUnsignedShort();
            for (int i = 0; i < m; i++) {
                String id = in.readUTF();
                int style = in.readUnsignedByte();
                float len = in.readFloat();
                tracers.put(id, new Tracer(style == 1, Float.isFinite(len) ? Math.max(0, Math.min(8, len)) : 0));
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

    // ---------------------------------------------------------------- обвес

    /** Характеристики оружия с обвесом (хвост LOADOUT). */
    record Stats(float aimTime, float spreadStand, float spreadMove, float spreadSneak, float spreadAim,
                 float recoilPitch, float recoilYaw, float zoom, int magazine, float rpm, float flashScale,
                 boolean silenced, boolean explosive, boolean ignite, int pellets, float reloadTactical,
                 float reloadEmpty, int xmagLevel, float damage, float damageRange, float speed, float headshot,
                 float armorIgnore, int pierce, float knockback, float weight, float[] zooms) {}

    /** LOADOUT: обвес своего оружия в руке; stats null — сервер не прислал (оружия нет). */
    record Loadout(String gun, String serial, Att.Gear gear, Stats stats) {}

    static Loadout readLoadout(DataInputStream in) throws IOException {
        String gun = in.readUTF();
        String serial = in.readUTF();
        Att.Gear g = new Att.Gear();
        int n = in.readUnsignedByte();
        for (int i = 0; i < n; i++) {
            int slot = in.readUnsignedByte();
            String id = in.readUTF();
            int flags = in.readUnsignedByte();
            int laser = in.readInt();
            if (slot >= Att.SLOTS.length || id.isEmpty()) continue;
            g.ids[slot] = id;
            g.builtin[slot] = (flags & 1) != 0;
            g.laser[slot] = laser < 0 ? -1 : laser & 0xFFFFFF;
        }
        g.adapter = in.readUTF();
        g.zoom = in.readUnsignedByte();
        Stats st = null;
        if (in.available() >= 4 * 11 + 2) {
            float aim = in.readFloat(), s1 = in.readFloat(), s2 = in.readFloat(), s3 = in.readFloat(), s4 = in.readFloat();
            float rp = in.readFloat(), ry = in.readFloat(), zoom = in.readFloat();
            int mag = in.readInt();
            float rpm = in.readFloat(), flash = in.readFloat();
            int flags = in.readUnsignedByte();
            int pellets = Math.max(1, in.readUnsignedByte());
            float rt = in.readFloat(), re = in.readFloat();
            int xm = in.readUnsignedByte();
            float dmg = in.readFloat(), dist = in.readFloat(), speed = in.readFloat(), hs = in.readFloat(), armor = in.readFloat();
            int pierce = in.readUnsignedByte();
            float kb = in.readFloat(), w = in.readFloat();
            int k = in.readUnsignedByte();
            float[] zooms = new float[k];
            for (int i = 0; i < k; i++) zooms[i] = in.readFloat();
            st = new Stats(fin(aim, 0.2f), fin(s1, 0), fin(s2, 0), fin(s3, 0), fin(s4, 0), fin(rp, 1), fin(ry, 1),
                    fin(zoom, 1), Math.max(1, mag), fin(rpm, 600), fin(flash, 1), (flags & 1) != 0, (flags & 2) != 0,
                    (flags & 4) != 0, pellets, fin(rt, 2), fin(re, 2), xm, fin(dmg, 0), fin(dist, 0), fin(speed, 0),
                    fin(hs, 1), fin(armor, 0), pierce, fin(kb, 0), fin(w, 0), zooms);
            g.xmag = xm;
        }
        return new Loadout(gun, serial, g, st);
    }

    private static float fin(float v, float def) {
        return Float.isFinite(v) ? v : def;
    }

    /** Оружие с характеристиками обвеса: магазин, темп, прицеливание, разброс, отдача, перезарядка, звук глушителя. */
    static Spec tuned(Spec s, Stats st) {
        if (st == null) return s;
        Map<String, String> snd = new HashMap<>(s.sounds());
        if (st.silenced() && !s.sound("silence").isEmpty()) snd.put("shoot", s.sound("silence"));
        return new Spec(s.id(), s.name(), s.model(), s.hud(), s.flash(), st.rpm(), s.auto(), s.semi(), st.magazine(),
                st.aimTime(), st.zoom(), s.draw(), st.reloadTactical(), st.reloadEmpty(), st.speed() > 0 ? st.speed() : s.speed(),
                s.life(), s.tracerColor(), st.spreadStand(), st.spreadAim(), scale(s.pitch(), st.recoilPitch()),
                scale(s.yaw(), st.recoilYaw()), snd);
    }

    private static List<Key> scale(List<Key> keys, float k) {
        if (Math.abs(k - 1) < 1e-4) return keys;
        List<Key> out = new ArrayList<>(keys.size());
        for (Key x : keys) out.add(new Key(x.time(), x.min() * k, x.max() * k));
        return out;
    }

    static byte[] refit(boolean open) {
        return new byte[] {C_REFIT, (byte) (open ? 1 : 0)};
    }

    static byte[] attach(int slot, int invSlot) {
        return new byte[] {C_ATTACH, (byte) slot, (byte) invSlot};
    }

    static byte[] detach(int slot) {
        return new byte[] {C_DETACH, (byte) slot};
    }

    static byte[] zoom(int level) {
        return new byte[] {C_ZOOM, (byte) Math.max(0, Math.min(255, level))};
    }

    static byte[] builtin(int slot, String id) {
        return slotUtf(C_BUILTIN, slot, id);
    }

    static byte[] adapter(int slot, String id) {
        return slotUtf(C_ADAPTER, slot, id);
    }

    static byte[] laser(int slot, int rgb) {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(8);
            DataOutputStream o = new DataOutputStream(bo);
            o.writeByte(C_LASER);
            o.writeByte(slot);
            o.writeInt(rgb & 0xFFFFFF);
            o.flush();
            return bo.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] slotUtf(int op, int slot, String id) {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(32);
            DataOutputStream o = new DataOutputStream(bo);
            o.writeByte(op);
            o.writeByte(slot);
            o.writeUTF(id == null ? "" : id);
            o.flush();
            return bo.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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

    static byte[] fire(int seq, List<Shot> shots, float ex, float ey, float ez) {
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
            o.writeFloat(ex);                                      // хвост: глаза от позиции игрока
            o.writeFloat(ey);
            o.writeFloat(ez);
            o.flush();
            return bo.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
