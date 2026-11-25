package ink.hoka.odyssey.client.spaceflight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ink.hoka.odyssey.spacephys.Vec3d;
import java.util.Set;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.Test;

/** Запертая грань ботинок: голова её не меняет; соседняя грань — шагом в неё или за ребром. */
class SurfaceLockTest {
    private static final int PX = 0, NX = 1, PY = 2, NY = 3, PZ = 4;
    private static final IntPredicate YES = i -> true;
    private static final IntPredicate NO = i -> false;

    private static IntPredicate touching(Integer... faces) {
        Set<Integer> s = Set.of(faces);
        return s::contains;
    }

    @Test
    void headTurnDoesNotSwitch() {
        SurfaceLock l = new SurfaceLock();
        assertEquals(PX, l.tick(true, touching(PX, PY), PX, Vec3d.ZERO, YES));
        // камера повернулась: «по верху камеры» теперь пол — грань остаётся стеной
        for (int i = 0; i < 50; i++) assertEquals(PX, l.tick(true, touching(PX, PY), PY, Vec3d.ZERO, YES));
        // и шаг вдоль стены (не в пол) — тоже
        assertEquals(PX, l.tick(true, touching(PX, PY), PY, new Vec3d(0, 0, 1), YES));
    }

    @Test
    void walkIntoAdjacentFace() {
        SurfaceLock l = new SurfaceLock();
        l.tick(true, touching(PY), PY, Vec3d.ZERO, YES);
        // идёт по полу в +X, впереди стена (блок со стороны +X — грань с нормалью −X)
        assertEquals(PY, l.tick(true, touching(PY, NX), PY, new Vec3d(1, 0, 0), NO), "рамка не влезла — остаётся");
        assertEquals(NX, l.tick(true, touching(PY, NX), PY, new Vec3d(1, 0, 0), YES));
        // со стены вниз в пол
        assertEquals(PY, l.tick(true, touching(NX, PY), NX, new Vec3d(0, -1, 0), YES));
    }

    @Test
    void sideTouchWithoutWalkingIntoItKeepsFace() {
        SurfaceLock l = new SurfaceLock();
        l.tick(true, touching(PY), PY, Vec3d.ZERO, YES);
        assertEquals(PY, l.tick(true, touching(PY, NX), PY, new Vec3d(0, 0, 1), YES), "шаг вдоль стены");
        assertEquals(PY, l.tick(true, touching(PY, NX), PY, new Vec3d(-1, 0, 0), YES), "шаг от стены");
    }

    @Test
    void releaseAndJump() {
        SurfaceLock l = new SurfaceLock();
        l.tick(true, touching(NY), NY, Vec3d.ZERO, YES);
        assertEquals(NY, l.surface());
        assertEquals(-1, l.tick(false, touching(NY), NY, Vec3d.ZERO, YES));
        assertEquals(-1, l.tick(true, touching(), -1, Vec3d.ZERO, YES), "касаний нет — нечего запирать");
    }

    @Test
    void edgeWrapAndTimeout() {
        SurfaceLock l = new SurfaceLock();
        l.tick(true, touching(PY), PY, Vec3d.ZERO, YES);
        // сошёл с края на восток: опоры нет — грань ещё держится
        assertEquals(PY, l.tick(true, touching(), -1, new Vec3d(1, 0, 0), YES));
        assertTrue(l.lost());
        // рамка опустилась у восточной стороны блока (грань +X) — встаёт на неё
        assertEquals(PX, l.tick(true, touching(PX), PX, Vec3d.ZERO, YES));
        assertFalse(l.lost());
        // противоположная грань (−X) не годится: там «изнутри», а без опоры дольше LOST_TICKS — отпускает
        SurfaceLock m = new SurfaceLock();
        m.tick(true, touching(PX), PX, Vec3d.ZERO, YES);
        for (int i = 0; i < SurfaceLock.LOST_TICKS; i++) assertEquals(PX, m.tick(true, touching(NX), NX, Vec3d.ZERO, YES));
        assertEquals(-1, m.tick(true, touching(NX), NX, Vec3d.ZERO, YES));
    }

    @Test
    void wrapPrefersFaceBehindTheStep() {
        SurfaceLock l = new SurfaceLock();
        l.tick(true, touching(PY), PY, Vec3d.ZERO, YES);
        assertEquals(PX, l.tick(true, touching(PX, PZ), PZ, new Vec3d(1, 0, 0), YES));
    }
}
