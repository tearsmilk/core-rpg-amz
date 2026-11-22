package ink.hoka.odyssey.spacesky;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import ink.hoka.odyssey.api.Services;
import ink.hoka.odyssey.gravity.BlackHoles;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GravityHolesTest {
    /** Реестр сервисов в памяти. */
    private static final class Mem implements Services {
        final Map<Class<?>, Object> map = new HashMap<>();

        @Override
        public <T> void register(Class<T> type, T impl) {
            map.put(type, impl);
        }

        @Override
        public <T> Optional<T> get(Class<T> type) {
            return Optional.ofNullable(type.cast(map.get(type)));
        }

        @Override
        public void unregister(Class<?> type) {
            map.remove(type);
        }
    }

    @Test
    void withoutGravityNoHoles() {
        Mem s = new Mem();
        assertEquals(List.of(), GravityHoles.read(s));
        assertNull(GravityHoles.subscribe(s, () -> { }));
    }

    @Test
    void holesFromServiceSkipBroken() {
        Mem s = new Mem();
        Runnable[] listener = new Runnable[1];
        s.register(BlackHoles.class, new BlackHoles() {
            @Override
            public List<Hole> holes() {
                return List.of(new Hole("galaxy1", "abyss", 0.5, 65, -600, 6, 12, 0, 6, 1, 1, 1),
                    new Hole("galaxy1", "bad", 0, 0, 0, 0, 12, 0, 6, 1, 1, 1));
            }

            @Override
            public Runnable onChange(Runnable l) {
                listener[0] = l;
                return () -> listener[0] = null;
            }
        });
        List<Holes.Hole> l = GravityHoles.read(s);
        assertEquals(1, l.size(), "горизонт 0 — пропущена");
        assertEquals("galaxy1", l.get(0).map());
        assertEquals(0.2079, l.get(0).nx(), 1e-4);
        Runnable off = GravityHoles.subscribe(s, () -> { });
        off.run();
        assertNull(listener[0]);
    }
}
