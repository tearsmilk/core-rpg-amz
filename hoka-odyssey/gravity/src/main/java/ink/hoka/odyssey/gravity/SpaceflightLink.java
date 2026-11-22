package ink.hoka.odyssey.gravity;

import ink.hoka.odyssey.spacephys.Body;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.List;
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
 *   <li>op 2 — физика игрока вкл/выкл: u8 op, u8 on (1 — клиент считает вакуум сам, 0 — не считает).</li>
 * </ul>
 * От клиента ничего не принимаем: его состояние — только обычные пакеты движения.
 */
final class SpaceflightLink {
    static final String MODULE = "spaceflight";
    static final int OP_BODIES = 1;
    static final int OP_PHYSICS = 2;
    private static final String API = "ink.hoka.odyssey.clientmods.ClientModsApi";

    private final GravityModule mod;
    private final Logger log;
    private Object api;
    private Method isReady;
    private Method send;
    private Method onReady;
    private Method off;
    private boolean warned;

    SpaceflightLink(GravityModule mod, Logger log) {
        this.mod = mod;
        this.log = log;
    }

    /**
     * Найти сервис clientmods (повторно — если он сменился после перезагрузки clientmods, подписаться заново).
     * ready получает игрока, у которого только что загрузился spaceflight (вызывать из тика — в тик и перекладываем).
     */
    void resolve(Consumer<Player> ready) {
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
            off = type.getMethod("off", String.class);
            Consumer<Player> cb = p -> mod.runInTick(() -> ready.accept(p));
            onReady.invoke(found, MODULE, cb);
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
}
