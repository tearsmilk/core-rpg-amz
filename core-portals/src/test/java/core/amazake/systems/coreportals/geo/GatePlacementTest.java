package core.amazake.systems.coreportals.geo;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GatePlacementTest {

    /** Плоский мир: ниже {@code ground} — камень, выше — воздух; плюс отдельные блоки. */
    private static final class Grid implements BlockQuery {
        final int ground;
        final Set<String> solid = new HashSet<>();

        Grid(final int ground) {
            this.ground = ground;
        }

        Grid block(final int x, final int y, final int z) {
            this.solid.add(x + "," + y + "," + z);
            return this;
        }

        @Override
        public boolean solid(final int x, final int y, final int z) {
            return y < this.ground || this.solid.contains(x + "," + y + "," + z);
        }

        @Override
        public boolean passable(final int x, final int y, final int z) {
            return !this.solid(x, y, z);
        }
    }

    @Test
    void southOnFlatGround() {
        // Игрок в (10.3, 64, 5.5) смотрит на юг: ряд z = 10 (distance 5), 7 столбцов x = 13..7 (вбок — (−dz, dx)).
        final Spot s = GatePlacement.find(10.3, 64, 5.5, 0f, 5, 3, new Grid(64)).orElseThrow();
        assertEquals(10.5, s.x());
        assertEquals(64, s.y());
        assertEquals(10.5, s.z());
        assertEquals(Facing.SOUTH, s.facing());
        assertArrayEquals(new int[] {13, 10, 12, 10, 11, 10, 10, 10, 9, 10, 8, 10, 7, 10}, s.blocks());
    }

    @Test
    void blockedFrontSpaceOrHeight() {
        // Блок в пространстве перед створками (ряд z = 8) — места нет.
        assertTrue(GatePlacement.find(10.3, 64, 5.5, 0f, 5, 0, new Grid(64).block(10, 64, 8)).isEmpty());
        // Блок на высоте 9 над землёй в ряду врат — тоже.
        assertTrue(GatePlacement.find(10.3, 64, 5.5, 0f, 5, 0, new Grid(64).block(13, 73, 10)).isEmpty());
        // На высоте 10 — уже не мешает.
        assertTrue(GatePlacement.find(10.3, 64, 5.5, 0f, 5, 0, new Grid(64).block(13, 74, 10)).isPresent());
    }

    @Test
    void touchesOnlyInsideAperture() {
        final Spot s = GatePlacement.find(10.3, 64, 5.5, 0f, 5, 3, new Grid(64)).orElseThrow();
        // Игрок в середине проёма.
        assertTrue(GatePlacement.touches(s, new Box(10.2, 64, 10.2, 10.8, 65.8, 10.8)));
        // Слишком далеко по нормали (перед вратами).
        assertFalse(GatePlacement.touches(s, new Box(10.2, 64, 9.0, 10.8, 65.8, 9.6)));
        // Сбоку, за краем проёма (на раме).
        assertFalse(GatePlacement.touches(s, new Box(12.9, 64, 10.2, 13.5, 65.8, 10.8)));
    }

    @Test
    void apertureGrowsFromZero() {
        final GateAperture zero = GateAperture.at(0);
        assertEquals(1.0E-4, zero.width());
        assertEquals(1.0E-4, zero.height());
        final GateAperture full = GateAperture.at(2);
        assertEquals(5.3, full.width(), 1e-12);
        assertEquals(9.4, full.height(), 1e-12);
        assertEquals(GateAperture.CENTER_Y - 8.77 / 2, full.ringBottom(), 1e-9);
        assertEquals(GateAperture.CENTER_Y + 8.77 / 2, full.ringTop(), 1e-9);
    }
}
