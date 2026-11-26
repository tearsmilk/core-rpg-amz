package ink.hoka.odyssey.guns;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BallisticsTest {
    private static final double[] FULL = {0, 0, 0, 1, 1, 1};
    private static final double[] SLAB = {0, 0, 0, 1, 0.5, 1};

    /** Мир из набора блоков и целей. */
    static final class World implements Ballistics.World {
        final Map<String, List<double[]>> blocks = new HashMap<>();
        final List<Ballistics.Target> targets = new ArrayList<>();
        int unloadedX = Integer.MAX_VALUE;

        void put(int x, int y, int z, double[] box) {
            blocks.put(x + "," + y + "," + z, List.of(box));
        }

        @Override
        public List<double[]> blockBoxes(int x, int y, int z) {
            if (x >= unloadedX) {
                return null;
            }
            return blocks.getOrDefault(x + "," + y + "," + z, List.of());
        }

        @Override
        public List<Ballistics.Target> targets(double cx, double cy, double cz, double radius) {
            return targets;
        }
    }

    static Ballistics.Target mob(String name, double x, double y, double z) {
        return new Ballistics.Target() {
            @Override public double minX() { return x - 0.3; }
            @Override public double minY() { return y; }
            @Override public double minZ() { return z - 0.3; }
            @Override public double maxX() { return x + 0.3; }
            @Override public double maxY() { return y + 1.8; }
            @Override public double maxZ() { return z + 0.3; }
            @Override public double eyeY() { return y + 1.62; }
            @Override public Object handle() { return name; }
        };
    }

    record Event(String what, Object who, double x, double y, double z, double dist, boolean head) {}

    static final class Log implements Ballistics.Listener {
        final List<Event> events = new ArrayList<>();

        @Override
        public void target(Ballistics.Target t, double x, double y, double z, double nx, double ny, double nz,
                           double fromStart, boolean head) {
            events.add(new Event("target", t.handle(), x, y, z, fromStart, head));
        }

        @Override
        public void block(int bx, int by, int bz, double x, double y, double z, double nx, double ny, double nz) {
            events.add(new Event("block", bx + "," + by + "," + bz, x, y, z, 0, false));
        }
    }

    @Test
    void rayBoxBasics() {
        Ballistics.Hit h = Ballistics.rayBox(0.5, 0.5, -5, 0, 0, 1, 0, 0, 0, 1, 1, 1, 100);
        assertNotNull(h);
        assertEquals(5, h.t(), 1e-9);
        assertEquals(-1, h.nz());
        assertNull(Ballistics.rayBox(0.5, 0.5, -5, 0, 0, 1, 0, 0, 0, 1, 1, 1, 4.9));   // не дотянулись
        assertNull(Ballistics.rayBox(0.5, 0.5, -5, 0, 0, -1, 0, 0, 0, 1, 1, 1, 100));  // позади
        assertNull(Ballistics.rayBox(2, 0.5, -5, 0, 0, 1, 0, 0, 0, 1, 1, 1, 100));     // мимо
        Ballistics.Hit in = Ballistics.rayBox(0.5, 0.5, 0.5, 0, 0, 1, 0, 0, 0, 1, 1, 1, 100);
        assertEquals(0, in.t());
        double s = Math.sqrt(0.5);
        Ballistics.Hit diag = Ballistics.rayBox(-1, 0.5, -1, s, 0, s, 0, 0, 0, 1, 1, 1, 100);
        assertEquals(Math.sqrt(2), diag.t(), 1e-9);
    }

    @Test
    void traceFindsFirstBlockAndSlab() {
        World w = new World();
        w.put(0, 64, 10, FULL);
        w.put(0, 64, 5, SLAB);                             // плита: нижняя половина
        // луч на высоте 64.75 проходит над плитой и упирается в полный блок
        Ballistics.BlockHit h = Ballistics.traceBlocks(w, 0.5, 64.75, 0.5, 0, 0, 1, 50);
        assertNotNull(h);
        assertEquals(10, h.z());
        assertEquals(9.5, h.hit().t(), 1e-9);
        // на высоте 64.25 — в плиту
        Ballistics.BlockHit s = Ballistics.traceBlocks(w, 0.5, 64.25, 0.5, 0, 0, 1, 50);
        assertEquals(5, s.z());
        // короткий луч — ничего
        assertNull(Ballistics.traceBlocks(w, 0.5, 64.75, 0.5, 0, 0, 1, 3));
        // отрицательное направление и диагональ
        w.put(-7, 64, -7, FULL);
        double k = Math.sqrt(0.5);
        Ballistics.BlockHit d = Ballistics.traceBlocks(w, 0.5, 64.5, 0.5, -k, 0, -k, 20);
        assertNotNull(d);
        assertEquals(-7, d.x());
        assertEquals(-7, d.z());
    }

    @Test
    void unloadedStopsBullet() {
        World w = new World();
        w.unloadedX = 20;
        Ballistics.BlockHit h = Ballistics.traceBlocks(w, 0.5, 64.5, 0.5, 1, 0, 0, 50);
        assertTrue(h.unloaded());
        assertEquals(19.5, h.hit().t(), 1e-9);
    }

    @Test
    void bulletHitsNearestTargetBeforeWall() {
        World w = new World();
        w.put(0, 64, 30, FULL);
        w.targets.add(mob("far", 0.5, 64, 20.5));
        w.targets.add(mob("near", 0.5, 64, 12.5));
        w.targets.add(mob("behindWall", 0.5, 64, 40.5));
        w.targets.add(mob("shooter", 0.5, 64, 0.5));
        Log log = new Log();
        Ballistics.Bullet b = new Ballistics.Bullet(0.5, 65.0, 0.5, 0, 0, 25, 16, 1, 0, 0, "shooter");
        assertFalse(b.step(w, log));                       // пробитие 1 — первая цель и всё
        assertEquals(1, log.events.size());
        assertEquals("near", log.events.get(0).who());
        assertEquals(11.7, log.events.get(0).dist(), 1e-9);
        assertFalse(log.events.get(0).head());
    }

    @Test
    void pierceAndWall() {
        World w = new World();
        w.put(0, 65, 30, FULL);
        w.targets.add(mob("a", 0.5, 64, 12.5));
        w.targets.add(mob("b", 0.5, 64, 20.5));
        w.targets.add(mob("c", 0.5, 64, 40.5));
        Log log = new Log();
        Ballistics.Bullet b = new Ballistics.Bullet(0.5, 65.7, 0.5, 0, 0, 25, 16, 3, 0, 0, null);
        boolean alive = b.step(w, log);                    // первые 25 блоков: a и b
        assertTrue(alive);
        assertEquals(2, log.events.size());
        assertTrue(log.events.get(0).head());              // 65.7 ≥ глаза 65.62 − 0.25
        assertFalse(b.step(w, log));                       // дальше стена, c за ней
        assertEquals("block", log.events.get(2).what());
        assertEquals(3, log.events.size());
    }

    @Test
    void gravityAndFrictionBendTrajectory() {
        World w = new World();
        Log log = new Log();
        Ballistics.Bullet b = new Ballistics.Bullet(0, 100, 0, 0, 0, 25, 16, 1, 0.15, 0.02, null);
        for (int i = 0; i < 3; i++) {
            assertTrue(b.step(w, log));
        }
        assertEquals(25 * 0.98 * 0.98 * 0.98, b.vz, 1e-9);
        assertTrue(b.vy < 0 && b.y < 100);
        // в вакууме — по прямой
        Ballistics.Bullet v = new Ballistics.Bullet(0, 100, 0, 0, 0, 25, 60, 1, 0, 0, null);
        for (int i = 0; i < 59; i++) {
            v.step(w, log);
        }
        assertEquals(100, v.y, 1e-9);
        assertEquals(25 * 59, v.z, 1e-6);
        assertFalse(v.step(w, log));                       // жизнь кончилась
        assertFalse(v.step(w, log));
    }

    @Test
    void headLine() {
        assertTrue(Ballistics.isHead(1.62, 1.62));
        assertTrue(Ballistics.isHead(1.40, 1.62));
        assertFalse(Ballistics.isHead(1.30, 1.62));
    }
}
