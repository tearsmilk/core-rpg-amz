package core.amazake.systems.coreportals.geo;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlacementTest {

    /** Плоский мир: всё ниже {@code ground} — камень, выше — воздух; плюс отдельные блоки. */
    private static final class Grid implements BlockQuery {
        final int ground;
        final Set<String> extraSolid = new HashSet<>();
        final Set<String> holes = new HashSet<>();

        Grid(final int ground) {
            this.ground = ground;
        }

        Grid block(final int x, final int y, final int z) {
            this.extraSolid.add(x + "," + y + "," + z);
            return this;
        }

        Grid hole(final int x, final int y, final int z) {
            this.holes.add(x + "," + y + "," + z);
            return this;
        }

        private boolean isSolid(final int x, final int y, final int z) {
            final String k = x + "," + y + "," + z;
            if (this.holes.contains(k)) {
                return false;
            }
            return y < this.ground || this.extraSolid.contains(k);
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
    void facingFromYaw() {
        assertEquals(Facing.SOUTH, Facing.fromYaw(0));
        assertEquals(Facing.SOUTH, Facing.fromYaw(44));
        assertEquals(Facing.WEST, Facing.fromYaw(46));
        assertEquals(Facing.NORTH, Facing.fromYaw(180));
        assertEquals(Facing.NORTH, Facing.fromYaw(-180));
        assertEquals(Facing.EAST, Facing.fromYaw(-90));
        assertEquals(Facing.EAST, Facing.fromYaw(270));
        assertEquals(Facing.SOUTH, Facing.fromYaw(360));
        assertEquals(Facing.NORTH, Facing.SOUTH.opposite());
    }

    @Test
    void southOnFlatGround() {
        // Игрок в (10.3, 64, 5.5) смотрит на юг (+z): ряд z = 5 + 3 = 8, граница столбцов x = round(10.3) = 10.
        final Optional<Spot> s = Placement.find(10.3, 64, 5.5, 0f, 3, 3, new Grid(64));
        assertTrue(s.isPresent());
        final Spot spot = s.get();
        assertEquals(10.0, spot.x());
        assertEquals(64, spot.y());
        assertEquals(8.5, spot.z());
        assertEquals(Facing.SOUTH, spot.facing());
        assertArrayEquals(new int[] {9, 8, 10, 8}, spot.blocks());
    }

    @Test
    void eastUsesZColumns() {
        final Spot spot = Placement.find(-3.2, 70, 7.6, -90f, 2, 3, new Grid(70)).orElseThrow();
        assertEquals(Facing.EAST, spot.facing());
        assertEquals(-4 + 2 + 0.5, spot.x());   // floor(−3.2) = −4, ряд −2
        assertEquals(8.0, spot.z());
        assertArrayEquals(new int[] {-2, 7, -2, 8}, spot.blocks());
    }

    @Test
    void snapsDownAndUp() {
        // Перед игроком яма глубиной 2 — портал встаёт на её дно.
        final Grid pit = new Grid(64);
        for (int x = 8; x <= 11; x++) {
            pit.hole(x, 63, 8).hole(x, 62, 8);
        }
        assertEquals(62, Placement.find(10.3, 64, 5.5, 0f, 3, 3, pit).orElseThrow().y());
        // Ступенька высотой 1 — портал встаёт на неё.
        final Grid step = new Grid(64).block(9, 64, 8).block(10, 64, 8);
        assertEquals(65, Placement.find(10.3, 64, 5.5, 0f, 3, 3, step).orElseThrow().y());
        // Ступенька выше поиска — некуда.
        assertFalse(Placement.find(10.3, 64, 5.5, 0f, 3, 0, step).isPresent());
    }

    @Test
    void refusesWithoutRoom() {
        // Низкий потолок над одним из столбцов.
        final Grid ceiling = new Grid(64).block(10, 66, 8);
        assertFalse(Placement.find(10.3, 64, 5.5, 0f, 3, 0, ceiling).isPresent());
        // Рама 3,5 блока: блок на высоте 3 тоже мешает, на высоте 4 — нет.
        assertFalse(Placement.find(10.3, 64, 5.5, 0f, 3, 0, new Grid(64).block(9, 67, 8)).isPresent());
        assertTrue(Placement.find(10.3, 64, 5.5, 0f, 3, 0, new Grid(64).block(9, 68, 8)).isPresent());
        // В воздухе (опоры нет ни на каком уровне).
        final Grid sky = new Grid(-1000);
        assertFalse(Placement.find(10.3, 64, 5.5, 0f, 3, 3, sky).isPresent());
        // Нет опоры под одним столбцом.
        final Grid half = new Grid(64);
        for (int y = 55; y < 64; y++) {
            half.hole(9, y, 8);
        }
        assertFalse(Placement.find(10.3, 64, 5.5, 0f, 3, 3, half).isPresent());
    }

    @Test
    void frameSizesInSync() {
        // Поверхность — до перекладины рамы; проём по высоте вмещает раму.
        assertEquals(3.5, Spot.HEIGHT, 1e-9);
        assertEquals(Spot.HEIGHT - Spot.PILLAR, Spot.SURFACE_HEIGHT, 1e-9);
        assertEquals(3.42, Spot.SURFACE_HEIGHT, 1e-9);   // шейдер: H в cp_surface
        assertTrue(Placement.CLEAR_HEIGHT >= Math.ceil(Spot.HEIGHT));
    }

    @Test
    void searchOrderPrefersLower() {
        assertEquals(0, Placement.order(0));
        assertEquals(-1, Placement.order(1));
        assertEquals(1, Placement.order(2));
        assertEquals(-2, Placement.order(3));
        assertEquals(2, Placement.order(4));
    }

    @Test
    void surfaceBoxAndLocalToWorld() {
        final Spot south = new Spot(10, 64, 8.5, Facing.SOUTH, new int[] {9, 8, 10, 8});
        final Box b = south.surfaceBox();
        assertEquals(10 - Spot.SURFACE_HALF_WIDTH, b.minX(), 1e-9);
        assertEquals(8.5 - Spot.ENTRY_HALF_DEPTH, b.minZ(), 1e-9);
        assertEquals(64 + Spot.SURFACE_HEIGHT, b.maxY(), 1e-9);
        // Игрок, шагнувший в проём, задевает объём; стоящий в двух блоках перед ним — нет.
        assertTrue(b.overlaps(9.7, 64, 8.2, 10.3, 65.8, 8.8));
        assertFalse(b.overlaps(9.7, 64, 6.2, 10.3, 65.8, 6.8));

        final Spot east = new Spot(-1.5, 70, 8, Facing.EAST, new int[] {-2, 7, -2, 8});
        final Box e = east.surfaceBox();
        assertEquals(-1.5 - Spot.ENTRY_HALF_DEPTH, e.minX(), 1e-9);
        assertEquals(8 - Spot.SURFACE_HALF_WIDTH, e.minZ(), 1e-9);
        // «Вбок» для востока — +z, «вглубь» — +x.
        assertArrayEquals(new double[] {-1.5, 71, 9}, east.toWorld(1, 1, 0), 1e-9);
        assertArrayEquals(new double[] {-0.5, 70, 8}, east.toWorld(0, 0, 1), 1e-9);
        assertArrayEquals(new double[] {9, 64, 8.5}, south.toWorld(1, 0, 0), 1e-9);
    }
}
