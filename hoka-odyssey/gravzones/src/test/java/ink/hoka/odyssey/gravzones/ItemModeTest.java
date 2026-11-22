package ink.hoka.odyssey.gravzones;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import ink.hoka.odyssey.gravity.VacuumService.Mode;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Что gravzones отвечает модулю gravity про предметы. */
class ItemModeTest {

    private static Zone zone(String name, int x0, int y0, int z0, int x1, int y1, int z1, double g, long c) {
        return new Zone(name, "galaxy1", x0, y0, z0, x1, y1, z1, g, c);
    }

    @Test
    void modeByZoneStrength() {
        assertEquals(Mode.MAP, GravZonesModule.itemMode(null));
        assertEquals(Mode.NORMAL, GravZonesModule.itemMode(zone("a", 0, 0, 0, 1, 1, 1, 0.1, 1)));
        assertEquals(Mode.NORMAL, GravZonesModule.itemMode(zone("a", 0, 0, 0, 1, 1, 1, 1, 1)));
        assertEquals(Mode.NORMAL, GravZonesModule.itemMode(zone("a", 0, 0, 0, 1, 1, 1, 10, 1)));
        assertEquals(Mode.VACUUM, GravZonesModule.itemMode(zone("a", 0, 0, 0, 1, 1, 1, 11, 1)));
        assertEquals(Mode.VACUUM, GravZonesModule.itemMode(zone("a", 0, 0, 0, 1, 1, 1, 13, 1)));
    }

    @Test
    void listLookupMatchesMapLookup() {
        Zones zs = new Zones();
        Zone big = zone("big", -10, 60, -10, 10, 80, 10, 1, 1);
        Zone vac = zone("vac", -2, 64, -2, 2, 68, 2, 12, 2);    // вакуумная внутри обычной — меньшая главнее
        zs.put(big);
        zs.put(vac);
        List<Zone> list = zs.on("galaxy1");
        double[][] pts = {{0, 65, 0}, {5, 65, 5}, {20, 65, 0}, {0.5, 69.5, 0.5}, {-10, 60, -10}, {11, 60, 0}};
        for (double[] p : pts) {
            assertSame(zs.at("galaxy1", p[0], p[1], p[2]), Zones.at(list, p[0], p[1], p[2]));
        }
        assertEquals(Mode.VACUUM, GravZonesModule.itemMode(Zones.at(list, 0, 65, 0)));
        assertEquals(Mode.NORMAL, GravZonesModule.itemMode(Zones.at(list, 5, 65, 5)));
        assertEquals(Mode.MAP, GravZonesModule.itemMode(Zones.at(list, 20, 65, 0)));
        assertNull(Zones.at(List.of(), 0, 0, 0));
    }
}
