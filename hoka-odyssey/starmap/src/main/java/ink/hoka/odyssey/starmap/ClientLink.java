package ink.hoka.odyssey.starmap;

import java.lang.reflect.Method;
import java.util.function.BiConsumer;
import net.minestom.server.entity.Player;
import org.slf4j.Logger;

/**
 * Связь с клиентским модулем starmap через модуль clientmods (softdepends). Классов clientmods при сборке нет, а на
 * сервере его может не быть совсем — только рефлексия (как SpaceflightLink в gravity): интерфейс ClientModsApi
 * ищем своим загрузчиком (softdepends даёт доступ к классам clientmods), сервис — через services().
 */
final class ClientLink {
    private static final String API = "ink.hoka.odyssey.clientmods.ClientModsApi";

    private final StarmapModule mod;
    private final Logger log;
    private Object api;
    private Method send;
    private Method on;
    private Method off;
    private boolean warned;

    ClientLink(StarmapModule mod, Logger log) {
        this.mod = mod;
        this.log = log;
    }

    /** Найти сервис clientmods (повторно — если clientmods перезагрузили, подписаться заново). */
    void resolve(BiConsumer<Player, byte[]> message) {
        Object found;
        Class<?> type;
        try {
            type = Class.forName(API, false, ClientLink.class.getClassLoader());
            found = mod.service(type);
        } catch (ClassNotFoundException | LinkageError e) {
            api = null;
            return;                                            // clientmods нет — карты ни у кого нет
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
            send = type.getMethod("send", Player.class, String.class, byte[].class);
            on = type.getMethod("on", String.class, BiConsumer.class);
            off = type.getMethod("off", String.class);
            on.invoke(found, StarmapProtocol.CHANNEL, message);
            api = found;
        } catch (ReflectiveOperationException | RuntimeException e) {
            warn(e);
            api = null;
        }
    }

    boolean connected() {
        return api != null;
    }

    void send(Player p, byte[] data) {
        Object a = api;
        if (a == null) {
            return;
        }
        try {
            send.invoke(a, p, StarmapProtocol.CHANNEL, data);
        } catch (ReflectiveOperationException | RuntimeException e) {
            warn(e);
        }
    }

    /** Отписаться (onDisable). */
    void close() {
        Object a = api;
        api = null;
        if (a != null) {
            try {
                off.invoke(a, StarmapProtocol.CHANNEL);
            } catch (ReflectiveOperationException | RuntimeException e) {
                warn(e);
            }
        }
    }

    private void warn(Throwable e) {
        if (!warned) {
            warned = true;
            log.warn("starmap: связь с clientmods не работает: {}", e.toString());
        }
    }
}
