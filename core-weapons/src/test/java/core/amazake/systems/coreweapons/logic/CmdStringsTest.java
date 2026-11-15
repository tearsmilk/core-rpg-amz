package core.amazake.systems.coreweapons.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class CmdStringsTest {

    @Test
    void frameGoesFirstAndOldFramesLeave() {
        assertEquals(List.of("cw_swing_2", "nexo"), CmdStrings.with(List.of("cw_swing_1", "nexo"), "cw_swing_2"));
        assertEquals(List.of("cw_swing_1"), CmdStrings.with(null, "cw_swing_1"));
    }

    @Test
    void stripKeepsForeignStrings() {
        assertEquals(List.of("a", "b"), CmdStrings.without(List.of("cw_swing_3", "a", "cw_swing_1", "b")));
        assertTrue(CmdStrings.hasFrame(List.of("x", "cw_swing_9")));
        assertFalse(CmdStrings.hasFrame(List.of("x")));
        assertFalse(CmdStrings.hasFrame(null));
    }
}
