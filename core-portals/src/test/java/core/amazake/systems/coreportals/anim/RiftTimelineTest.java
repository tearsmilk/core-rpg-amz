package core.amazake.systems.coreportals.anim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RiftTimelineTest {

    private static final RiftTimeline TL = RiftTimeline.of(600);

    @Test
    void phasesInOrder() {
        assertEquals(RiftPhase.CRACK, TL.phase(0));
        assertEquals(RiftPhase.CRACK, TL.phase(RiftTimeline.CRACK - 1));
        assertEquals(RiftPhase.SPLIT, TL.phase(RiftTimeline.CRACK));
        assertEquals(RiftPhase.TEAR, TL.phase(RiftTimeline.CRACK + RiftTimeline.SPLIT));
        assertEquals(RiftPhase.IDLE, TL.phase(RiftTimeline.OPENED));
        assertEquals(RiftPhase.SEAL, TL.phase(600 - RiftTimeline.CLOSING));
        assertEquals(RiftPhase.SEAL, TL.phase(599));
        assertEquals(RiftPhase.DONE, TL.phase(600));
        assertEquals(600, TL.end());
    }

    @Test
    void enterableOnlyWhenOpen() {
        assertFalse(TL.enterable(RiftTimeline.OPENED - 1));
        assertTrue(TL.enterable(RiftTimeline.OPENED));
        assertTrue(TL.enterable(TL.closeStart() - 1));
        assertFalse(TL.enterable(TL.closeStart()));
    }

    @Test
    void tearGrowsMonotonicallyThroughTheThreeStages() {
        assertEquals(0, TL.tear(0), 1e-9);
        double prev = -1;
        for (long t = 0; t <= RiftTimeline.OPENED; t++) {
            final double v = TL.tear(t);
            assertTrue(v >= prev - 1e-12, "tear падает на " + t);
            assertTrue(v >= 0 && v <= 1, "tear вне 0..1 на " + t);
            prev = v;
        }
        assertEquals(RiftTimeline.P_CRACK, TL.tear(RiftTimeline.CRACK), 1e-9);
        assertEquals(RiftTimeline.P_SPLIT, TL.tear(RiftTimeline.CRACK + RiftTimeline.SPLIT), 1e-9);
        assertEquals(1, TL.tear(RiftTimeline.OPENED), 1e-9);
        assertEquals(1, TL.tear(300), 1e-9);
    }

    @Test
    void sealsBackIntoACrackThenVanishes() {
        final long c = TL.closeStart();
        double prev = 2;
        for (long t = c; t <= TL.end(); t++) {
            final double v = TL.tear(t);
            assertTrue(v <= prev + 1e-12, "tear растёт при закрытии на " + t);
            prev = v;
        }
        assertEquals(1, TL.tear(c), 1e-9);
        // К концу SEAL края сошлись: снова трещина во всю длину.
        assertEquals(RiftTimeline.P_CRACK, TL.tear(c + RiftTimeline.SEAL), 1e-9);
        assertEquals(0, TL.tear(TL.end()), 1e-9);
        assertEquals(0, TL.openness(c + RiftTimeline.SEAL), 1e-9);
    }

    @Test
    void opennessIsZeroWhileACrack() {
        assertEquals(0, TL.openness(RiftTimeline.CRACK), 1e-9);
        assertEquals(0, TL.openness(RiftTimeline.CRACK + RiftTimeline.SPLIT), 1e-9);
        assertTrue(TL.openness(RiftTimeline.CRACK + RiftTimeline.SPLIT + 10) > 0.3);
        assertEquals(1, TL.openness(RiftTimeline.OPENED), 1e-9);
    }

    @Test
    void screenCracksOnlyWhileOpening() {
        assertTrue(TL.screenCracks(0) > 0);
        assertEquals(1, TL.screenCracks(RiftTimeline.OPENED - 1), 1e-9);
        assertEquals(0, TL.screenCracks(RiftTimeline.OPENED));
        assertEquals(0, TL.screenCracks(TL.closeStart() + 1));
        for (long t = 1; t < RiftTimeline.OPENED; t++) {
            assertTrue(TL.screenCracks(t) > TL.screenCracks(t - 1));
        }
        // Закрыли на середине раскрытия — трещины на экране сразу пропадают.
        final RiftTimeline early = TL.closeAt(20);
        assertEquals(0, early.screenCracks(25));
    }

    @Test
    void earlyCloseStartsFromCurrentLook() {
        final RiftTimeline early = TL.closeAt(36);
        assertEquals(36, early.closeStart());
        assertEquals(TL.tear(36), early.tear(36), 1e-9);
        assertTrue(early.tear(37) <= early.tear(36));
        assertEquals(76, early.end());
        // Повторный closeAt позже — не сдвигает.
        assertEquals(early, early.closeAt(50));
        // Закрыли, пока это была трещина: она просто гаснет.
        final RiftTimeline crack = TL.closeAt(6);
        assertEquals(TL.tear(6), crack.tear(6 + RiftTimeline.SEAL - 1), 1e-9);
        assertEquals(0, crack.tear(crack.end()), 1e-9);
    }

    @Test
    void flashAtTheVeryEnd() {
        final long c = TL.closeStart();
        assertEquals(0, TL.flash(c));
        assertEquals(0, TL.flash(c + RiftTimeline.CLOSING - 8));
        assertTrue(TL.flash(c + RiftTimeline.CLOSING - 6) > 0.5);
        assertEquals(0, TL.flash(TL.end()));
    }

    @Test
    void shortLifetimeIsClampedAndInvalidRejected() {
        assertEquals(RiftTimeline.MIN_LIFETIME, RiftTimeline.of(10).lifetimeTicks());
        assertThrows(IllegalArgumentException.class, () -> new RiftTimeline(10, 0));
        assertThrows(IllegalArgumentException.class, () -> new RiftTimeline(600, -1));
    }
}
