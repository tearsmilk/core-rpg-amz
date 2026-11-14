package core.amazake.systems.coremobs.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import core.amazake.systems.coremobs.geo.BlockPos;
import core.amazake.systems.coremobs.util.IntRange;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class RegionTest {

    private static final List<BlockPos> POINTS = List.of(new BlockPos(0, 64, 0), new BlockPos(30, 66, 0),
        new BlockPos(30, 63, 30), new BlockPos(0, 65, 30));

    @Test
    void heightFromPoints() {
        final Region r = Region.fromPoints("forest", "world", POINTS, 5, 5);
        assertEquals(63, r.minY());
        assertEquals(71, r.maxY());
        assertTrue(r.contains(10, 64, 10));
        assertFalse(r.contains(10, 80, 10));
        assertEquals(0.0, r.distanceOutside(10, 65, 10), 1e-9);
        assertEquals(9.0, r.distanceOutside(10, 81, 10), 1e-9);
        assertEquals(6.0, r.distanceOutside(37.0, 65, 10), 1e-9);
    }

    @Test
    void yamlRoundTrip() throws Exception {
        final Region r = Region.fromPoints("forest", "world", POINTS, 5, 7)
            .withSpawn(SpawnSettings.DEFAULTS.withMobs(Map.of("forest_zombie", 60)).withAmount(new IntRange(1, 3))
                .withMaxAlive(12).withInterval(8).withPlayerRadius(40).withMinPlayerDistance(4).withEnabled(false));
        final String text = RegionCodec.write(List.of(r)).saveToString();
        final YamlConfiguration back = new YamlConfiguration();
        back.loadFromString(text);
        final List<String> warnings = new ArrayList<>();
        final Region read = RegionCodec.read(back, warnings::add).get("forest");
        assertTrue(warnings.isEmpty(), warnings.toString());
        assertEquals(r.world(), read.world());
        assertEquals(r.polygon().vertices(), read.polygon().vertices());
        assertEquals(r.minY(), read.minY());
        assertEquals(r.maxY(), read.maxY());
        assertEquals(7, read.despawnRadius());
        assertEquals(r.spawn(), read.spawn());
    }

    @Test
    void badRegionsAreSkippedWithWarning() throws Exception {
        final YamlConfiguration y = new YamlConfiguration();
        y.loadFromString("""
            regions:
              two-points:
                world: world
                points: ["0,64,0", "5,64,5"]
              no-world:
                points: ["0,64,0", "5,64,5", "5,64,0"]
              ok:
                world: world
                points: ["0,64,0", "5,64,5", "5,64,0"]
                spawn:
                  amount: "0-200"
                  mobs: {a: 0, b: 3}
            """);
        final List<String> warnings = new ArrayList<>();
        final Map<String, Region> map = RegionCodec.read(y, warnings::add);
        assertEquals(List.of("ok"), List.copyOf(map.keySet()));
        assertEquals(Map.of("b", 3), map.get("ok").spawn().mobs());
        assertEquals(IntRange.of(1), map.get("ok").spawn().amount());
        assertEquals(4, warnings.size(), warnings.toString());
    }

    @Test
    void selections() {
        final Selections s = new Selections();
        final UUID id = UUID.randomUUID();
        assertEquals(Selections.AddResult.ADDED, s.add(id, "world", new BlockPos(0, 64, 0), 3));
        assertEquals(Selections.AddResult.SAME_AS_LAST, s.add(id, "world", new BlockPos(0, 64, 0), 3));
        s.add(id, "world", new BlockPos(5, 64, 0), 3);
        s.add(id, "world", new BlockPos(5, 64, 5), 3);
        assertEquals(Selections.AddResult.LIMIT, s.add(id, "world", new BlockPos(9, 64, 9), 3));
        assertEquals(new BlockPos(5, 64, 5), s.undo(id));
        assertEquals(2, s.get(id).points().size());
        assertEquals(Selections.AddResult.NEW_WORLD, s.add(id, "nether", new BlockPos(1, 1, 1), 3));
        assertEquals(1, s.get(id).points().size());
        s.undo(id);
        assertNull(s.get(id));
    }
}
