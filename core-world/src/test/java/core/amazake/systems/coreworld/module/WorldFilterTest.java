package core.amazake.systems.coreworld.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorldFilterTest {

    @Test
    void emptyListMeansAllWorlds() {
        final WorldFilter all = WorldFilter.of(List.of());
        assertSame(WorldFilter.ALL, all);
        assertTrue(all.allWorlds());
        assertTrue(all.affects("world"));
        assertTrue(all.affects("world_nether"));
        assertTrue(all.affects("anything"));
        assertEquals("все миры", all.describe());
    }

    @Test
    void onlyListedWorlds() {
        final WorldFilter f = WorldFilter.of(List.of("world", "world_nether"));
        assertFalse(f.allWorlds());
        assertTrue(f.affects("world"));
        assertTrue(f.affects("world_nether"));
        assertFalse(f.affects("world_the_end"));
        assertFalse(f.affects("worl"));
        assertFalse(f.affects(null));
        assertFalse(f.affects(""));
        assertEquals("world, world_nether", f.describe());
    }

    @Test
    void namesIgnoreCaseAndSpaces() {
        final WorldFilter f = WorldFilter.of(List.of("  Spawn ", "RPG_World"));
        assertTrue(f.affects("spawn"));
        assertTrue(f.affects("SPAWN"));
        assertTrue(f.affects("rpg_world"));
        assertTrue(f.affects(" rpg_world "));
        assertFalse(f.affects("world"));
    }

    @Test
    void blankAndNullNamesAreSkipped() {
        assertSame(WorldFilter.ALL, WorldFilter.of(Arrays.asList("", "   ", null)));
        final WorldFilter f = WorldFilter.of(Arrays.asList("", "world", null));
        assertEquals(1, f.worlds().size());
        assertTrue(f.affects("world"));
        assertFalse(f.affects("other"));
    }
}
