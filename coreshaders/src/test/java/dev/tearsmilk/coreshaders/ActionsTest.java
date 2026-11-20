package dev.tearsmilk.coreshaders;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ActionsTest {
    @Test
    void fillReplacesKnownPlaceholders() {
        Map<String, String> v = Map.of("player", "Steve", "world", "world_nether", "x", "10", "y", "64", "z", "-3");
        assertEquals("tp Steve 10 64 -3", Actions.fill("tp {player} {x} {y} {z}", v));
        assertEquals("warp spawn Steve {unknown} {", Actions.fill("warp spawn {PLAYER} {unknown} {", v));
        assertEquals("mv tp Steve world_nether", Actions.fill("mv tp {player} {world}", v));
    }

    @Test
    void actionsEncodeAndDecode() {
        Actions.Action a = new Actions.Action(Actions.Executor.CONSOLE, "warp spawn {player}");
        assertEquals("console:warp spawn {player}", a.encode());
        assertEquals(a, Actions.Action.decode(a.encode()));
        Actions.Action b = Actions.Action.decode("player:/spawn");
        assertEquals(new Actions.Action(Actions.Executor.PLAYER, "spawn"), b);
        assertNull(Actions.Action.decode("op:stop"));
        assertNull(Actions.Action.decode("console:"));
        assertNull(Actions.Action.decode("nothing"));
    }

    @Test
    void executorAndTriggerParse() {
        assertEquals(Actions.Executor.PLAYER, Actions.Executor.parse("Player"));
        assertEquals(Actions.Executor.CONSOLE, Actions.Executor.parse("консоль"));
        assertNull(Actions.Executor.parse("op"));
        assertTrue(Actions.Trigger.parse("both").onEnter());
        assertTrue(Actions.Trigger.parse("both").onClick());
        assertFalse(Actions.Trigger.parse("enter").onClick());
        assertFalse(Actions.Trigger.parse("off").onEnter());
        assertNull(Actions.Trigger.parse("walkin"));
    }

    @Test
    void cooldown() {
        assertEquals(2.5, Actions.parseCooldown("2,5"));
        assertNull(Actions.parseCooldown("-1"));
        assertNull(Actions.parseCooldown("abc"));
        assertTrue(Actions.ready(null, 1000, 2.0));
        assertFalse(Actions.ready(1000L, 2999, 2.0));
        assertTrue(Actions.ready(1000L, 3000, 2.0));
        assertTrue(Actions.ready(1000L, 1000, 0.0));
    }

    @Test
    void groupFlagIsStripped() {
        assertArrayEquals(new String[] {"trigger", "enter"},
            EffectCommands.stripGroup(new String[] {"trigger", "enter", "group"}));
        assertArrayEquals(new String[] {"trigger", "enter"}, EffectCommands.stripGroup(new String[] {"trigger", "enter"}));
        assertTrue(EffectCommands.isGroupWord("-g"));
    }

    @Test
    void dataActive() {
        Actions.Data d = new Actions.Data(List.of(), Actions.Trigger.ENTER, 2.0, null, null, "g");
        assertFalse(d.active());
        d = d.withActions(List.of(new Actions.Action(Actions.Executor.CONSOLE, "say hi")));
        assertTrue(d.active());
        assertFalse(d.withTrigger(Actions.Trigger.OFF).active());
    }
}
