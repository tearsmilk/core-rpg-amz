package core.amazake.systems.coremechanics.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorldMeshesTest {

    /** Физ-система без Jolt: считает статичные тела. */
    static final class FakeWorld implements PhysicsWorld {
        final Set<Integer> bodies = new HashSet<>();
        int next;
        boolean full;

        @Override
        public boolean hasRoom() {
            return !this.full;
        }

        @Override
        public int addStaticMesh(final float[] triangles, final int ox, final int oy, final int oz) {
            if (this.full) {
                return -1;
            }
            this.bodies.add(this.next);
            return this.next++;
        }

        @Override
        public void removeStatic(final int id) {
            this.bodies.remove(id);
        }

        @Override
        public RagdollBody createRagdoll(final double fx, final double fy, final double fz, final float yawDeg, final double vx,
                                         final double vy, final double vz) {
            return null;
        }

        @Override
        public int step() {
            return 0;
        }

        @Override
        public int bodyCount() {
            return this.bodies.size();
        }

        @Override
        public int activeBodyCount() {
            return 0;
        }

        @Override
        public long lastStepNanos() {
            return 0;
        }

        @Override
        public void destroy() {
        }
    }

    private static SectionSample floor(final int sx, final int sy, final int sz, final boolean hole) {
        final float[][] b = new float[16 * SectionSample.LAYERS * 16][];
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                if (!(hole && x == 5 && z == 5)) {
                    b[SectionSample.index(x, 0, z)] = SectionMesher.FULL;
                }
            }
        }
        return new SectionSample(sx, sy, sz, true, b);
    }

    @Test
    void requestsThenBuildsThenReady() {
        final FakeWorld w = new FakeWorld();
        final List<int[]> asked = new ArrayList<>();
        final WorldMeshes m = new WorldMeshes(w, (sx, sy, sz) -> asked.add(new int[] {sx, sy, sz}), -64, 320);
        m.beginTick();
        // коробка 8×8×8 у (8, 8, 8) задевает одну секцию (0,0,0)
        assertFalse(m.require(4, 4, 4, 12, 12, 12, 1, 24));
        assertEquals(1, asked.size());
        // повторный require не шлёт второй запрос, пока ждём ответа
        assertFalse(m.require(4, 4, 4, 12, 12, 12, 2, 24));
        assertEquals(1, asked.size());
        m.accept(floor(0, 0, 0, false), 3);
        assertTrue(m.require(4, 4, 4, 12, 12, 12, 4, 24));
        assertEquals(1, w.bodyCount());
        assertTrue(m.changedSince(4, 4, 4, 12, 12, 12, 3));
        assertFalse(m.changedSince(4, 4, 4, 12, 12, 12, 4));
    }

    @Test
    void unchangedSnapshotDoesNotRebuild() {
        final FakeWorld w = new FakeWorld();
        final WorldMeshes m = new WorldMeshes(w, (sx, sy, sz) -> { }, -64, 320);
        m.require(1, 1, 1, 2, 2, 2, 1, 24);
        m.accept(floor(0, 0, 0, false), 1);
        assertEquals(1, m.builds());
        m.markDirtyBlock(3, 0, 3);
        m.accept(floor(0, 0, 0, false), 2);
        assertEquals(1, m.builds());
        m.accept(floor(0, 0, 0, true), 3);   // дырка в полу — пересборка
        assertEquals(2, m.builds());
        assertEquals(1, w.bodyCount());       // старое тело убрано
    }

    @Test
    void dirtyBlockOnBorderRequestsNeighbour() {
        final FakeWorld w = new FakeWorld();
        final List<String> asked = new ArrayList<>();
        final WorldMeshes m = new WorldMeshes(w, (sx, sy, sz) -> asked.add(sx + "," + sy + "," + sz), -64, 320);
        m.require(10, 1, 1, 20, 2, 2, 1, 24);   // секции (0,0,0) и (1,0,0)
        m.accept(floor(0, 0, 0, false), 1);
        m.accept(floor(1, 0, 0, false), 1);
        asked.clear();
        m.beginTick();
        m.markDirtyBlock(15, 3, 3);              // на границе x: обе секции
        m.require(10, 1, 1, 20, 2, 2, 2, 24);
        assertEquals(List.of("0,0,0", "1,0,0"), asked);
    }

    @Test
    void unloadedChunkCountsAsReadyAndEmpty() {
        final FakeWorld w = new FakeWorld();
        final WorldMeshes m = new WorldMeshes(w, (sx, sy, sz) -> { }, -64, 320);
        assertFalse(m.require(1, 1, 1, 2, 2, 2, 1, 24));
        m.accept(SectionSample.unloaded(0, 0, 0), 1);
        assertTrue(m.require(1, 1, 1, 2, 2, 2, 2, 24));
        assertEquals(0, w.bodyCount());
    }

    @Test
    void requestLimitPerTick() {
        final List<String> asked = new ArrayList<>();
        final WorldMeshes m = new WorldMeshes(new FakeWorld(), (sx, sy, sz) -> asked.add(sx + "," + sy + "," + sz), -64, 320);
        m.beginTick();
        m.require(0, 0, 0, 40, 40, 40, 1, 5);   // 3×3×3 секций, лимит 5
        assertEquals(5, asked.size());
        m.beginTick();
        m.require(0, 0, 0, 40, 40, 40, 2, 5);
        assertEquals(10, asked.size());
    }

    @Test
    void evictAndDestroyRemoveBodies() {
        final FakeWorld w = new FakeWorld();
        final WorldMeshes m = new WorldMeshes(w, (sx, sy, sz) -> { }, -64, 320);
        m.require(1, 1, 1, 2, 2, 2, 1, 24);
        m.accept(floor(0, 0, 0, false), 1);
        assertEquals(1, w.bodyCount());
        m.evict(100, 200);
        assertEquals(1, m.sectionCount());
        m.evict(500, 200);
        assertEquals(0, m.sectionCount());
        assertEquals(0, w.bodyCount());
    }

    @Test
    void sectionKeysRoundTrip() {
        for (final int[] v : new int[][] {{0, 0, 0}, {-1, -4, -1}, {1_000_000, 19, -1_000_000}, {-33554432, -2048, 33554431}}) {
            final long k = WorldMeshes.key(v[0], v[1], v[2]);
            assertEquals(v[0], WorldMeshes.keyX(k));
            assertEquals(v[1], WorldMeshes.keyY(k));
            assertEquals(v[2], WorldMeshes.keyZ(k));
        }
    }
}
