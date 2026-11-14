package core.amazake.systems.coreportals.geo;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RingPlacementTest {

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
    void southOnFlatGround() {
        // Игрок в (10.3, 64, 5.5) смотрит на юг: ряд z = 8, средний столбец x = 10, центр кольца (10.5, 65.8, 8.5).
        final Spot s = RingPlacement.find(10.3, 64, 5.5, 0f, 3, 3, new Grid(64)).orElseThrow();
        assertEquals(10.5, s.x());
        assertEquals(64, s.y());
        assertEquals(8.5, s.z());
        assertEquals(Facing.SOUTH, s.facing());
        assertArrayEquals(new int[] {9, 8, 10, 8, 11, 8}, s.blocks());
        assertArrayEquals(new double[] {10.5, 65.8, 8.5}, s.toWorld(0, RingPlacement.CENTER_UP, 0), 1e-9);
    }

    @Test
    void westUsesZColumns() {
        final Spot s = RingPlacement.find(-3.2, 70, 7.6, 90f, 2, 3, new Grid(70)).orElseThrow();
        assertEquals(Facing.WEST, s.facing());
        assertEquals(-4 - 2 + 0.5, s.x());   // floor(−3.2) = −4, ряд −6
        assertEquals(7.5, s.z());
        assertArrayEquals(new int[] {-6, 6, -6, 7, -6, 8}, s.blocks());
    }

    @Test
    void needsFreeThreeByFour() {
        // Свободно 3×4 — ставится; блок в любом из 12 — нет (в т. ч. на высоте 3 и сбоку).
        assertTrue(RingPlacement.find(10.3, 64, 5.5, 0f, 3, 0, new Grid(64)).isPresent());
        assertFalse(RingPlacement.find(10.3, 64, 5.5, 0f, 3, 0, new Grid(64).block(10, 67, 8)).isPresent());
        assertFalse(RingPlacement.find(10.3, 64, 5.5, 0f, 3, 0, new Grid(64).block(11, 64, 8)).isPresent());
        assertFalse(RingPlacement.find(10.3, 64, 5.5, 0f, 3, 0, new Grid(64).block(9, 65, 8)).isPresent());
        // Блок на высоте 4 (выше кольца) не мешает.
        assertTrue(RingPlacement.find(10.3, 64, 5.5, 0f, 3, 0, new Grid(64).block(10, 68, 8)).isPresent());
        // В воздухе — нет.
        assertFalse(RingPlacement.find(10.3, 64, 5.5, 0f, 3, 3, new Grid(-1000)).isPresent());
    }

    @Test
    void supportUnderMiddleColumnOnly() {
        // Под боковыми столбцами — обрыв: кольцо висит, опора нужна только под средним.
        final Grid ledge = new Grid(64);
        for (int y = 50; y < 64; y++) {
            ledge.hole(9, y, 8).hole(11, y, 8);
        }
        assertEquals(64, RingPlacement.find(10.3, 64, 5.5, 0f, 3, 0, ledge).orElseThrow().y());
        // Перед игроком яма 3 × 2 глубиной — кольцо опускается на её дно (как «Портал»).
        final Grid pit = new Grid(64);
        for (int x = 9; x <= 11; x++) {
            pit.hole(x, 63, 8).hole(x, 62, 8);
        }
        assertEquals(62, RingPlacement.find(10.3, 64, 5.5, 0f, 3, 3, pit).orElseThrow().y());
    }

    @Test
    void entryOnlyThroughTheInnerDisk() {
        final Spot s = new Spot(10.5, 64, 8.5, Facing.SOUTH, new int[] {9, 8, 10, 8, 11, 8});
        // Игрок шагнул в кольцо по центру (ноги на земле, хитбокс 0.6 × 1.8).
        assertTrue(RingPlacement.touches(s, 10.2, 64, 8.2, 10.8, 65.8, 8.8));
        // Стоит перед кольцом в двух блоках — нет.
        assertFalse(RingPlacement.touches(s, 10.2, 64, 6.2, 10.8, 65.8, 6.8));
        // Чуть в плоскости, но сбоку за внутренним радиусом (ближний край в 1.3 от центра) — нет.
        assertFalse(RingPlacement.touches(s, 11.8, 64, 8.2, 12.4, 65.8, 8.8));
        // Сбоку, но ближний край в 1.0 — да.
        assertTrue(RingPlacement.touches(s, 11.5, 64, 8.2, 12.1, 65.8, 8.8));
        // Над кольцом (ноги на 3.1 над землёй: 1.3 выше центра) — нет.
        assertFalse(RingPlacement.touches(s, 10.2, 67.1, 8.2, 10.8, 68.9, 8.8));
        // Угол: ближняя точка (0.9, −0.9) от центра, |..| = 1.27 > 1.2 — нет.
        assertFalse(RingPlacement.touches(s, 11.4, 63.1, 8.2, 12.0, 64.9, 8.8));
        // Касание слоя гранью не считается: хитбокс до z = 8.5 − 0.3.
        assertFalse(RingPlacement.touches(s, 10.2, 64, 7.6, 10.8, 65.8, 8.2));
        assertTrue(RingPlacement.touches(s, 10.2, 64, 7.6, 10.8, 65.8, 8.21));
    }

    @Test
    void entryAlongX() {
        // Кольцо лицом на восток: плоскость кольца — x = −1.5, «вбок» — +z.
        final Spot s = new Spot(-1.5, 70, 8.5, Facing.EAST, new int[] {-2, 7, -2, 8, -2, 9});
        assertTrue(RingPlacement.touches(s, -1.8, 70, 8.2, -1.2, 71.8, 8.8));
        assertFalse(RingPlacement.touches(s, -3.8, 70, 8.2, -3.2, 71.8, 8.8));
        assertTrue(RingPlacement.touches(s, -1.8, 70, 9.5, -1.2, 71.8, 10.1));    // вбок на 1.0
        assertFalse(RingPlacement.touches(s, -1.8, 70, 9.8, -1.2, 71.8, 10.4));   // вбок на 1.3
    }
}
