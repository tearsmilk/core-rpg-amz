package core.amazake.systems.coremechanics.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import core.amazake.systems.coremechanics.admin.RpgAdminCommand.Op;
import core.amazake.systems.coremechanics.admin.RpgAdminCommand.Parsed;
import java.util.List;
import org.bukkit.GameMode;
import org.junit.jupiter.api.Test;

class RpgAdminTest {

    @Test
    void keysAreStable() {
        // Другие системы читают флаг по этому ключу — менять нельзя.
        assertEquals("coremechanics:rpgadmin", RpgAdmin.FLAG.toString());
        assertEquals("coremechanics:rpgadmin_prev", RpgAdmin.PREVIOUS.toString());
        assertEquals("coremechanics.rpgadmin", RpgAdmin.PERMISSION);
    }

    @Test
    void restoreMode() {
        assertEquals(GameMode.SURVIVAL, RpgAdmin.restoreMode(null));
        assertEquals(GameMode.SURVIVAL, RpgAdmin.restoreMode(""));
        assertEquals(GameMode.SURVIVAL, RpgAdmin.restoreMode("garbage"));
        assertEquals(GameMode.ADVENTURE, RpgAdmin.restoreMode("ADVENTURE"));
        assertEquals(GameMode.SPECTATOR, RpgAdmin.restoreMode(" spectator "));
        assertEquals(GameMode.CREATIVE, RpgAdmin.restoreMode("CREATIVE"));
    }

    @Test
    void parse() {
        assertEquals(new Parsed(Op.TOGGLE, null), RpgAdminCommand.parse(new String[0]));
        assertEquals(new Parsed(Op.ON, null), RpgAdminCommand.parse(new String[] {"on"}));
        assertEquals(new Parsed(Op.OFF, null), RpgAdminCommand.parse(new String[] {"ВЫКЛ"}));
        assertEquals(new Parsed(Op.STATUS, "Rezus"), RpgAdminCommand.parse(new String[] {"status", "Rezus"}));
        assertEquals(new Parsed(Op.TOGGLE, "_TearsMilk_"), RpgAdminCommand.parse(new String[] {"_TearsMilk_"}));
        assertNull(RpgAdminCommand.parse(new String[] {"on", "bad nick!"}));
        assertNull(RpgAdminCommand.parse(new String[] {"какой-то текст"}));
        assertNull(RpgAdminCommand.parse(new String[] {"on", "a", "b"}));
    }

    @Test
    void tabComplete() {
        final List<String> online = List.of("Rezus", "Steve", "Olga");
        assertEquals(List.of("on", "off", "Olga"), RpgAdminCommand.complete(new String[] {"o"}, online));
        assertEquals(List.of("status", "Steve"), RpgAdminCommand.complete(new String[] {"s"}, online));
        assertEquals(List.of("Rezus"), RpgAdminCommand.complete(new String[] {"off", "r"}, online));
        assertEquals(List.of(), RpgAdminCommand.complete(new String[] {"Rezus", ""}, online));
    }
}
