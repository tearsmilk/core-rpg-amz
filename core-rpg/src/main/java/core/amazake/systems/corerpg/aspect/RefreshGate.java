package core.amazake.systems.corerpg.aspect;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Склейка отложенных пересчётов по игроку с привязкой к сессии. Флаг «пересчёт уже в очереди» хранит объект сессии
 * (для игрока — сам {@code Player}, который новый при каждом входе): если задача прошлой сессии так и не выполнилась
 * (планировщик ушедшего игрока её выбросил), новая сессия не застревает на её флаге.
 *
 * @param <S> объект сессии, сравнивается по идентичности
 */
public final class RefreshGate<S> {

    private final Map<UUID, S> pending = new ConcurrentHashMap<>();

    /** true — вызывающий должен поставить пересчёт в очередь; false — пересчёт этой сессии уже ждёт. */
    public boolean claim(final UUID id, final S session) {
        while (true) {
            final S previous = this.pending.putIfAbsent(id, session);
            if (previous == null) {
                return true;
            }
            if (previous == session) {
                return false;
            }
            // флаг остался от прошлой сессии: её задача уже не выполнится
            if (this.pending.replace(id, previous, session)) {
                return true;
            }
        }
    }

    /** Пересчёт сессии выполнен (или не поставлен): снять её флаг, чужой не трогать. */
    public void release(final UUID id, final S session) {
        this.pending.remove(id, session);
    }

    public void forget(final UUID id) {
        this.pending.remove(id);
    }

    public void clear() {
        this.pending.clear();
    }

    public boolean isPending(final UUID id) {
        return this.pending.containsKey(id);
    }
}
