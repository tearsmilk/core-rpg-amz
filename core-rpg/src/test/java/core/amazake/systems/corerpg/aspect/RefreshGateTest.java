package core.amazake.systems.corerpg.aspect;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class RefreshGateTest {

    @Test
    void coalescesWithinOneSession() {
        final RefreshGate<Object> gate = new RefreshGate<>();
        final UUID id = UUID.randomUUID();
        final Object session = new Object();
        assertTrue(gate.claim(id, session));
        assertFalse(gate.claim(id, session));
        gate.release(id, session);
        assertTrue(gate.claim(id, session));
    }

    @Test
    void staleFlagOfPreviousSessionDoesNotBlockNewSession() {
        final RefreshGate<Object> gate = new RefreshGate<>();
        final UUID id = UUID.randomUUID();
        final Object oldSession = new Object();
        // Выход: forget, а затем ещё один запрос старой сессии, задача которого так и не выполнится.
        gate.forget(id);
        assertTrue(gate.claim(id, oldSession));
        final Object newSession = new Object();
        assertTrue(gate.claim(id, newSession));
        assertFalse(gate.claim(id, newSession));
        // Поздний release старой сессии не снимает флаг новой.
        gate.release(id, oldSession);
        assertTrue(gate.isPending(id));
        gate.release(id, newSession);
        assertFalse(gate.isPending(id));
    }
}
