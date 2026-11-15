package core.amazake.systems.corenpc.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import core.amazake.systems.corenpc.dialog.Action;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NpcStoreTest {

    @Test
    void roundTripAtomic(@TempDir final Path dir) throws Exception {
        final NpcDef a = NpcDef.create("elder", "mannequin", "world", 10.5, 64, -3.25, 90f, 5f)
            .withName("<gold>Староста")
            .withSkin(new NpcDef.Skin("Notch", "dGV4", "c2ln"))
            .withModel(new NpcDef.ModelSpec("elder_model", 1.5, "idle", "wave"))
            .withDialog("example")
            .withCommands(List.of(Action.parse("console: say hi {player}"), Action.parse("player: spawn")))
            .withOption("cooldown", 2.5)
            .withOption("hologram", "display")
            .withOption("lines", List.of("<gray>староста", "строка 2"))
            .withOption("left-click", true)
            .withOption("look-at", false);
        final NpcDef b = NpcDef.create("guard", "zombie", "world_nether", 0, 70, 0, 0, 0);
        final Path f = dir.resolve("npcs.yml");
        NpcStore.save(f, List.of(a, b));
        assertFalse(Files.exists(dir.resolve("npcs.yml.tmp")));
        final List<String> warns = new ArrayList<>();
        final Map<String, NpcDef> back = NpcStore.load(f, warns::add);
        assertTrue(warns.isEmpty(), warns.toString());
        assertEquals(a, back.get("elder"));
        assertEquals(b, back.get("guard"));
    }

    @Test
    void brokenEntriesAreSkipped(@TempDir final Path dir) throws Exception {
        final Path f = dir.resolve("npcs.yml");
        Files.writeString(f, "npcs:\n  a: 5\n  b:\n    type: villager\n  c:\n    location: {world: w, x: 1, y: 2, z: 3}\n");
        final List<String> warns = new ArrayList<>();
        final Map<String, NpcDef> m = NpcStore.load(f, warns::add);
        assertEquals(1, m.size());
        assertEquals(2, warns.size());
        assertEquals("mannequin", m.get("c").type());
        assertTrue(m.get("c").lookAt());
    }

    @Test
    void chunkKeys() {
        final NpcDef d = NpcDef.create("x", "mannequin", "w", -0.5, 0, 33, 0, 0);
        assertEquals(NpcDef.chunkKey(-1, 2), d.chunkKey());
    }
}
