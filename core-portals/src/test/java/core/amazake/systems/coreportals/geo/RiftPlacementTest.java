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

    /** Прежний размер по умолчанию — для проверок, что маленький разлом тоже работает. */
    private static final RiftPlacement.Size SMALL = new RiftPlacement.Size(5, 7, 3.6);

    @Test
    void defaultIsHuge() {
        assertEquals(9.0, SIZE.width());
        assertEquals(13.0, SIZE.height());
        assertEquals(6.6, SIZE.centerUp());
        assertEquals(0.1, SIZE.bottom(), 1e-9);
        assertEquals(13.1, SIZE.top(), 1e-9);
        assertEquals(4, RiftPlacement.columnsPerSide(SIZE));
        assertEquals(13, RiftPlacement.clearHeight(SIZE));
        assertEquals(1.0, RiftPlacement.Size.REFERENCE.scale(), 1e-9);
        assertEquals((9 / 5.0 + 13 / 7.0) / 2, SIZE.scale(), 1e-9);
    }

    @Test
    void sizeOfTheFreeSpace() {
        assertEquals(2, RiftPlacement.columnsPerSide(SMALL));
        assertEquals(7, RiftPlacement.clearHeight(SMALL));
        assertEquals(3, RiftPlacement.columnsPerSide(new RiftPlacement.Size(6, 7, 3.6)));
        assertEquals(1, RiftPlacement.columnsPerSide(new RiftPlacement.Size(3, 4, 2.1)));
        assertEquals(0, RiftPlacement.columnsPerSide(new RiftPlacement.Size(1, 2, 1.1)));
        assertEquals(2, RiftPlacement.columnsPerSide(new RiftPlacement.Size(5.4, 7, 3.6)));
        assertEquals(5, RiftPlacement.columnsPerSide(new RiftPlacement.Size(10, 13, 6.6)));
        assertEquals(8, RiftPlacement.clearHeight(new RiftPlacement.Size(6, 8, 4.1)));
        assertEquals(16, RiftPlacement.clearHeight(new RiftPlacement.Size(16, 16, 8.1)));
    }

    @Test
    void ovalCountScalesWithSize() {
        final Grid g = new Grid(64);
        final RiftPlacement.Count small = count(g, SMALL);
        final RiftPlacement.Count big = count(g, SIZE);
        // Блоков в овале ≈ π·a·b: 5 × 7 ≈ 27, 9 × 13 ≈ 92; середина — около четверти.
        assertTrue(small.oval() > 20 && small.oval() < 34, small.toString());
        assertTrue(big.oval() > 80 && big.oval() < 105, big.toString());
        assertTrue(big.core() > 15 && big.core() < 32, big.toString());
        assertEquals(0, big.blocked());
    }

    @Test
    void southOnFlatGround() {
        // Игрок в (10.3, 64, 5.5) смотрит на юг: ряд z = 8, столбцы x = 6..14, центр разлома (10.5, 70.6, 8.5).
        final Spot s = RiftPlacement.find(10.3, 64, 5.5, 0f, 3, 3, SIZE, new Grid(64)).orElseThrow();
        assertEquals(10.5, s.x());
        assertEquals(64, s.y());
        assertEquals(8.5, s.z());
        assertEquals(Facing.SOUTH, s.facing());
        assertArrayEquals(new int[] {6, 8, 7, 8, 8, 8, 9, 8, 10, 8, 11, 8, 12, 8, 13, 8, 14, 8}, s.blocks());
        assertArrayEquals(new double[] {10.5, 70.6, 8.5}, s.toWorld(0, SIZE.centerUp(), 0), 1e-9);
    }

    @Test
    void eastUsesColumnsAlongZ() {
        final Spot s = RiftPlacement.find(0.5, 70, 0.5, -90f, 4, 0, SMALL, new Grid(70)).orElseThrow();
        assertEquals(Facing.EAST, s.facing());
        assertEquals(4.5, s.x());
        assertEquals(0.5, s.z());
        assertArrayEquals(new int[] {4, -2, 4, -1, 4, 0, 4, 1, 4, 2}, s.blocks());
    }

    @Test
    void aFewObstaclesDoNotBlockAHugeRift() {
        // Дерево сбоку: ствол (x = 13) на высоте 0..4 и листва — несколько блоков у края овала.
        final Grid g = new Grid(64);
        for (int h = 0; h < 5; h++) {
            g.block(13, 64 + h, 8);
        }
        g.block(12, 69, 8).block(13, 69, 8).block(14, 69, 8).block(14, 70, 8);
        assertTrue(RiftPlacement.find(10.3, 64, 5.5, 0f, 3, 0, SIZE, g).isPresent());
        // Блоки за овалом (углы описанного прямоугольника) не считаются вовсе.
        final Grid corners = new Grid(64);
        for (final int x : new int[] {6, 14}) {
            for (int h = 0; h < 3; h++) {
                corners.block(x, 64 + h, 8).block(x, 64 + 12 - h, 8);
            }
        }
        assertEquals(0, count(corners, SIZE).blocked());
        assertTrue(RiftPlacement.find(10.3, 64, 5.5, 0f, 3, 0, SIZE, corners).isPresent());
    }

    @Test
    void heavyObstructionRefuses() {
        // Стена слева на всю высоту: 3 столбца из 9 — больше 20 % овала.
        final Grid wall = new Grid(64);
        for (int x = 6; x <= 8; x++) {
            for (int h = 0; h < 13; h++) {
                wall.block(x, 64 + h, 8);
            }
        }
        assertTrue(count(wall, SIZE).blocked() > count(wall, SIZE).oval() * RiftPlacement.MAX_BLOCKED);
        assertTrue(RiftPlacement.find(10.3, 64, 5.5, 0f, 3, 0, SIZE, wall).isEmpty());
        // Низкая пещера (потолок с высоты 6) — тоже.
        final Grid cave = new Grid(64);
        for (int x = 0; x < 20; x++) {
            for (int y = 70; y < 80; y++) {
                cave.block(x, y, 8);
            }
        }
        assertTrue(RiftPlacement.find(10.3, 64, 5.5, 0f, 3, 0, SIZE, cave).isEmpty());
    }

    @Test
    void middleMustBeAlmostFree() {
        // В середине 9 × 13 ≈ 23 блока: 2 занятых (10 %) — можно, 3 — нет.
        final Grid two = new Grid(64).block(10, 70, 8).block(11, 71, 8);
        assertEquals(2, count(two, SIZE).coreBlocked());
        assertTrue(RiftPlacement.find(10.3, 64, 5.5, 0f, 3, 0, SIZE, two).isPresent());
        final Grid three = new Grid(64).block(10, 70, 8).block(11, 71, 8).block(9, 69, 8);
        assertEquals(3, count(three, SIZE).coreBlocked());
        assertTrue(RiftPlacement.find(10.3, 64, 5.5, 0f, 3, 0, SIZE, three).isEmpty());
    }

    @Test
    void entryMustBeFree() {
        // Блок в проходе (у земли рядом со средним столбцом) — на этом уровне нельзя.
        assertTrue(RiftPlacement.find(10.3, 64, 5.5, 0f, 3, 0, SIZE, new Grid(64).block(11, 65, 8)).isEmpty());
        // Блок прямо в середине прохода: на уровне земли нельзя, зато можно встать на него (на блок выше).
        final Grid g = new Grid(64).block(10, 64, 8);
        assertTrue(RiftPlacement.find(10.3, 64, 5.5, 0f, 3, 0, SIZE, g).isEmpty());
        assertEquals(65, RiftPlacement.find(10.3, 64, 5.5, 0f, 3, 3, SIZE, g).orElseThrow().y());
    }

    @Test
    void needsGroundUnderTheMiddle() {
        assertTrue(RiftPlacement.find(10.3, 64, 5.5, 0f, 3, 3, SIZE, new Grid(64).hole(10, 63, 8)).isEmpty());
        // Без земли под краем — можно (разлом висит над полом).
        assertTrue(RiftPlacement.find(10.3, 64, 5.5, 0f, 3, 3, SIZE, new Grid(64).hole(6, 63, 8)).isPresent());
    }

    @Test
    void searchesGroundUpAndDown() {
        // Игрок на 66, земля на 64: найдено на два ниже.
        final Spot s = RiftPlacement.find(10.3, 66, 5.5, 0f, 3, 3, SIZE, new Grid(64)).orElseThrow();
        assertEquals(64, s.y());
        assertTrue(RiftPlacement.find(10.3, 66, 5.5, 0f, 3, 1, SIZE, new Grid(64)).isEmpty());
    }

    @Test
    void lowCeilingFitsOnlyASmallRift() {
        final Grid g = new Grid(64);
        for (int x = 0; x < 20; x++) {
            for (int y = 69; y < 72; y++) {
                g.block(x, y, 8);   // потолок пещеры с высоты 5
            }
        }
        assertTrue(RiftPlacement.find(10.3, 64, 5.5, 0f, 3, 0, SMALL, g).isEmpty());
        assertTrue(RiftPlacement.find(10.3, 64, 5.5, 0f, 3, 0, SIZE, g).isEmpty());
        // Маленький разлом 3 × 4 туда влезает.
        assertTrue(RiftPlacement.find(10.3, 64, 5.5, 0f, 3, 0, new RiftPlacement.Size(3, 4, 2.1), g).isPresent());
    }

    /** Подсчёт для места игрока (10.3, 64, 5.5) на юг, земля 64. */
    private static RiftPlacement.Count count(final Grid g, final RiftPlacement.Size size) {
        final int side = RiftPlacement.columnsPerSide(size);
        final int[] cols = new int[2 * (2 * side + 1)];
        for (int i = 0; i <= 2 * side; i++) {
            cols[2 * i] = 10 - side + i;
            cols[2 * i + 1] = 8;
        }
        return RiftPlacement.count(g, cols, side, RiftPlacement.clearHeight(size), 64, size);
    }

    @Test
    void entryCoversTheRift() {
        final Spot s = new Spot(10.5, 64, 8.5, Facing.SOUTH, new int[0]);
        // Игрок в середине проёма.
        assertTrue(RiftPlacement.touches(s, SMALL, 10.2, 64, 8.2, 10.8, 65.8, 8.8));
        // Ближе к краю, но ещё в овале (x = 8.6, середина по высоте).
        assertTrue(RiftPlacement.touches(s, SMALL, 8.4, 66.7, 8.2, 9.0, 68.5, 8.8));
        // Сбоку, за краем овала.
        assertFalse(RiftPlacement.touches(s, SMALL, 13.2, 64, 8.2, 13.8, 65.8, 8.8));
        // В углу описанного прямоугольника, но вне овала.
        assertFalse(RiftPlacement.touches(s, SMALL, 7.9, 64, 8.2, 8.5, 65.8, 8.8));
        // Перед разломом, не дошёл до плоскости.
        assertFalse(RiftPlacement.touches(s, SMALL, 10.2, 64, 7.0, 10.8, 65.8, 7.6));
        // Позади — тоже нет.
        assertFalse(RiftPlacement.touches(s, SMALL, 10.2, 64, 9.4, 10.8, 65.8, 10.0));
    }

    @Test
    void entryScalesWithSize() {
        final Spot s = new Spot(10.5, 64, 8.5, Facing.SOUTH, new int[0]);
        // 3,5 блока вбок от центра, на середине высоты: в огромном овале (полуось входа 4,14) — да, в 5 × 7 — нет.
        assertTrue(RiftPlacement.touches(s, SIZE, 6.7, 69.7, 8.2, 7.3, 71.5, 8.8));
        assertFalse(RiftPlacement.touches(s, SMALL, 6.7, 69.7, 8.2, 7.3, 71.5, 8.8));
        // Голова на 12-м блоке — ещё внутри огромного.
        assertTrue(RiftPlacement.touches(s, SIZE, 10.2, 74.5, 8.2, 10.8, 76.3, 8.8));
        assertEquals(0, RiftPlacement.distance(s, SIZE, 14.9, 70, 8.5), 1e-9);
    }

    @Test
    void distanceToTheRiftRectangle() {
        final Spot s = new Spot(10.5, 64, 8.5, Facing.SOUTH, new int[0]);
        // Прямо перед серединой, в 4 блоках.
        assertEquals(4, RiftPlacement.distance(s, SMALL, 10.5, 67, 4.5), 1e-9);
        // В плоскости, напротив края — 0.
        assertEquals(0, RiftPlacement.distance(s, SMALL, 12.0, 66, 8.5), 1e-9);
        // Сбоку: 3 блока за краем по ширине (край на x = 8.0), 4 по глубине.
        assertEquals(5, RiftPlacement.distance(s, SMALL, 5.0, 66, 4.5), 1e-9);
        // Над верхом (71,1) на 2.
        assertEquals(2, RiftPlacement.distance(s, SMALL, 10.5, 73.1, 8.5), 1e-9);
        final double[] n = RiftPlacement.nearest(s, SMALL, 30, 40, 0);
        assertArrayEquals(new double[] {13.0, 64.1, 8.5}, n, 1e-9);
    }
}
