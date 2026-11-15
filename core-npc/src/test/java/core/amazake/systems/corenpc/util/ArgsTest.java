package core.amazake.systems.corenpc.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ArgsTest {

    @Test
    void joinTail() {
        assertEquals("give {player} bread 3", Args.join(new String[] {"command", "add", "x", "console", "give", "{player}", "bread", "3"}, 4));
        assertEquals("", Args.join(new String[] {"a"}, 1));
        assertEquals("b c", Args.join(new String[] {"a", "b", "c"}, 1));
    }

    @Test
    void tokenizeQuotes() {
        assertEquals(List.of("rename", "elder", "<gold>Староста Ведан"), Args.tokenize("rename elder \"<gold>Староста Ведан\""));
        assertEquals(List.of("a", "", "b"), Args.tokenize("a \"\" b"));
        assertEquals(List.of("say \"hi\""), Args.tokenize("\"say \\\"hi\\\"\""));
        assertEquals(List.of("x", "unclosed tail"), Args.tokenize("x \"unclosed tail"));
        assertEquals(List.of(), Args.tokenize("   "));
    }

    @Test
    void numbersAndBools() {
        assertEquals(1.5, Args.number("1,5"));
        assertNull(Args.number("abc"));
        assertNull(Args.number("NaN"));
        assertEquals(Boolean.TRUE, Args.bool("да"));
        assertEquals(Boolean.FALSE, Args.bool("off"));
        assertNull(Args.bool("maybe"));
    }

    @Test
    void durations() {
        assertEquals(5.0, Args.seconds("5"));
        assertEquals(2.5, Args.seconds("2.5s"));
        assertEquals(90.0, Args.seconds("1m30s"));
        assertEquals(0.5, Args.seconds("500ms"));
        assertEquals(3600.0, Args.seconds("1h"));
        assertNull(Args.seconds("-1"));
        assertNull(Args.seconds("5x"));
        assertNull(Args.seconds("m"));
    }

    @Test
    void ids() {
        assertTrue(Args.validId("elder_1"));
        assertTrue(Args.validId("quest.npc-2"));
        assertFalse(Args.validId("староста"));
        assertFalse(Args.validId("a b"));
        assertFalse(Args.validId(""));
    }
}
