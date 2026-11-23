package ink.hoka.odyssey.client.starmap;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Контакты с сервера (модуль starmap): корабли и игроки на той же карте. Разбор S2C op 1 с проверкой границ
 * (протокол — README модуля). Всё — в клиентском потоке (обратные вызовы ядра идут в нём).
 */
final class Contacts {
    static final int OP_CONTACTS = 1;
    static final int OP_WHERE = 2;
    static final int MAX_COUNT = 4096;
    static final int MAX_STRING = 64;
    /** Данные сервера старше этого — считаем, что сервер замолчал (карта у него не открыта или модуля нет). */
    static final long STALE_MS = 3000;

    static final int SHIP_MINE = 1;
    static final int SHIP_FLYING = 2;
    static final int SHIP_PILOT_ME = 4;
    static final int SHIP_STOWED = 8;
    static final int PLAYER_PILOT = 1;

    record Ship(String id, String name, String owner, int flags, double x, double y, double z, float yaw) {
        boolean mine() { return (flags & SHIP_MINE) != 0; }

        boolean flying() { return (flags & SHIP_FLYING) != 0; }

        boolean pilotMe() { return (flags & SHIP_PILOT_ME) != 0; }
    }

    record Player(UUID uuid, String name, double x, double y, double z, float yaw, int flags) {
        boolean pilot() { return (flags & PLAYER_PILOT) != 0; }
    }

    List<Ship> ships = List.of();
    List<Player> players = List.of();
    /** Имя карты (мира) на сервере, "" — сервер не говорил. */
    String map = "";
    long receivedAt;

    boolean fresh() {
        return receivedAt != 0 && System.currentTimeMillis() - receivedAt < STALE_MS;
    }

    void clear() {
        ships = List.of();
        players = List.of();
        map = "";
        receivedAt = 0;
    }

    /**
     * Сообщение сервера. Возвращает true, если изменилось имя карты. Битое сообщение бросает IOException
     * (тогда старые данные остаются).
     */
    boolean read(byte[] data) throws IOException {
        if (data == null || data.length < 1) return false;
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        int op = in.readUnsignedByte();
        if (op == OP_WHERE) {
            String m = str(in);
            boolean changed = !m.equals(map);
            map = m;
            return changed;
        }
        if (op != OP_CONTACTS) return false;                       // незнакомое — от новой версии сервера
        in.readUnsignedByte();                                      // версия (1); поля новых версий — в конце
        String m = str(in);
        int ns = in.readInt();
        if (ns < 0 || ns > MAX_COUNT || ns > data.length / 30) throw new IOException("кораблей: " + ns);
        List<Ship> s = new ArrayList<>(ns);
        for (int i = 0; i < ns; i++) {
            String id = str(in), name = str(in), owner = str(in);
            int flags = in.readUnsignedByte();
            double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
            float yaw = in.readFloat();
            if (finite(x, y, z) && Float.isFinite(yaw)) s.add(new Ship(id, name, owner, flags, x, y, z, yaw));
        }
        int np = in.readInt();
        if (np < 0 || np > MAX_COUNT || np > data.length / 30) throw new IOException("игроков: " + np);
        List<Player> p = new ArrayList<>(np);
        for (int i = 0; i < np; i++) {
            UUID u = new UUID(in.readLong(), in.readLong());
            String name = str(in);
            double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
            float yaw = in.readFloat();
            int flags = in.readUnsignedByte();
            if (finite(x, y, z) && Float.isFinite(yaw)) p.add(new Player(u, name, x, y, z, yaw, flags));
        }
        ships = List.copyOf(s);
        players = List.copyOf(p);
        receivedAt = System.currentTimeMillis();
        boolean changed = !m.equals(map);
        map = m;
        return changed;
    }

    private static boolean finite(double x, double y, double z) {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
                && Math.abs(x) < 1e8 && Math.abs(y) < 1e8 && Math.abs(z) < 1e8;
    }

    /** Строка UTF с обрезкой и без управляющих символов. */
    private static String str(DataInputStream in) throws IOException {
        String s = in.readUTF();
        if (s.length() > MAX_STRING) s = s.substring(0, MAX_STRING);
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= ' ' && c != '§' && c != 0x7F) b.append(c);
        }
        return b.toString();
    }
}
