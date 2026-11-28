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
 *   <li>5 ADS: u8 1/0 — прицел (ПКМ) нажат/отпущен (клиентский модуль забирает ПКМ себе, «использования» нет);</li>
 *   <li>6 REFIT: u8 1/0 — экран обвеса (Y) открыт/закрыт: пока открыт, сервер не даёт стрелять и целиться;</li>
 *   <li>7 ATTACH: u8 слот обвеса, u8 слот инвентаря (0–35) с предметом-обвесом — поставить (старый обвес — в тот же
 *       слот инвентаря);</li>
 *   <li>8 DETACH: u8 слот обвеса — снять (предмет — в свободный слот инвентаря, встроенный — просто убрать);</li>
 *   <li>9 BUILTIN: u8 слот, UTF id — поставить встроенный обвес оружия (без предмета);</li>
 *   <li>10 ADAPTER: u8 слот (0 — прицел), UTF id планки («» — без неё);</li>
 *   <li>11 LASER: u8 слот ЛЦУ (4 — laser, 3 — рукоять с ЛЦУ), i32 цвет RGB;</li>
 *   <li>12 ZOOM: u8 номер кратности прицела.</li>
 * </ul>
 * Слоты обвеса (номер): 0 scope, 1 muzzle, 2 stock, 3 grip, 4 laser, 5 extended_mag ({@link Attachments.Slot}).
 * Сервер → клиент:
 * <ul>
 *   <li>1 CONFIG: u8 версия, u16 оружий, на каждое — см. {@link #config};</li>
 *   <li>2 STATE: UTF id оружия в руке («» — нет), i32 патроны, i32 запас (−1 — бесконечно), u8 режим, u8 флаги
 *       (1 — перезарядка, 2 — достаёт, 4 — вакуум), i32 последний принятый seq;</li>
 *   <li>3 TRACER: i32 id стрелка, f64 x, y, z, f32 vx, vy, vz (блоков/с), f32 жизнь (с), i32 цвет RGB, u8 флаги
 *       (1 — вакуум);</li>
 *   <li>4 IMPACT: f64 x, y, z, f32 nx, ny, nz, u8 вид (0 — блок, 1 — тело, 2 — голова);</li>
 *   <li>5 RELOAD_START: u8 вид (1 — тактическая, 2 — пустой магазин, 3 — по одному патрону), f32 секунд до
 *       готовности; у вида 3 — ещё u8 циклов loop и u8 1 — с пустым магазином (вступление reload_intro_empty);</li>
 *   <li>6 REJECT: i32 seq, u8 причина ({@link FireValidator.Result#code});</li>
 *   <li>7 HIT: u8 вид (1 — попадание, 2 — в голову, 3 — убийство);</li>
 *   <li>8 POSE: i32 id игрока, u8 флаги (1 — в руке оружие, 2 — прицел, 4 — перезарядка), f32 секунд с начала
 *       перезарядки, f32 её длина — тем, кто его видит (поза тела от третьего лица; старые клиенты op не знают и
 *       пропускают);</li>
 *   <li>9 BACK: i32 id игрока, UTF id оружия «за спиной» («» — нет): оружие в быстрой панели не в руке — тем, кто
 *       его видит, и ему самому (старые клиенты op не знают и пропускают);</li>
 *   <li>10 LOADOUT: обвес своего оружия в руке — UTF id оружия («» — нет), UTF серийный номер, u8 n, n раз: u8 слот,
 *       UTF id обвеса, u8 флаги (1 — встроенный), i32 цвет луча (−1 — по умолчанию); UTF планка прицела, u8 кратность;
 *       дальше характеристики с обвесом: f32 время прицеливания, f32 разброс стоя, в движении, присев, в прицеле,
 *       f32 множитель вертикальной и горизонтальной отдачи, f32 приближение, i32 магазин, f32 rpm, f32 размер
 *       вспышки, u8 флаги (1 — глушитель, 2 — взрыв, 4 — поджог), u8 пуль за выстрел, f32 перезарядка тактическая и
 *       с пустым (с), u8 уровень магазина, f32 урон (первая полоса), f32 её дальность, f32 скорость пули, f32 множитель
 *       в голову, f32 пробитие брони, u8 пронзание, f32 отбрасывание, f32 вес обвеса, u8 n и n × f32 кратностей
 *       прицела;</li>
 *   <li>11 GEAR: i32 id игрока, UTF id оружия в руке («» — нет), UTF обвес «слот=id[:rrggbb]» через запятую
 *       (и «adapter=id») — тем, кто его видит, и ему самому (вид от третьего лица);</li>
 *   <li>12 REFIT_RESULT: u8 код (0 — готово, 1 — нет места в инвентаре, 2 — не подходит, 3 — слот заперт,
 *       4 — мешает планка/прицел, 5 — нет предмета, 6 — занято (перезарядка), 7 — оружия нет в руке, 8 — слишком
 *       часто), UTF подсказка по-русски.</li>
 * </ul>
 * Каталог обвесов и обвес каждого оружия клиент берёт из файла {@code guns/attachments.json} (clientmods, как модели).
 * У TRACER в флагах ещё 2 — выстрел с глушителем (вспышки у дула нет).
 * CONFIG после громкости вакуума — хвост: u16 оружий, на каждое UTF id и UTF файл анимаций тела от третьего лица;
 * за ним второй хвост: u16 оружий, на каждое UTF id и UTF id предмета hoitems («» — нет; по нему клиент рисует
 * оружие за спиной); за ним третий хвост: u16 оружий, на каждое UTF id, UTF вид (rifle / sniper / pistol), f32 время
 * ручного затвора (с, 0 — нет), u8 флаги (1 — оптика: в прицеле экран чёрный, кроме круга прицела), f32 размер вспышки
 * у ствола (TACZ muzzle_flash.scale); за ним четвёртый хвост: u16 оружий, на каждое UTF id и f32 отдача в вакууме
 * (блоков за выстрел в свободном полёте, 0 — нет; клиентский модуль отдаёт её spaceflight); за ним пятый хвост:
 * u16 оружий, на каждое UTF id, u8 пуль за выстрел (дробь), u8 флаги (1 — без своего прицела на экране, 2 — дальше
 * перезарядка по одному патрону: f32 intro, intro-empty, intro-empty-feed, loop, loop-feed, end); за ним шестой
 * хвост: u16 оружий, на каждое UTF id, u8 вид трассера (0 — пуля, 1 — плазма: короткий светящийся сгусток, голубая
 * вспышка и искры попадания) и f32 его длина (блоков, 0 — по умолчанию). Старые клиенты хвосты не читают. У TRACER
 * флаг 4 — плазма, за флагами тогда f32 длина сгустка (старые клиенты лишнее не читают). В BACK вместо одного id может прийти список через запятую (длинное оружие за спиной и
 * пистолет в кобуре).
 */
final class Proto {
    static final String CHANNEL = "guns";
    static final int VERSION = 1;

    static final int C_HELLO = 1;
    static final int C_FIRE = 2;
    static final int C_RELOAD = 3;
    static final int C_FIREMODE = 4;
    static final int C_ADS = 5;
    static final int C_REFIT = 6;
    static final int C_ATTACH = 7;
    static final int C_DETACH = 8;
    static final int C_BUILTIN = 9;
    static final int C_ADAPTER = 10;
    static final int C_LASER = 11;
    static final int C_ZOOM = 12;

    static final int S_CONFIG = 1;
    static final int S_STATE = 2;
    static final int S_TRACER = 3;
    static final int S_IMPACT = 4;
    static final int S_RELOAD = 5;
    static final int S_REJECT = 6;
    static final int S_HIT = 7;
    static final int S_POSE = 8;
    static final int S_BACK = 9;
    static final int S_LOADOUT = 10;
    static final int S_GEAR = 11;
    static final int S_REFIT_RESULT = 12;

    /** Флаги TRACER: 1 — вакуум, 2 — с глушителем (у дула нет вспышки). */
    static final int TRACER_VACUUM = 1;
    static final int TRACER_SILENCED = 2;
    /** Флаг TRACER: плазменный сгусток (короткий светящийся заряд); за флагами — f32 его длина, блоков. */
    static final int TRACER_PLASMA = 4;

    /** Итог действия с обвесом (REFIT_RESULT). */
    static final int REFIT_OK = 0;
    static final int REFIT_NO_SPACE = 1;
    static final int REFIT_NOT_ALLOWED = 2;
    static final int REFIT_LOCKED = 3;
    static final int REFIT_ADAPTER = 4;
    static final int REFIT_NO_ITEM = 5;
    static final int REFIT_BUSY = 6;
    static final int REFIT_NOT_HELD = 7;
    static final int REFIT_RATE = 8;

    static final int POSE_HOLDING = 1;
    static final int POSE_AIM = 2;
    static final int POSE_RELOAD = 4;

    static final int FLAG_RELOADING = 1;
    static final int FLAG_DRAWING = 2;
    static final int FLAG_VACUUM = 4;

    static final int MAX_SHOTS = 16;

    private Proto() {
    }

    record Shot(int ms, float dx, float dy, float dz) {}

    record Fire(int seq, List<Shot> shots) {}

    /**
     * Сообщение клиента: op и данные (FIRE — выстрелы; FIREMODE/ADS/REFIT/ZOOM — mode; действия с обвесом — slot
     * (номер {@link Attachments.Slot}), arg (слот инвентаря, цвет RGB) и str (id обвеса, планки)). null — битое.
     */
    record Msg(int op, Fire fire, int mode, int slot, int arg, String str) {
        Msg(int op, Fire fire, int mode) {
            this(op, fire, mode, 0, 0, "");
        }
    }

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
                case C_FIREMODE, C_ADS, C_REFIT, C_ZOOM -> {
                    return new Msg(op, null, in.readUnsignedByte());
                }
                case C_ATTACH -> {
                    int slot = in.readUnsignedByte();
                    return new Msg(op, null, 0, slot, in.readUnsignedByte(), "");
                }
                case C_DETACH -> {
                    return new Msg(op, null, 0, in.readUnsignedByte(), 0, "");
                }
                case C_BUILTIN, C_ADAPTER -> {
                    int slot = in.readUnsignedByte();
                    String id = in.readUTF();
                    if (id.length() > 64) {
                        return null;
                    }
                    return new Msg(op, null, 0, slot, 0, id);
                }
                case C_LASER -> {
                    int slot = in.readUnsignedByte();
                    return new Msg(op, null, 0, slot, in.readInt() & 0xFFFFFF, "");
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
        return config(guns, vacuumVolume, true);
    }

    /** CONFIG; backItems false (back-gun выключен) — второй хвост пустой: клиент не рисует оружие за спиной и у себя. */
    static byte[] config(Collection<GunDef> guns, float vacuumVolume, boolean backItems) {
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
            o.writeShort(guns.size());
            for (GunDef g : guns) {
                o.writeUTF(g.id());
                o.writeUTF(g.playerAnimation() == null ? "" : g.playerAnimation());
            }
            o.writeShort(backItems ? guns.size() : 0);
            for (GunDef g : backItems ? guns : List.<GunDef>of()) {
                o.writeUTF(g.id());
                o.writeUTF(g.item() == null ? "" : g.item());
            }
            o.writeShort(guns.size());
            for (GunDef g : guns) {
                o.writeUTF(g.id());
                o.writeUTF(g.type());
                o.writeFloat((float) g.boltTime());
                o.writeByte(g.scope() ? 1 : 0);
                o.writeFloat((float) g.flashScale());
            }
            o.writeShort(guns.size());                        // четвёртый хвост: отдача в вакууме, блоков за выстрел
            for (GunDef g : guns) {
                o.writeUTF(g.id());
                o.writeFloat((float) g.vacuumRecoil());
            }
            o.writeShort(guns.size());                        // пятый хвост: дробь, свой прицел, перезарядка по одному
            for (GunDef g : guns) {
                o.writeUTF(g.id());
                o.writeByte(g.pellets());
                GunDef.ShellReload sh = g.shellReload();
                o.writeByte((g.crosshair() ? 0 : 1) | (sh != null ? 2 : 0));
                if (sh != null) {
                    o.writeFloat((float) sh.intro());
                    o.writeFloat((float) sh.introEmpty());
                    o.writeFloat((float) sh.introEmptyFeed());
                    o.writeFloat((float) sh.loop());
                    o.writeFloat((float) sh.loopFeed());
                    o.writeFloat((float) sh.end());
                }
            }
            o.writeShort(guns.size());                        // шестой хвост: вид трассера (пуля / плазма) и его длина
            for (GunDef g : guns) {
                o.writeUTF(g.id());
                o.writeByte(g.plasma() ? 1 : 0);
                o.writeFloat((float) g.tracerLength());
            }
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

    static byte[] pose(int entityId, int flags, float reloadSince, float reloadLength) {
        return write(o -> {
            o.writeByte(S_POSE);
            o.writeInt(entityId);
            o.writeByte(flags);
            o.writeFloat(reloadSince);
            o.writeFloat(reloadLength);
        });
    }

    /** Оружие за спиной игрока entityId (gun «» — нет; несколько — через запятую: за спиной и в кобуре). */
    static byte[] back(int entityId, String gun) {
        return write(o -> {
            o.writeByte(S_BACK);
            o.writeInt(entityId);
            o.writeUTF(gun == null ? "" : gun);
        });
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
            o.writeByte(vacuum ? TRACER_VACUUM : 0);
        });
    }

    /** TRACER с флагами (TRACER_VACUUM, TRACER_SILENCED). */
    static byte[] tracer(int shooter, double x, double y, double z, double vx, double vy, double vz, double life,
                         int color, int flags) {
        return tracer(shooter, x, y, z, vx, vy, vz, life, color, flags, 0);
    }

    /** TRACER с флагами; с TRACER_PLASMA за флагами — length (длина сгустка, блоков). */
    static byte[] tracer(int shooter, double x, double y, double z, double vx, double vy, double vz, double life,
                         int color, int flags, double length) {
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
            o.writeByte(flags);
            if ((flags & TRACER_PLASMA) != 0) {
                o.writeFloat((float) length);
            }
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

    /** RELOAD_START перезарядки по одному патрону (вид 3) с хвостом: u8 циклов loop, u8 1 — с пустым магазином. */
    static byte[] reloadStart(int kind, double seconds, int loops, boolean empty) {
        return write(o -> {
            o.writeByte(S_RELOAD);
            o.writeByte(kind);
            o.writeFloat((float) seconds);
            o.writeByte(Math.max(0, Math.min(255, loops)));
            o.writeByte(empty ? 1 : 0);
        });
    }

    /** Обвес своего оружия в руке и его характеристики с обвесом (см. описание класса). gun «» — оружия нет. */
    static byte[] loadout(String gun, String serial, Loadout lo, AttachmentMath.Effective fx, GunDef base) {
        return write(o -> {
            o.writeByte(S_LOADOUT);
            o.writeUTF(gun == null ? "" : gun);
            o.writeUTF(serial == null ? "" : serial);
            var entries = lo == null ? java.util.Map.<Attachments.Slot, Loadout.Entry>of() : lo.entries();
            o.writeByte(entries.size());
            for (var e : entries.entrySet()) {
                o.writeByte(e.getKey().ordinal());
                o.writeUTF(e.getValue().id());
                o.writeByte(e.getValue().virtual() ? 1 : 0);
                o.writeInt(e.getValue().laser());
            }
            o.writeUTF(lo == null ? "" : lo.adapter);
            o.writeByte(lo == null ? 0 : Math.min(255, lo.zoom));
            GunDef g = fx == null ? base : fx.gun();
            if (g == null) {
                return;
            }
            o.writeFloat((float) g.aimTime());
            o.writeFloat((float) g.spreadStand());
            o.writeFloat((float) g.spreadMove());
            o.writeFloat((float) g.spreadSneak());
            o.writeFloat((float) g.spreadAim());
            o.writeFloat((float) (base == null ? 1 : AttachmentMath.recoilMul(base, g, true)));
            o.writeFloat((float) (base == null ? 1 : AttachmentMath.recoilMul(base, g, false)));
            o.writeFloat((float) g.zoom());
            o.writeInt(g.magazine());
            o.writeFloat((float) g.rpm());
            o.writeFloat((float) g.flashScale());
            o.writeByte((fx != null && fx.silenced() ? 1 : 0) | (fx != null && fx.explosion() != null ? 2 : 0)
                    | (fx != null && fx.ignite() ? 4 : 0));
            o.writeByte(g.pellets());
            o.writeFloat((float) g.tactical().total());
            o.writeFloat((float) g.empty().total());
            o.writeByte(fx == null ? 0 : fx.xmagLevel());
            o.writeFloat((float) (g.damage().length == 0 ? 0 : g.damage()[0]));
            o.writeFloat((float) (g.damageDistance().length == 0 || !Double.isFinite(g.damageDistance()[0]) ? 0
                    : g.damageDistance()[0]));
            o.writeFloat((float) g.speed());
            o.writeFloat((float) g.headshot());
            o.writeFloat((float) (fx == null ? 0 : fx.armorIgnore()));
            o.writeByte(g.pierce());
            o.writeFloat((float) g.knockback());
            o.writeFloat((float) (fx == null ? 0 : fx.weight()));
            double[] z = fx == null ? new double[0] : fx.zooms();
            o.writeByte(Math.min(16, z.length));
            for (int i = 0; i < Math.min(16, z.length); i++) {
                o.writeFloat((float) z[i]);
            }
        });
    }

    /** Обвес оружия в руке игрока entityId — тем, кто его видит (и ему самому): csv «слот=id[:rrggbb]», «adapter=id». */
    static byte[] gear(int entityId, String gun, String csv) {
        return write(o -> {
            o.writeByte(S_GEAR);
            o.writeInt(entityId);
            o.writeUTF(gun == null ? "" : gun);
            o.writeUTF(csv == null ? "" : csv);
        });
    }

    /** Итог действия с обвесом: код (REFIT_*) и подсказка игроку по-русски. */
    static byte[] refitResult(int code, String message) {
        return write(o -> {
            o.writeByte(S_REFIT_RESULT);
            o.writeByte(code);
            o.writeUTF(message == null ? "" : message);
        });
    }

    // ---------------------------------------------------------------- клиент → сервер, обвес (для тестов и клиента)

    static byte[] attach(int slot, int invSlot) {
        return write(o -> {
            o.writeByte(C_ATTACH);
            o.writeByte(slot);
            o.writeByte(invSlot);
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
