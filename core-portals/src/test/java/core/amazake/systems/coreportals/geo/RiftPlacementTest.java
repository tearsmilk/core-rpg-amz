package core.amazake.systems.coreportals.geo;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RiftPlacementTest {

    private static final RiftPlacement.Size SIZE = RiftPlacement.Size.DEFAULT;

    /** Плоский мир: ниже {@code ground} — камень, выше — воздух; плюс отдельные блоки и дыры. */
    private static final class Grid implements BlockQuery {
        final int ground;
        final Set<String> solid = new HashSet<>();
        final Set<String> holes = new HashSet<>();

        Grid(final int ground) {
            this.ground = ground;
        }

        Grid block(final int x, final int y, final int z) {
            this.solid.add(x + "," + y + "," + z);
            return this;
        }

        Grid hole(final int x, final int y, final int z) {
            this.holes.add(x + "," + y + "," + z);
            return this;
        }

        private boolean isSolid(final int x, final int y, final int z) {
            final String k = x + "," + y + "," + z;
            return !this.holes.contains(k) && (y < this.ground || this.solid.contains(k));
        }

        @Override
        public boolean solid(final int x, final int y, final int z) {
            return this.isSolid(x, y, z);
        }

        @Override
        public boolean passable(final int x, final int y, final int z) {
            return !this.isSolid(x, y, z);
        }
    }

    @Test
    void sizeOfTheFreeSpace() {
        assertEquals(2, RiftPlacement.columnsPerSide(SIZE));
        assertEquals(7, RiftPlacement.clearHeight(SIZE));
        assertEquals(3, RiftPlacement.columnsPerSide(new RiftPlacement.Size(6, 7, 3.6)));
        assertEquals(1, RiftPlacement.columnsPerSide(new RiftPlacement.Size(3, 4, 2.1)));
        assertEquals(0, RiftPlacement.columnsPerSide(new RiftPlacement.Size(1, 2, 1.1)));
        assertEquals(2, RiftPlacement.columnsPerSide(new RiftPlacement.Size(5.4, 7, 3.6)));
        assertEquals(8, RiftPlacement.clearHeight(new RiftPlacement.Size(6, 8, 4.1)));
        assertEquals(0.1, SIZE.bottom(), 1e-9);
        assertEquals(7.1, SIZE.top(), 1e-9);
    }

    @Test
    void southOnFlatGround() {
        // Игрок в (10.3, 64, 5.5) смотрит на юг: ряд z = 8, столбцы x = 8..12, центр разлома (10.5, 67.6, 8.5).
        final Spot s = RiftPlacement.find(10.3, 64, 5.5, 0f, 3, 3, SIZE, new Grid(64)).orElseThrow();
        assertEquals(10.5, s.x());
        assertEquals(64, s.y());
        assertEquals(8.5, s.z());
        assertEquals(Facing.SOUTH, s.facing());
        assertArrayEquals(new int[] {8, 8, 9, 8, 10, 8, 11, 8, 12, 8}, s.blocks());
        assertArrayEquals(new double[] {10.5, 67.6, 8.5}, s.toWorld(0, SIZE.centerUp(), 0), 1e-9);
    }

    @Test
    void eastUsesColumnsAlongZ() {
        final Spot s = RiftPlacement.find(0.5, 70, 0.5, -90f, 4, 0, SIZE, new Grid(70)).orElseThrow();
        assertEquals(Facing.EAST, s.facing());
        assertEquals(4.5, s.x());
        assertEquals(0.5, s.z());
        assertArrayEquals(new int[] {4, -2, 4, -1, 4, 0, 4, 1, 4, 2}, s.blocks());
    }

    @Test
    void obstacleInsideTheSpaceBlocks() {
        // Блок у края (x = 12) на высоте 6 — внутри 5 × 7: на этом уровне нельзя, выше — нет земли под серединой.
        assertTrue(RiftPlacement.find(10.3, 64, 5.5, 0f, 3, 3, SIZE, new Grid(64).block(12, 70, 8)).isEmpty());
        // Блок на высоте 7 (восьмой) — уже над разломом (верх 7,1, допуск 0,2): не мешает.
        assertTrue(RiftPlacement.find(10.3, 64, 5.5, 0f, 3, 3, SIZE, new Grid(64).block(12, 71, 8)).isPresent());
        // Блок сразу за краем по ширине (x = 13) не мешает.
        assertTrue(RiftPlacement.find(10.3, 64, 5.5, 0f, 3, 3, SIZE, new Grid(64).block(13, 66, 8)).isPresent());
    }

    @Test
    void needsGroundUnderTheMiddle() {
        assertTrue(RiftPlacement.find(10.3, 64, 5.5, 0f, 3, 3, SIZE, new Grid(64).hole(10, 63, 8)).isEmpty());
        // Без земли под краем — можно (разлом висит над полом).
        assertTrue(RiftPlacement.find(10.3, 64, 5.5, 0f, 3, 3, SIZE, new Grid(64).hole(8, 63, 8)).isPresent());
    }

    @Test
    void searchesGroundUpAndDown() {
        // Игрок на 66, земля на 64: найдено на два ниже.
        final Spot s = RiftPlacement.find(10.3, 66, 5.5, 0f, 3, 3, SIZE, new Grid(64)).orElseThrow();
        assertEquals(64, s.y());
        assertTrue(RiftPlacement.find(10.3, 66, 5.5, 0f, 3, 1, SIZE, new Grid(64)).isEmpty());
    }

    @Test
    void lowCeilingDoesNotFit() {
        final Grid g = new Grid(64);
        for (int x = 0; x < 20; x++) {
            g.block(x, 69, 8);   // потолок на высоте 5
        }
        assertTrue(RiftPlacement.find(10.3, 64, 5.5, 0f, 3, 0, SIZE, g).isEmpty());
        // Маленький разлом 3 × 4 туда влезает.
        assertTrue(RiftPlacement.find(10.3, 64, 5.5, 0f, 3, 0, new RiftPlacement.Size(3, 4, 2.1), g).isPresent());
    }

    @Test
    void entryCoversTheRift() {
        final Spot s = new Spot(10.5, 64, 8.5, Facing.SOUTH, new int[0]);
        // Игрок в середине проёма.
        assertTrue(RiftPlacement.touches(s, SIZE, 10.2, 64, 8.2, 10.8, 65.8, 8.8));
        // Ближе к краю, но ещё в овале (x = 8.6, середина по высоте).
        assertTrue(RiftPlacement.touches(s, SIZE, 8.4, 66.7, 8.2, 9.0, 68.5, 8.8));
        // Сбоку, за краем овала.
        assertFalse(RiftPlacement.touches(s, SIZE, 13.2, 64, 8.2, 13.8, 65.8, 8.8));
        // В углу описанного прямоугольника, но вне овала.
        assertFalse(RiftPlacement.touches(s, SIZE, 7.9, 64, 8.2, 8.5, 65.8, 8.8));
        // Перед разломом, не дошёл до плоскости.
        assertFalse(RiftPlacement.touches(s, SIZE, 10.2, 64, 7.0, 10.8, 65.8, 7.6));
        // Позади — тоже нет.
        assertFalse(RiftPlacement.touches(s, SIZE, 10.2, 64, 9.4, 10.8, 65.8, 10.0));
    }

    @Test
    void distanceToTheRiftRectangle() {
        final Spot s = new Spot(10.5, 64, 8.5, Facing.SOUTH, new int[0]);
        // Прямо перед серединой, в 4 блоках.
        assertEquals(4, RiftPlacement.distance(s, SIZE, 10.5, 67, 4.5), 1e-9);
        // В плоскости, напротив края — 0.
        assertEquals(0, RiftPlacement.distance(s, SIZE, 12.0, 66, 8.5), 1e-9);
        // Сбоку: 3 блока за краем по ширине (край на x = 8.0), 4 по глубине.
        assertEquals(5, RiftPlacement.distance(s, SIZE, 5.0, 66, 4.5), 1e-9);
        // Над верхом (71,1) на 2.
        assertEquals(2, RiftPlacement.distance(s, SIZE, 10.5, 73.1, 8.5), 1e-9);
        final double[] n = RiftPlacement.nearest(s, SIZE, 30, 40, 0);
        assertArrayEquals(new double[] {13.0, 64.1, 8.5}, n, 1e-9);
    }
}
