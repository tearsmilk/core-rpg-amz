package ink.hoka.odyssey.starmap;

import ink.hoka.odyssey.api.OdysseyModule;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;

/**
 * Корабли модуля ships — только чтение и только рефлексией: у ships нет сервиса, а его классы (Ship, Flight)
 * закрыты в пакете. Читаем поля так, как они устроены на живом сервере (ships 0.1.0):
 * <ul>
 *   <li>{@code ShipsModule.ships} — {@code Map<String, Ship>} (ключ — id корабля);</li>
 *   <li>{@code Ship}: {@code owner} (UUID строкой), {@code ownerName}, {@code template}, {@code map}, {@code state}
 *       (PARKED, FLYING, STOWED, MODEL), {@code mx/my/mz/myaw} (модель), {@code t} (чертёж: {@code name, sx, sy, sz}),
 *       {@code flight} (в полёте), метод {@code world(int,int,int)} — мировая точка блока чертежа;</li>
 *   <li>{@code Flight}: {@code pose()} — {x, y, z, yaw, pitch, roll}, поле {@code pilot}.</li>
 * </ul>
 * Если что-то не нашлось (другая версия ships) — один раз пишем в лог и дальше кораблей просто нет.
 * Вызывать только в тике сервера: ships меняет свои корабли там же.
 */
final class ShipsProbe {
    record RawShip(String id, String name, String owner, String ownerName, String map, String state,
                   double x, double y, double z, float yaw, Object pilot) {
    }

    private final Logger log;
    private Object module;
    private Field shipsField;
    private boolean broken;

    ShipsProbe(Logger log) {
        this.log = log;
    }

    /** Все корабли модуля ships (пусто — модуля нет или он другой версии). */
    List<RawShip> ships(OdysseyModule self) {
        List<RawShip> out = new ArrayList<>();
        OdysseyModule ships = self.context().module("ships").orElse(null);
        if (ships == null) {
            module = null;
            return out;
        }
        if (ships != module) {                                  // ships (пере)загрузили — искать заново
            module = ships;
            broken = false;
            try {
                shipsField = ships.getClass().getDeclaredField("ships");
                shipsField.setAccessible(true);
            } catch (ReflectiveOperationException | RuntimeException e) {
                fail(e);
            }
        }
        if (broken) {
            return out;
        }
        List<Map.Entry<?, ?>> entries;
        try {
            Map<?, ?> map = (Map<?, ?>) shipsField.get(ships);
            entries = new ArrayList<>(map.entrySet());
        } catch (ConcurrentModificationException e) {
            return out;                                         // в следующий раз
        } catch (ReflectiveOperationException | RuntimeException e) {
            fail(e);
            return out;
        }
        for (Map.Entry<?, ?> e : entries) {
            try {
                RawShip s = read(String.valueOf(e.getKey()), e.getValue());
                if (s != null) {
                    out.add(s);
                }
            } catch (ReflectiveOperationException | RuntimeException ex) {
                fail(ex);
                return new ArrayList<>();
            }
        }
        return out;
    }

    private RawShip read(String id, Object ship) throws ReflectiveOperationException {
        if (ship == null) {
            return null;
        }
        Class<?> c = ship.getClass();
        String owner = str(get(c, ship, "owner"));
        String ownerName = str(get(c, ship, "ownerName"));
        String template = str(get(c, ship, "template"));
        String map = str(get(c, ship, "map"));
        Object stateObj = get(c, ship, "state");
        String state = stateObj == null ? "" : ((Enum<?>) stateObj).name();
        Object t = get(c, ship, "t");
        String name = template;
        if (t != null) {
            Object n = get(t.getClass(), t, "name");
            if (n instanceof String s && !s.isBlank()) {
                name = s;
            }
        }
        double x;
        double y;
        double z;
        float yaw;
        Object pilot = null;
        Object flight = get(c, ship, "flight");
        if ("FLYING".equals(state) && flight != null) {
            Method pose = flight.getClass().getDeclaredMethod("pose");
            pose.setAccessible(true);
            double[] p = (double[]) pose.invoke(flight);
            if (p == null || p.length < 4) {
                return null;
            }
            x = p[0];
            y = p[1];
            z = p[2];
            yaw = (float) p[3];
            pilot = get(flight.getClass(), flight, "pilot");
        } else if ("MODEL".equals(state)) {
            x = ((Number) get(c, ship, "mx")).doubleValue();
            y = ((Number) get(c, ship, "my")).doubleValue();
            z = ((Number) get(c, ship, "mz")).doubleValue();
            yaw = ((Number) get(c, ship, "myaw")).floatValue();
        } else if (t != null) {                                 // стоит блоками в мире (или спрятан)
            int sx = ((Number) get(t.getClass(), t, "sx")).intValue();
            int sy = ((Number) get(t.getClass(), t, "sy")).intValue();
            int sz = ((Number) get(t.getClass(), t, "sz")).intValue();
            Method world = c.getDeclaredMethod("world", int.class, int.class, int.class);
            world.setAccessible(true);
            Object centre = world.invoke(ship, sx / 2, sy / 2, sz / 2);
            double[] cp = vec(centre);
            x = cp[0] + 0.5;
            y = cp[1];
            z = cp[2] + 0.5;
            yaw = parkedYaw(c, ship, t, world, sx / 2, sy / 2, sz / 2, cp);
        } else {
            return null;
        }
        return new RawShip(id, name, owner, ownerName, map, state, x, y, z, yaw, pilot);
    }

    /** Курс стоящего корабля: куда «нос» чертежа (forwardDir) смотрит в мире после поворота. */
    private static float parkedYaw(Class<?> c, Object ship, Object t, Method world, int lx, int ly, int lz, double[] cp) {
        try {
            Method fd = t.getClass().getDeclaredMethod("forwardDir");
            fd.setAccessible(true);
            String dir = String.valueOf(fd.invoke(t)).toLowerCase(java.util.Locale.ROOT);
            int dx = 0;
            int dz = 0;
            switch (dir) {
                case "north", "n", "-z" -> dz = -1;
                case "south", "s", "+z", "z" -> dz = 1;
                case "east", "e", "+x", "x" -> dx = 1;
                case "west", "w", "-x" -> dx = -1;
                default -> {
                    return 0f;
                }
            }
            double[] f = vec(world.invoke(ship, lx + dx * 4, ly, lz + dz * 4));
            double wx = f[0] - cp[0];
            double wz = f[2] - cp[2];
            if (wx == 0 && wz == 0) {
                return 0f;
            }
            return (float) Math.toDegrees(Math.atan2(-wx, wz));   // yaw Minecraft: 0 — +Z, 90 — −X
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 0f;
        }
    }

    /** Точка Minestom (Vec/Pos/Point): x(), y(), z(). */
    private static double[] vec(Object p) throws ReflectiveOperationException {
        if (p instanceof net.minestom.server.coordinate.Point point) {
            return new double[] {point.x(), point.y(), point.z()};
        }
        throw new NoSuchMethodException("ships: world() вернул не точку");
    }

    private static Object get(Class<?> c, Object o, String name) throws ReflectiveOperationException {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private void fail(Throwable e) {
        if (!broken) {
            broken = true;
            log.warn("starmap: корабли модуля ships не читаются (другая версия ships?) — на карте будут только "
                    + "игроки: {}", e.toString());
        }
    }
}
