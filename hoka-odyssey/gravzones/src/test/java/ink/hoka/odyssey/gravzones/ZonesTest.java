package ink.hoka.odyssey.gravzones;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ZonesTest {

    private static Zone zone(String name, String map, int x0, int y0, int z0, int x1, int y1, int z1, double g, long c) {
        return new Zone(name, map, x0, y0, z0, x1, y1, z1, g, c);
    }

    @Test
    void containsIncludesWholeMaxBlockAndNormalizesCorners() {
        Zone z = zone("a", "w", 5, 70, 5, 0, 64, 0, 1, 1);  // углы в любом порядке
        assertEquals(0, z.minX());
        assertEquals(70, z.maxY());
        assertTrue(z.contains(0, 64, 0));
        assertTrue(z.contains(5.99, 70.99, 5.99));
        assertFalse(z.contains(6, 65, 3));
        assertFalse(z.contains(-0.01, 65, 3));
        assertFalse(z.contains(3, 63.99, 3));
        assertEquals(6L * 7 * 6, z.volume());
    }

    @Test
    void pointChoosesSmallestThenNewest() {
        Zones zs = new Zones();
        zs.put(zone("big", "w", 0, 0, 0, 20, 20, 20, 0.5, 1));
        zs.put(zone("small", "w", 5, 5, 5, 6, 6, 6, 10, 2));
        zs.put(zone("other", "x", 0, 0, 0, 20, 20, 20, 12, 3));
        assertEquals("big", zs.at("w", 1, 1, 1).name());
        assertEquals("small", zs.at("w", 5.5, 5.5, 5.5).name());     // меньшая внутри большей
        assertEquals("other", zs.at("x", 5.5, 5.5, 5.5).name());
        assertNull(zs.at("w", 30, 1, 1));
        assertNull(zs.at("nope", 1, 1, 1));

        zs.put(zone("twin", "w", 5, 5, 5, 6, 6, 6, 0.1, 4));          // тот же объём — главнее новая
        assertEquals("twin", zs.at("w", 5.5, 5.5, 5.5).name());
        assertTrue(zs.get("w", "twin").beats(zs.get("w", "small")));
        assertEquals(List.of("small", "twin"), zs.overlapping(zs.get("w", "big")).stream().map(Zone::name).toList());
    }

    @Test
    void putReplacesByNameAndRemove() {
        Zones zs = new Zones();
        zs.put(zone("A", "w", 0, 0, 0, 1, 1, 1, 2, zs.nextSeq()));
        Zone a = zs.get("w", "a");
        zs.put(a.withG(0.3));
        assertEquals(1, zs.size());
        assertEquals(0.3, zs.get("w", "A").g());
        assertEquals(a.created(), zs.get("w", "A").created());
        assertEquals(2, zs.nextSeq());
        assertEquals("A", zs.remove("w", "a").name());
        assertNull(zs.remove("w", "a"));
        assertEquals(0, zs.size());
        assertFalse(zs.hasMap("w"));
    }

    @Test
    void intersects() {
        Zone a = zone("a", "w", 0, 0, 0, 4, 4, 4, 1, 1);
        assertTrue(a.intersects(zone("b", "w", 4, 4, 4, 9, 9, 9, 1, 2)));   // общий угловой блок
        assertFalse(a.intersects(zone("c", "w", 5, 0, 0, 9, 4, 4, 1, 3)));
        assertFalse(a.intersects(zone("d", "x", 0, 0, 0, 4, 4, 4, 1, 4)));  // другая карта
    }

    @Test
    void storeRoundTrip() throws Exception {
        List<Zone> in = List.of(
            zone("луна", "galaxy1", -10, 60, -10, 10, 90, 10, 0.1, 1),
            zone("heavy_2", "world", 0, 40, 0, 3, 45, 3, 10, 5),
            zone("выброс", "world", 100, 40, 100, 110, 60, 110, 12.5, 7));
        String json = ZoneStore.toJson(in);
        List<String> warns = new ArrayList<>();
        List<Zone> out = ZoneStore.fromJson(json, warns::add);
        assertEquals(in, out);
        assertTrue(warns.isEmpty());

        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("gz");
        ZoneStore store = new ZoneStore(dir.resolve("sub/zones.json"));
        assertTrue(store.load(warns::add).isEmpty());                   // файла нет — пусто
        store.save(2, json);
        store.save(1, ZoneStore.toJson(List.of()));                     // старый снимок не затирает новый
        assertEquals(in, store.load(warns::add));
    }

    @Test
    void storeSkipsBrokenEntries() {
        String json = "{\"zones\":[{\"name\":\"ok\",\"map\":\"w\",\"min\":[0,0,0],\"max\":[1,1,1],\"g\":2},"
            + "{\"name\":\"bad\",\"map\":\"w\",\"min\":[0,0],\"max\":[1,1,1],\"g\":2},"
            + "{\"name\":\"weak\",\"map\":\"w\",\"min\":[0,0,0],\"max\":[1,1,1],\"g\":0.01}]}";
        List<String> warns = new ArrayList<>();
        List<Zone> out = ZoneStore.fromJson(json, warns::add);
        assertEquals(1, out.size());
        assertEquals("ok", out.getFirst().name());
        assertEquals(1, out.getFirst().created());                      // нет created — по порядку
        assertEquals(2, warns.size());
    }

    @Test
    void zonesReplaceAllKeepsSeq() {
        Zones zs = new Zones();
        zs.replaceAll(List.of(zone("a", "w", 0, 0, 0, 1, 1, 1, 2, 9)));
        assertEquals(10, zs.nextSeq());
    }
}
