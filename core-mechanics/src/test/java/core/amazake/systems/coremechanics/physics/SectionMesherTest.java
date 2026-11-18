package core.amazake.systems.coremechanics.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;

class SectionMesherTest {

    @Test
    void emptySectionHasNoTriangles() {
        final SectionMesher.Result r = SectionMesher.mesh((x, y, z) -> SectionMesher.EMPTY, 0, 0, 0);
        assertNull(r.triangles());
        assertEquals(0, r.triangleCount());
    }

    @Test
    void singleBlockHasTwelveTrianglesFacingOutward() {
        final SectionMesher.Result r = SectionMesher.mesh((x, y, z) -> x == 3 && y == 4 && z == 5 ? SectionMesher.FULL : SectionMesher.EMPTY,
            0, 0, 0);
        assertEquals(12, r.triangleCount());
        final float[] t = r.triangles();
        for (int i = 0; i < t.length; i += 9) {
            // нормаль (v1−v0)×(v2−v0) смотрит от центра блока
            final float ax = t[i + 3] - t[i], ay = t[i + 4] - t[i + 1], az = t[i + 5] - t[i + 2];
            final float bx = t[i + 6] - t[i], by = t[i + 7] - t[i + 1], bz = t[i + 8] - t[i + 2];
            final float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
            final float cx = (t[i] + t[i + 3] + t[i + 6]) / 3 - 3.5f, cy = (t[i + 1] + t[i + 4] + t[i + 7]) / 3 - 4.5f,
                cz = (t[i + 2] + t[i + 5] + t[i + 8]) / 3 - 5.5f;
            assertTrue(nx * cx + ny * cy + nz * cz > 0, "грань " + i / 9 + " смотрит внутрь");
        }
    }

    @Test
    void sharedFacesBetweenFullBlocksAreCulled() {
        // пол 16×16 на y=0: только верх и низ каждого блока + боковины по краю секции
        final SectionMesher.Result r = SectionMesher.mesh((x, y, z) -> y == 0 && x >= 0 && x < 16 && z >= 0 && z < 16
            ? SectionMesher.FULL : SectionMesher.EMPTY, 0, 0, 0);
        assertEquals(256 * 2 * 2 + 4 * 16 * 2, r.triangleCount());
    }

    @Test
    void hashChangesWithGeometry() {
        final long a = SectionMesher.mesh((x, y, z) -> y == 0 ? SectionMesher.FULL : SectionMesher.EMPTY, 0, 0, 0).hash();
        final long b = SectionMesher.mesh((x, y, z) -> y == 0 && x != 7 ? SectionMesher.FULL : SectionMesher.EMPTY, 0, 0, 0).hash();
        final long c = SectionMesher.mesh((x, y, z) -> y == 0 ? SectionMesher.FULL : SectionMesher.EMPTY, 0, 0, 0).hash();
        assertNotEquals(a, b);
        assertEquals(a, c);
    }

    @Test
    void sampleMapsWorldCoordinatesAndBorders() {
        final float[][] boxes = new float[16 * SectionSample.LAYERS * 16][];
        boxes[SectionSample.index(0, -1, 0)] = SectionMesher.FULL;   // блок под секцией
        boxes[SectionSample.index(0, 0, 0)] = SectionMesher.FULL;
        final SectionSample s = new SectionSample(-1, 2, 3, true, boxes);
        assertSame(SectionMesher.FULL, s.boxes(-16, 32, 48));
        assertSame(SectionMesher.FULL, s.boxes(-16, 31, 48));
        assertSame(SectionMesher.EMPTY, s.boxes(-17, 32, 48));   // соседний чанк не снимается
        // нижняя грань блока (−16, 32, 48) закрыта блоком под секцией: 5 граней = 10 треугольников
        assertEquals(10, s.mesh().triangleCount());
        assertSame(SectionMesher.EMPTY, SectionSample.unloaded(0, 0, 0).boxes(0, 0, 0));
    }

    @Test
    void boxesFromCollisionShape() {
        assertSame(SectionMesher.FULL, SectionSampler.fromBoxes(List.of(new BoundingBox(0, 0, 0, 1, 1, 1))));
        assertSame(SectionMesher.EMPTY, SectionSampler.fromBoxes(List.of()));
        final float[] slab = SectionSampler.fromBoxes(List.of(new BoundingBox(0, 0, 0, 1, 0.5, 1)));
        assertEquals(6, slab.length);
        assertEquals(0.5f, slab[4]);
    }
}
