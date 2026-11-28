package ink.hoka.odyssey.starmap;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Протокол канала «starmap» (сервер ↔ клиентский модуль starmap), big-endian, как DataOutputStream.
 * <p>
 * Клиент → сервер:
 * <ul>
 *   <li>op 1 — карта открыта/закрыта: {@code u8 op, u8 open} (1 — открыта, 0 — закрыта). Пока карта открыта,
 *       клиент повторяет «открыта» раз в 5 секунд (напоминание).</li>
 *   <li>op 2 — «на какой я карте»: {@code u8 op} (клиент шлёт при входе и смене мира — чтобы хранить точки по
 *       имени карты). Не чаще раза в секунду на игрока.</li>
 * </ul>
 * Сервер → клиент:
 * <ul>
 *   <li>op 1 — контакты: {@code u8 op, u8 version (1), UTF map, i32 shipCount}, на корабль
 *       {@code UTF id, UTF name, UTF owner, u8 flags, f64 x, f64 y, f64 z, f32 yaw}; затем {@code i32 playerCount},
 *       на игрока {@code i64 uuidMost, i64 uuidLeast, UTF name, f64 x, f64 y, f64 z, f32 yaw, u8 flags}.
 *       Флаги корабля: бит 0 — мой, бит 1 — летит, бит 2 — я пилот, бит 3 — спрятан. Флаги игрока: бит 0 — пилотирует
 *       корабль. yaw — градусы Minecraft (0 — на юг, +Z; 90 — на запад). Строки — не длиннее 48 символов.</li>
 *   <li>op 2 — имя карты: {@code u8 op, UTF map} (ответ на C2S op 2; "" — карта не из maps/).</li>
 * </ul>
 */
final class StarmapProtocol {
    static final String CHANNEL = "starmap";
    static final int OP_OPEN = 1;
    static final int OP_WHERE = 2;
    static final int OP_CONTACTS = 1;
    static final int VERSION = 1;
    static final int MAX_STRING = 48;

    static final int SHIP_MINE = 1;
    static final int SHIP_FLYING = 2;
    static final int SHIP_PILOT_ME = 4;
    static final int SHIP_STOWED = 8;
    static final int PLAYER_PILOT = 1;

    private StarmapProtocol() {
    }

    record ShipContact(String id, String name, String owner, int flags, double x, double y, double z, float yaw) {
    }

    record PlayerContact(UUID uuid, String name, double x, double y, double z, float yaw, int flags) {
    }

    /**
     * Разобрать C2S op 1. Возвращает 1 — открыта, 0 — закрыта, −1 — не op 1, пусто или коротко. Лишние байты в
     * конце допускаются (новая версия клиента).
     */
    static int decodeOpen(byte[] data) {
        if (data == null || data.length < 2 || (data[0] & 0xFF) != OP_OPEN) {
            return -1;
        }
        return data[1] != 0 ? 1 : 0;
    }

    /** C2S op 2 «на какой я карте»? */
    static boolean isWhere(byte[] data) {
        return data != null && data.length >= 1 && (data[0] & 0xFF) == OP_WHERE;
    }

    /** Пакет S2C op 2: имя карты. */
    static byte[] where(String map) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(8 + (map == null ? 0 : map.length() * 2));
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(OP_WHERE);
            out.writeUTF(cut(map));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bytes.toByteArray();
    }

    /** Пакет S2C op 1. Нечисловые координаты (NaN, ∞) заменяются нулями — клиент такие всё равно отбросит. */
    static byte[] contacts(String map, List<ShipContact> ships, List<PlayerContact> players) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(16 + ships.size() * 64 + players.size() * 64);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(OP_CONTACTS);
            out.writeByte(VERSION);
            out.writeUTF(cut(map));
            out.writeInt(ships.size());
            for (ShipContact s : ships) {
                out.writeUTF(cut(s.id()));
                out.writeUTF(cut(s.name()));
                out.writeUTF(cut(s.owner()));
                out.writeByte(s.flags());
                out.writeDouble(fin(s.x()));
                out.writeDouble(fin(s.y()));
                out.writeDouble(fin(s.z()));
                out.writeFloat((float) fin(s.yaw()));
            }
            out.writeInt(players.size());
            for (PlayerContact p : players) {
                out.writeLong(p.uuid().getMostSignificantBits());
                out.writeLong(p.uuid().getLeastSignificantBits());
                out.writeUTF(cut(p.name()));
                out.writeDouble(fin(p.x()));
                out.writeDouble(fin(p.y()));
                out.writeDouble(fin(p.z()));
                out.writeFloat((float) fin(p.yaw()));
                out.writeByte(p.flags());
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);              // ByteArrayOutputStream не бросает
        }
        return bytes.toByteArray();
    }

    static String cut(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > MAX_STRING ? s.substring(0, MAX_STRING) : s;
    }

    private static double fin(double v) {
        return Double.isFinite(v) ? v : 0;
    }
}
