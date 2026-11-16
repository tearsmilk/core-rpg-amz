package core.amazake.systems.corepython.host;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Событие для тестов: Python отмечает в нём, что обработчик сработал. */
public final class TestEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    public final List<String> marks = new CopyOnWriteArrayList<>();
    private final String payload;

    public TestEvent(final String payload) {
        this.payload = payload;
    }

    public String getPayload() {
        return this.payload;
    }

    public void mark(final String s) {
        this.marks.add(s);
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
