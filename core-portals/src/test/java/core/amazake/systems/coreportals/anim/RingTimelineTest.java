package core.amazake.systems.coreportals.anim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RingTimelineTest {

    @Test
    void phasesOfDefaultLifetime() {
        final RingTimeline tl = RingTimeline.of(600);
        assertEquals(570, tl.closeStart());
        assertEquals(600, tl.end());
        assertEquals(RingPhase.SPARK, tl.phase(0));
        assertEquals(RingPhase.SPARK, tl.phase(9));
        assertEquals(RingPhase.FORM, tl.phase(10));
        assertEquals(RingPhase.FORM, tl.phase(39));
        assertEquals(RingPhase.IDLE, tl.phase(40));
        assertEquals(RingPhase.IDLE, tl.phase(569));
        assertEquals(RingPhase.COLLAPSE, tl.phase(570));
        assertEquals(RingPhase.COLLAPSE, tl.phase(599));
        assertEquals(RingPhase.DONE, tl.phase(600));
        assertFalse(tl.enterable(39));
        assertTrue(tl.enterable(40));
        assertFalse(tl.enterable(570));
        // Полсекунды точки, полторы — формирование, полторы — схлопывание.
        assertEquals(10, RingTimeline.SPARK);
        assertEquals(30, RingTimeline.FORM);
        assertEquals(30, RingTimeline.CLOSING);
    }

    @Test
    void openingStory() {
        final RingTimeline tl = RingTimeline.of(600);
        // Точка: загорается, кольца и пустоты ещё нет.
        assertEquals(0, tl.ring(5), 1e-9);
        assertEquals(0, tl.voidOpen(5), 1e-9);
        assertEquals(1, tl.shaderPhase(5), 1e-9);
        assertTrue(tl.spark(5) > 0.5);
        // Кольцо начинает расти, пока точка ещё горит (кадры перекрываются).
        assertTrue(tl.ring(12) > 0 && tl.spark(12) > 0.3);
        // Пустота отстаёт от кольца: чёрная дыра «проявляется» изнутри.
        assertTrue(tl.voidOpen(20) < tl.ring(20));
        // Лёгкий перелёт размера кольца перед концом.
        double max = 0;
        for (int t = 8; t <= 40; t++) {
            max = Math.max(max, tl.ring(t));
        }
        assertTrue(max > 1.03 && max < 1.12, "перелёт: " + max);
        // Открыт: всё на месте, точка погасла, оборот ровно один (кольцо стоит без поворота).
        assertEquals(1, tl.ring(40), 1e-9);
        assertEquals(1, tl.voidOpen(40), 1e-9);
        assertEquals(0, tl.shaderPhase(40), 1e-9);
        assertEquals(0, tl.spark(40), 1e-9);
        assertEquals(RingTimeline.SPIN_OPEN, tl.spin(40), 1e-9);
        assertEquals(1, tl.glow(300), 1e-9);
        assertEquals(1, tl.shardSize(300), 1e-9);
        assertEquals(1, tl.shardRadius(300), 1e-9);
        assertEquals(RingTimeline.SPIN_OPEN, tl.spin(300), 1e-9);
    }

    @Test
    void collapseStory() {
        final RingTimeline tl = RingTimeline.of(600);
        final long c = tl.closeStart();
        // Замах: кольцо сперва чуть раздувается, потом схлопывается в точку.
        assertTrue(tl.ring(c + 6) > 1.0);
        assertTrue(tl.ring(c + 21) < 0.5);
        assertEquals(0, tl.ring(c + RingTimeline.COLLAPSE), 1e-9);
        assertEquals(0, tl.ring(c + 29), 1e-9);
        // Пустота стягивается в точку раньше кольца.
        assertEquals(0, tl.voidOpen(c + RingTimeline.COLLAPSE - 4), 1e-9);
        // Кольцо разгоняется: поворот растёт всё быстрее, но за кадр — меньше пол-оборота (интерполяция без рывков).
        double prevStep = 0;
        for (long t = c; t < c + RingTimeline.CLOSING; t += RingTimeline.KEY_STEP) {
            final double step = tl.spin(t + RingTimeline.KEY_STEP) - tl.spin(t);
            assertTrue(step >= prevStep - 1e-12, "разгон");
            assertTrue(step < Math.PI * 0.75, "шаг поворота " + step);
            prevStep = step;
        }
        // Осколки летят внутрь и исчезают.
        assertTrue(tl.shardRadius(c + 12) < 1 && tl.shardRadius(c + 12) > 0);
        assertEquals(0, tl.shardRadius(c + RingTimeline.COLLAPSE), 1e-9);
        assertEquals(0, tl.shardSize(c + RingTimeline.COLLAPSE), 1e-9);
        // Вспышка в конце и ничего после.
        double flash = 0;
        for (long t = c + RingTimeline.CLOSING - RingTimeline.FLASH; t < c + RingTimeline.CLOSING; t++) {
            flash = Math.max(flash, tl.spark(t));
        }
        assertTrue(flash > 2, "вспышка " + flash);
        assertEquals(0, tl.spark(c + RingTimeline.CLOSING), 1e-9);
        assertEquals(0, tl.glow(c + RingTimeline.CLOSING), 1e-9);
    }

    @Test
    void earlyCloseStartsFromCurrentState() {
        final RingTimeline tl = RingTimeline.of(600).closeAt(20);   // на середине формирования
        assertEquals(20, tl.closeStart());
        assertEquals(50, tl.end());
        final double ring = RingTimeline.openingRing(20);
        assertEquals(ring, tl.ring(20), 1e-9);                 // без скачка
        assertTrue(tl.ring(20) < 1);
        assertEquals(0, tl.ring(20 + RingTimeline.COLLAPSE), 1e-9);
        assertEquals(RingPhase.DONE, tl.phase(50));
        assertSame(tl, tl.closeAt(30));
        // Закрыт до появления кольца — точка гаснет, кольца так и нет.
        final RingTimeline early = RingTimeline.of(600).closeAt(4);
        assertEquals(0, early.ring(10), 1e-9);
        assertTrue(early.spark(14) < early.spark(4));
    }

    @Test
    void lifetimeIsClamped() {
        assertEquals(RingTimeline.MIN_LIFETIME, RingTimeline.of(10).lifetimeTicks());
        assertThrows(IllegalArgumentException.class, () -> new RingTimeline(10, 0));
        assertTrue(RingTimeline.of(RingTimeline.MIN_LIFETIME).enterable(RingTimeline.OPENED));
        // Минимальный срок из config.yml (6 с) хватает и кольцу.
        assertTrue(RingTimeline.MIN_LIFETIME <= 120);
    }

    @Test
    void inBackSwingsBelowZero() {
        assertEquals(0, Easing.inBack(0, 1.4), 1e-9);
        assertEquals(1, Easing.inBack(1, 1.4), 1e-9);
        assertTrue(Easing.inBack(0.3, 1.4) < 0);
    }
}
