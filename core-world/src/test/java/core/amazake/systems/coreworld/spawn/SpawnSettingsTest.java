package core.amazake.systems.coreworld.spawn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.entity.EntityType;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.junit.jupiter.api.Test;

class SpawnSettingsTest {

    @Test
    void defaultsBlockNaturalBatsEverywhere() throws Exception {
        final SpawnSettings s = SpawnSettings.parse("", w -> { });
        assertTrue(s.blocks(EntityType.BAT, SpawnReason.NATURAL, "world"));
        assertTrue(s.blocks(EntityType.BAT, SpawnReason.SPAWNER_EGG, "world_nether"));
        assertFalse(s.blocks(EntityType.BAT, SpawnReason.COMMAND, "world"));
        assertFalse(s.blocks(EntityType.ZOMBIE, SpawnReason.NATURAL, "world"));
        assertTrue(s.removes(EntityType.BAT, "world"));
    }

    @Test
    void parsesListsAndWarnsOnBadNames() throws Exception {
        final List<String> warns = new ArrayList<>();
        final SpawnSettings s = SpawnSettings.parse("""
            spawn:
              blocked: [bat, phantom, nope]
              worlds: [world]
              allow-reasons: []
              remove-existing: false
            """, warns::add);
        assertEquals(1, warns.size());
        assertTrue(s.blocks(EntityType.PHANTOM, SpawnReason.COMMAND, "WORLD"));
        assertFalse(s.blocks(EntityType.BAT, SpawnReason.NATURAL, "world_nether"));
        assertFalse(s.removes(EntityType.BAT, "world"));
    }

    @Test
    void emptyBlockedDoesNothing() throws Exception {
        final SpawnSettings s = SpawnSettings.parse("spawn:\n  blocked: []\n", w -> { });
        assertFalse(s.blocks(EntityType.BAT, SpawnReason.NATURAL, "world"));
    }
}
