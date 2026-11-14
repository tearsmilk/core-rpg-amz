package core.amazake.systems.coreportals.anim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.DoubleUnaryOperator;
import org.junit.jupiter.api.Test;

class TimelineTest {

    @Test
    void easingEndpointsAndMonotonic() {
        final DoubleUnaryOperator[] curves = {Easing::linear, Easing::inQuad, Easing::outQuad, Easing::inCubic,
            Easing::outCubic, Easing::inOutCubic, Easing::inOutSine};
        for (final DoubleUnaryOperator f : curves) {
            assertEquals(0, f.applyAsDouble(0), 1e-9);
            assertEquals(1, f.applyAsDouble(1), 1e-9);
            assertEquals(0, f.applyAsDouble(-3), 1e-9);
            assertEquals(1, f.applyAsDouble(7), 1e-9);
            double prev = -1;
            for (int i = 0; i <= 100; i++) {
                final double v = f.applyAsDouble(i / 100.0);
                assertTrue(v >= prev - 1e-12, "монотонность");
                prev = v;
            }
        }
        assertEquals(0.5, Easing.inOutCubic(0.5), 1e-9);
        assertEquals(1, Easing.outBack(1, 1.2), 1e-9);
        assertEquals(0, Easing.outBack(0, 1.2), 1e-9);
        double max = 0;
        for (int i = 0; i <= 100; i++) {
            max = Math.max(max, Easing.outBack(i / 100.0, 1.2));
        }
        assertTrue(max > 1.03 && max < 1.08, "перелёт около 5 %: " + max);
    }

    @Test
    void phasesOfDefaultLifetime() {
        final Timeline tl = Timeline.of(600);
        assertEquals(560, tl.closeStart());
        assertEquals(600, tl.end());
        assertEquals(Phase.CRACK, tl.phase(0));
        assertEquals(Phase.CRACK, tl.phase(9));
        assertEquals(Phase.RISE, tl.phase(10));
        assertEquals(Phase.OPEN, tl.phase(40));
        assertEquals(Phase.IDLE, tl.phase(60));
        assertEquals(Phase.IDLE, tl.phase(559));
        assertEquals(Phase.CLOSE_SURFACE, tl.phase(560));
        assertEquals(Phase.CLOSE_FRAME, tl.phase(575));
        assertEquals(Phase.DONE, tl.phase(600));
        assertFalse(tl.enterable(59));
        assertTrue(tl.enterable(60));
        assertFalse(tl.enterable(560));
    }

    @Test
    void valuesFollowTheStory() {
        final Timeline tl = Timeline.of(600);
        // Трещина: рама под землёй, поверхность закрыта, свечение земли растёт.
        assertEquals(0, tl.rise(5), 1e-9);
        assertEquals(0, tl.surface(5), 1e-9);
        assertTrue(tl.ground(5) > 0 && tl.ground(5) < 1);
        // Подъём: середина пути ровно на середине (inOutCubic симметрична).
        assertEquals(0.5, tl.rise(25), 1e-9);
        assertEquals(1, tl.rise(40), 1e-9);
        assertEquals(0, tl.surface(40), 1e-9);
        // Раскрытие и открытый портал.
        assertTrue(tl.surface(50) > 0.5);
        assertEquals(1, tl.surface(60), 1e-9);
        assertEquals(1, tl.flames(300), 1e-9);
        assertEquals(1, tl.ground(300), 1e-9);
        // Закрытие: сначала поверхность, потом рама.
        assertEquals(1, tl.rise(570), 1e-9);
        assertTrue(tl.surface(570) < 1);
        assertEquals(0, tl.surface(575), 1e-9);
        assertTrue(tl.rise(585) < 1 && tl.rise(585) > 0);
        assertEquals(0, tl.rise(600), 1e-9);
        assertEquals(0, tl.flames(600), 1e-9);
        assertEquals(0, tl.ground(600), 1e-9);
    }

    @Test
    void earlyCloseStartsFromCurrentState() {
        final Timeline tl = Timeline.of(600).closeAt(25);   // на середине подъёма
        assertEquals(25, tl.closeStart());
        assertEquals(65, tl.end());
        assertEquals(0.5, tl.rise(30), 1e-9);              // держит высоту, пока «закрывается» поверхность
        assertEquals(0, tl.surface(30), 1e-9);
        assertTrue(tl.rise(50) < 0.5);
        assertEquals(0, tl.rise(65), 1e-9);
        assertEquals(Phase.DONE, tl.phase(65));
        // Повторное закрытие позже — без изменений; раньше уже начатого — тоже.
        assertSame(tl, tl.closeAt(40));
        // Закрытие открытого портала.
        final Timeline open = Timeline.of(600).closeAt(200);
        assertEquals(1, open.surface(200), 1e-9);
        assertEquals(Phase.CLOSE_SURFACE, open.phase(200));
    }

    @Test
    void lifetimeIsClamped() {
        assertEquals(Timeline.MIN_LIFETIME, Timeline.of(10).lifetimeTicks());
        assertThrows(IllegalArgumentException.class, () -> new Timeline(10, 0));
        assertTrue(Timeline.of(Timeline.MIN_LIFETIME).enterable(Timeline.OPENED));
    }
}
