package core.amazake.systems.coreweapons.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FramesTest {

    @Test
    void parsesAndSteps() {
        final List<String> warns = new ArrayList<>();
        final Frames f = Frames.parse(List.of("cw_swing_1:1", "cw_swing_2:2", "cw_swing_3", "bad:3"), warns::add);
        assertEquals(List.of("cw_swing_1", "cw_swing_2", "cw_swing_3"), f.names());
        assertEquals(1, warns.size());
        assertEquals(5, f.total());
        assertEquals("cw_swing_1", f.at(0));
        assertEquals("cw_swing_2", f.at(1));
        assertEquals("cw_swing_2", f.at(2));
        assertEquals("cw_swing_3", f.at(3));
        assertEquals("cw_swing_3", f.at(4));
        assertNull(f.at(5));
        assertNull(f.at(-1));
    }

    @Test
    void badTicksFallBackAndClamp() {
        final Frames f = Frames.parse(List.of("cw_swing_a:x", "cw_swing_b:99", "cw_swing_c:0"), m -> { });
        assertEquals(List.of(2, 20, 1), f.ticks());
    }
}
