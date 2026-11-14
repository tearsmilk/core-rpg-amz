package core.amazake.systems.coreskyrift.anim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import core.amazake.systems.coreskyrift.Markers;
import core.amazake.systems.coreskyrift.Settings;
import org.junit.jupiter.api.Test;

class TimelineTest {

    private final Timeline tl = Timeline.of(Settings.DEFAULTS.times());

    @Test
    void defaultStagesAndTotal() {
        assertEquals(60, this.tl.crackStart());
        assertEquals(120, this.tl.openingStart());
        assertEquals(520, this.tl.activeStart());
        assertEquals(1720, this.tl.closeTick());
        assertEquals(1840, this.tl.fogOutStart());
        assertEquals(1920, this.tl.total());   // 96 s
        assertEquals(Timeline.Stage.FOG_IN, this.tl.stage(0));
        assertEquals(Timeline.Stage.CRACK, this.tl.stage(60));
        assertEquals(Timeline.Stage.OPENING, this.tl.stage(300));
        assertEquals(Timeline.Stage.ACTIVE, this.tl.stage(1000));
        assertEquals(Timeline.Stage.CLOSING, this.tl.stage(1800));
        assertEquals(Timeline.Stage.FOG_OUT, this.tl.stage(1900));
        assertEquals(Timeline.Stage.DONE, this.tl.stage(1920));
        assertTrue(this.tl.done(1920));
        assertFalse(this.tl.done(1919));
    }

    @Test
    void fogRisesHoldsAndFades() {
        assertEquals(0, this.tl.fog(0), 1e-9);
        assertTrue(this.tl.fog(20) > 0.5, "outCubic: more than half after a third");
        assertEquals(1, this.tl.fog(60), 1e-9);
        assertEquals(1, this.tl.fog(1839), 1e-9);
        assertTrue(this.tl.fog(1880) < 1 && this.tl.fog(1880) > 0);
        assertEquals(0, this.tl.fog(1920), 1e-9);
        for (long t = 1; t < 60; t++) {
            assertTrue(this.tl.fog(t) >= this.tl.fog(t - 1));
        }
        for (long t = 1841; t <= 1920; t++) {
            assertTrue(this.tl.fog(t) <= this.tl.fog(t - 1));
        }
    }

    @Test
    void riftCrackThenMonotoneOpening() {
        assertEquals(0, this.tl.rift(59), 1e-9);
        assertEquals(Timeline.CRACK_END, this.tl.rift(120), 1e-9);
        for (long t = 61; t <= 520; t++) {
            assertTrue(this.tl.rift(t) >= this.tl.rift(t - 1) - 1e-12, "rift never shrinks while opening, t=" + t);
        }
        assertEquals(1, this.tl.rift(520), 1e-9);
        assertEquals(1, this.tl.rift(1700), 1e-9);
    }

    @Test
    void closingGoesBackToCrackThenNothing() {
        assertEquals(1, this.tl.rift(1720), 1e-9);
        assertEquals(Timeline.CRACK_END, this.tl.rift(1720 + 72), 1e-9);   // 60 % of 120
        assertEquals(0, this.tl.rift(1840), 1e-9);
        for (long t = 1721; t <= 1840; t++) {
            assertTrue(this.tl.rift(t) <= this.tl.rift(t - 1) + 1e-12);
        }
    }

    @Test
    void earlyCloseStartsFromCurrentLook() {
        final double before = this.tl.rift(300);
        final double fogBefore = this.tl.fog(300);
        final Timeline c = this.tl.closeAt(300);
        assertEquals(300, c.closeTick());
        assertEquals(before, c.rift(300), 1e-9);
        assertEquals(fogBefore, c.fog(300), 1e-9);
        assertEquals(300 + 120 + 80, c.total());
        assertTrue(c.rift(310) < before);
        assertSame(c, c.closeAt(400), "already closing: unchanged");
        // closing during fog-in: fog fades from where it was
        final Timeline f = this.tl.closeAt(10);
        assertEquals(this.tl.fog(10), f.fog(10 + 120), 1e-9);
        assertEquals(0, f.rift(50), 1e-9);
    }

    @Test
    void wavesOnlyWhileOpenEnough() {
        assertFalse(this.tl.wavesActive(100, 0.6));
        int first = -1;
        for (int t = 0; t < 2000; t++) {
            if (this.tl.wavesActive(t, 0.6)) {
                first = t;
                break;
            }
        }
        assertTrue(first > 120 && first < 520, "first wave during opening, at 60 %: " + first);
        assertTrue(this.tl.rift(first) >= 0.6);
        assertTrue(this.tl.wavesActive(1000, 0.6));
        assertFalse(this.tl.wavesActive(1720, 0.6), "no waves while closing");
        assertFalse(this.tl.closeAt(800).wavesActive(900, 0.6));
    }

    @Test
    void cuesAndFlash() {
        assertEquals(Timeline.Cue.CAST, this.tl.cue(0));
        assertEquals(Timeline.Cue.CRACK, this.tl.cue(60));
        assertEquals(Timeline.Cue.RUMBLE, this.tl.cue(180));
        assertEquals(Timeline.Cue.OPEN, this.tl.cue(520));
        assertEquals(Timeline.Cue.CLOSE, this.tl.cue(1720));
        assertEquals(Timeline.Cue.FADE, this.tl.cue(1840));
        assertNull(this.tl.cue(1000));
        assertEquals(0, this.tl.flash(59), 1e-9);
        assertTrue(this.tl.flash(63) > 0.8);
        assertEquals(0, this.tl.flash(200), 1e-9);
        assertTrue(this.tl.flash(1720 + 108 + 3) > 0.3, "snap-shut flash");
    }

    @Test
    void bytesAndRemaining() {
        assertEquals(0, Markers.paramByte(this.tl.rift(0)));
        assertEquals(50, Markers.paramByte(this.tl.rift(120)));
        assertEquals(250, Markers.paramByte(this.tl.rift(600)));
        assertEquals(1, this.tl.remaining(0), 1e-9);
        assertEquals(0.5, this.tl.remaining(960), 1e-9);
        assertEquals(0, this.tl.remaining(5000), 1e-9);
    }
}
