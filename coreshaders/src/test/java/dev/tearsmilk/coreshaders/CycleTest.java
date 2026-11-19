package dev.tearsmilk.coreshaders;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class CycleTest {
    private final List<String> images = Settings.DEFAULT_IMAGES;

    @Test
    void forwardWraps() {
        assertEquals("space", Cycle.next(images, "sky_sea", 1));
        assertEquals("sunset", Cycle.next(images, "end", 1));
        assertEquals("sky_sea", Cycle.next(images, "sea_sand", 1));
    }

    @Test
    void backwardWraps() {
        assertEquals("sea_sand", Cycle.next(images, "sky_sea", -1));
    }

    @Test
    void unknownGoesToFirst() {
        assertEquals("sky_sea", Cycle.next(images, "lava", 1));
        assertEquals("x", Cycle.next(List.of(), "x", 1));
    }

    @Test
    void shapes() {
        assertEquals(Shape.PLANE, Shape.CUBE.next());
        assertEquals(Shape.CUBE, Shape.PLANE.next());
        assertEquals(Shape.PLANE, Shape.parse("Плоскость"));
        assertEquals(Shape.CUBE, Shape.parse("cube"));
        assertEquals(null, Shape.parse("sphere"));
    }
}
