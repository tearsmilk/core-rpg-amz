package core.amazake.systems.coremobs.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class PolygonTest {

    private static BlockPos p(final int x, final int z) {
        return new BlockPos(x, 64, z);
    }

    /** Квадрат 0..10 по вершинам-блокам: 11×11 блоков. */
    private static final Polygon SQUARE = new Polygon(List.of(p(0, 0), p(10, 0), p(10, 10), p(0, 10)));

    @Test
    void needsThreePoints() {
        assertThrows(IllegalArgumentException.class, () -> new Polygon(List.of(p(0, 0), p(1, 1))));
    }

    @Test
    void vertexBlocksAndEdgesAreInside() {
        assertTrue(SQUARE.containsBlock(0, 0));
        assertTrue(SQUARE.containsBlock(10, 10));
        assertTrue(SQUARE.containsBlock(5, 0));
        assertTrue(SQUARE.containsBlock(5, 5));
        assertFalse(SQUARE.containsBlock(11, 5));
        assertFalse(SQUARE.containsBlock(-1, 5));
        assertEquals(121, SQUARE.blockArea());
    }

    @Test
    void entityCoordinatesAtBlockBounds() {
        // блок 0 занимает x 0..1: его западный край тоже внутри
        assertTrue(SQUARE.contains(0.0, 5.0));
        assertTrue(SQUARE.contains(11.0, 5.0));
        assertFalse(SQUARE.contains(11.2, 5.0));
        assertEquals(0.0, SQUARE.distanceOutside(5, 5), 1e-9);
        assertEquals(5.0, SQUARE.distanceOutside(16.0, 5.5), 1e-9);
    }

    @Test
    void concaveShape() {
        // буква «Г»: выемка в правом верхнем углу
        final Polygon l = new Polygon(List.of(p(0, 0), p(20, 0), p(20, 5), p(5, 5), p(5, 20), p(0, 20)));
        assertTrue(l.containsBlock(2, 18));
        assertTrue(l.containsBlock(18, 2));
        assertFalse(l.containsBlock(15, 15));
        assertTrue(l.distanceOutside(15.5, 15.5) > 9);
    }

    @Test
    void triangle() {
        final Polygon t = new Polygon(List.of(p(0, 0), p(20, 0), p(0, 20)));
        assertTrue(t.containsBlock(3, 3));
        assertFalse(t.containsBlock(15, 15));
        assertTrue(t.containsBlock(10, 10)); // на гипотенузе
    }

    @Test
    void randomBlockIsInside() {
        final Random r = new Random(42);
        final Polygon l = new Polygon(List.of(p(0, 0), p(20, 0), p(20, 5), p(5, 5), p(5, 20), p(0, 20)));
        for (int i = 0; i < 500; i++) {
            final BlockPos b = l.randomBlock(r, 40);
            assertNotNull(b);
            assertTrue(l.containsBlock(b.x(), b.z()));
        }
    }

    @Test
    void outlineClosesTheShape() {
        final List<double[]> pts = SQUARE.outline(1.0);
        assertEquals(40, pts.size());
        for (final double[] pt : pts) {
            assertTrue(SQUARE.distanceToEdge(pt[0], pt[1]) < 1e-9);
        }
    }

    @Test
    void blockPosParseAndFormat() {
        assertEquals(new BlockPos(-3, 70, 12), BlockPos.parse(" -3, 70 ,12"));
        assertEquals("-3,70,12", new BlockPos(-3, 70, 12).format());
        assertThrows(IllegalArgumentException.class, () -> BlockPos.parse("1,2"));
    }
}
