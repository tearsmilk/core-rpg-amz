package core.amazake.systems.coremechanics.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RdTabTest {

    private static List<String> tab(final String... a) {
        return RdTab.complete(a, List.of("Rezus", "SkT1"), List.of("Rezus", "Steve", "OldGuy"));
    }

    @Test
    void firstArgument() {
        final List<String> all = tab("");
        assertTrue(all.containsAll(List.of("clear", "info", "statue", "summon", "pose", "anim", "frame", "tool", "topple", "up", "turn",
            "move", "remove", "removeall", "list", "poses", "Rezus", "SkT1", "Steve", "OldGuy")));
        assertEquals(1, all.stream().filter("Rezus"::equals).count());
        assertEquals(List.of("statue", "summon", "SkT1", "Steve"), tab("s"));
        assertEquals(List.of("remove", "removeall", "Rezus"), tab("re"));
    }

    @Test
    void subArguments() {
        assertEquals(List.of("tpose", "think"), tab("pose", "t"));
        assertTrue(tab("anim", "").containsAll(List.of("dance", "walk", "stop")));
        assertEquals(List.of("stop"), tab("anim", "st"));
        assertEquals(List.of("add", "clear", "play", "stop", "speed"), tab("frame", ""));
        assertEquals(List.of("Rezus"), tab("statue", "r"));
        assertTrue(tab("statue", "Rezus", "").containsAll(List.of("stand", "dance")));
        assertEquals(List.of("2", "5", "10", "20"), tab("frame", "speed", ""));
        assertEquals(List.of(), tab("clear", ""));
        assertEquals(List.of(), tab("pose", "dab", "x"));
    }
}
