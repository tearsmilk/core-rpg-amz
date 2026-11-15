package core.amazake.systems.corenpc.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.junit.jupiter.api.Test;

class PlaceholdersTest {

    private static final Map<String, String> VARS = Map.of("player", "Steve", "npc", "elder", "evil", "<red>x</red>");

    @Test
    void substitutes() {
        assertEquals("give Steve bread 3", Placeholders.apply("give {player} bread 3", VARS));
        assertEquals("elder:Steve", Placeholders.apply("{npc}:{player}", VARS));
    }

    @Test
    void unknownAndBracesStay() {
        assertEquals("{unknown} {player", Placeholders.apply("{unknown} {player", VARS));
        assertEquals("{} {player}", Placeholders.apply("{} {{player}", VARS));
        assertEquals("%vault_eco_balance%", Placeholders.apply("%vault_eco_balance%", VARS));
    }

    @Test
    void escapesForMiniMessage() {
        final String s = Placeholders.apply("Hi {evil}!", VARS, MiniMessage.miniMessage()::escapeTags);
        assertEquals("Hi \\<red>x\\</red>!", s);
        assertEquals("Hi <red>x</red>!", MiniMessage.miniMessage().stripTags(s).replace("\\", ""));
    }
}
