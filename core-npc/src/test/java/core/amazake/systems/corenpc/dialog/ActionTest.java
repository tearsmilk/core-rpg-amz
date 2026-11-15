package core.amazake.systems.corenpc.dialog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ActionTest {

    @Test
    void parsesTypes() {
        assertEquals(new Action(Action.Type.CONSOLE, "give {player} bread 3"), Action.parse("console: /give {player} bread 3"));
        assertEquals(new Action(Action.Type.PLAYER, "spawn"), Action.parse("player: spawn"));
        assertEquals(new Action(Action.Type.PLAYER, "warp town"), Action.parse("/warp town"));
        assertEquals(new Action(Action.Type.MESSAGE, "<green>Привет: мир"), Action.parse("message: <green>Привет: мир"));
        assertEquals(new Action(Action.Type.SOUND, "entity.villager.yes 1 1.2"), Action.parse("sound: entity.villager.yes 1 1.2"));
        assertEquals(new Action(Action.Type.GOTO, "other:start"), Action.parse("goto: other:start"));
        assertEquals(new Action(Action.Type.CLOSE, ""), Action.parse("close"));
        assertEquals(new Action(Action.Type.CLOSE, ""), Action.parse("END"));
        assertNull(Action.parse("   "));
    }

    @Test
    void roundTrip() {
        for (final String s : new String[] {"console: say {player}", "player: spawn", "message: <red>x", "close"}) {
            assertEquals(Action.parse(s), Action.parse(Action.parse(s).serialize()));
        }
    }

    @Test
    void consoleArgumentsMustBeOneSafeWord() {
        assertTrue(Actions.safeArgument("Steve_01"));
        assertTrue(Actions.safeArgument("069a79f4-44e9-4726-a5be-fca90e38aaf5"));
        assertTrue(Actions.safeArgument("12.5"));
        assertFalse(Actions.safeArgument("Steve op Steve"));
        assertFalse(Actions.safeArgument("a\nb"));
        assertFalse(Actions.safeArgument("@a"));
        assertFalse(Actions.safeArgument("x\u00a0y"));
        assertFalse(Actions.safeArgument("\u00a7cred"));
        assertTrue(Actions.CONSOLE_VARS.contains("player"));
        assertFalse(Actions.CONSOLE_VARS.contains("player_display"));
    }
}
