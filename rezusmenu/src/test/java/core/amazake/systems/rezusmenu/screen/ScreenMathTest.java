package core.amazake.systems.rezusmenu.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ScreenMathTest {

    /** Разница поворота через ±180° (смотрит на север) не даёт скачка курсора через весь экран. */
    @Test
    void yawDifferenceWrapsAroundNorth() {
        assertEquals(10.0, Screen.wrap(-175.0 - 175.0), 1e-9);
        assertEquals(-10.0, Screen.wrap(175.0 - -175.0), 1e-9);
        assertEquals(0.0, Screen.wrap(360.0), 1e-9);
        assertEquals(90.0, Screen.wrap(90.0), 1e-9);
    }
}
