package core.amazake.systems.coreportals.anim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GateTimelineTest {

    @Test
    void stagesAndEntry() {
        final GateTimeline tl = GateTimeline.of(900);
        assertEquals(900 - GateTimeline.CLOSING, tl.closeStart());
        assertEquals("MATERIALIZE", tl.stage(0));
        assertEquals("BLOOM", tl.stage(40));
        assertEquals("OPEN", tl.stage(GateTimeline.OPENED));
        assertEquals("COLLAPSE", tl.stage(tl.closeStart()));
        assertEquals("DONE", tl.stage(900));
        assertFalse(tl.enterable(GateTimeline.OPENED - 1));
        assertTrue(tl.enterable(GateTimeline.OPENED));
        assertFalse(tl.enterable(tl.closeStart()));
        assertTrue(tl.done(900));
        assertFalse(tl.done(899));
    }

    @Test
    void shortLifetimeStillOpensFully() {
        // Не раньше 120-го тика: врата успевают раскрыться.
        assertEquals(120, GateTimeline.of(10).closeStart());
    }

    @Test
    void curves() {
        final GateTimeline tl = GateTimeline.of(900);
        assertEquals(0.0, tl.reveal(0), 1e-12);
        assertEquals(1.0, tl.reveal(40), 1e-12);
        assertEquals(0.0, tl.aperture(24), 1e-12);
        assertEquals(1.0, tl.aperture(GateTimeline.OPENED), 1e-12);
        // Закрытие: кольцо сворачивается за 58 тиков, рама держится 28 тиков и уходит к концу закрытия.
        final long c = tl.closeStart();
        assertEquals(1.0, tl.reveal(c + 28), 1e-12);
        assertEquals(0.0, tl.aperture(c + 58), 1e-12);
        assertEquals(0.0, tl.reveal(c + GateTimeline.CLOSING), 1e-12);
    }

    @Test
    void earlyCloseContinuesFromCurrentSize() {
        final GateTimeline tl = GateTimeline.of(900).closeAt(60);
        assertEquals(60, tl.closeStart());
        final double atClose = GateTimeline.of(900).aperture(60);
        assertEquals(atClose, tl.aperture(60), 1e-12);
        assertTrue(atClose > 0 && atClose < 1);
        // Закрытие, которое уже идёт, не сдвигается.
        assertEquals(tl, tl.closeAt(80));
    }
}
