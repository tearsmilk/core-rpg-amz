package ink.hoka.odyssey.coreshaders;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TriggerBoxTest {
    private static double[] player(double x, double y, double z) {
        return Triggers.playerBox(x, y, z, 0.3, 1.8);
    }

    @Test
    void cubeNeedsOverlap() {
        double[] cube = Geometry.box(Shape.CUBE, 0, 64, 0, Dir.UP);
        assertTrue(Triggers.touches(player(0.5, 64, 0.5), cube, false));
        assertTrue(Triggers.touches(player(-0.2, 64, 0.5), cube, false));
        assertFalse(Triggers.touches(player(-0.3, 64, 0.5), cube, false));
    }

    @Test
    void wallPlaneOnlyWhenPressedToWall() {
        double[] plane = Geometry.box(Shape.PLANE, 0, 64, 0, Dir.EAST);
        assertTrue(Triggers.touches(player(0.3, 64, 0.5), plane, true));
        assertTrue(Triggers.touches(player(0.35, 64, 0.5), plane, true));
        assertFalse(Triggers.touches(player(0.5, 64, 0.5), plane, true));
        assertFalse(Triggers.touches(player(0.8, 64, 0.5), plane, true));
    }

    @Test
    void floorPlaneWhenStandingOnIt() {
        double[] plane = Geometry.box(Shape.PLANE, 0, 64, 0, Dir.UP);
        assertTrue(Triggers.touches(player(0.5, 64, 0.5), plane, true));
        assertFalse(Triggers.touches(player(0.5, 64.5, 0.5), plane, true));
        assertFalse(Triggers.touches(player(1.5, 64, 0.5), plane, true));
    }
}
