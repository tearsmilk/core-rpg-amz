package ink.hoka.odyssey.gravity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ink.hoka.odyssey.gravity.VacuumService.Mode;
import ink.hoka.odyssey.gravity.VacuumService.ZoneLookup;
import ink.hoka.odyssey.gravity.VacuumService.ZoneProvider;
import java.util.concurrent.ConcurrentHashMap;
import net.minestom.server.collision.Aerodynamics;
import org.junit.jupiter.api.Test;

/** Режимы предметов с источником зон (gravzones) и отпускание предмета из вакуума. */
class ZoneProviderTest {

    @Test
    void itemVacuumOnlyOnVacuumMapAndNotInNormalZone() {
        assertTrue(VacuumService.itemInVacuum(true, Mode.MAP));
        assertTrue(VacuumService.itemInVacuum(true, Mode.VACUUM));
        assertFalse(VacuumService.itemInVacuum(true, Mode.NORMAL));    // зона 0.1…10 главнее вакуумной карты
        assertFalse(VacuumService.itemInVacuum(false, Mode.MAP));
        assertFalse(VacuumService.itemInVacuum(false, Mode.VACUUM));   // на обычной карте предметы — как раньше
        assertFalse(VacuumService.itemInVacuum(false, Mode.NORMAL));
    }

    @Test
    void modeAtWithoutZonesIsMap() {
        assertEquals(Mode.MAP, VacuumOverrides.modeAt(null, 0, 64, 0));
        assertEquals(Mode.MAP, VacuumOverrides.modeAt((x, y, z) -> null, 0, 64, 0));
        ZoneLookup box = (x, y, z) -> x >= 0 && x < 10 ? Mode.NORMAL : Mode.MAP;
        assertEquals(Mode.NORMAL, VacuumOverrides.modeAt(box, 5, 64, 0));
        assertEquals(Mode.MAP, VacuumOverrides.modeAt(box, 15, 64, 0));
    }

    @Test
    void providerRemovedOnlyByOwnerAndOnClear() {
        VacuumOverrides o = new VacuumOverrides(new ConcurrentHashMap<>());
        ZoneLookup normal = (x, y, z) -> Mode.NORMAL;
        ZoneProvider a = inst -> normal;
        ZoneProvider b = inst -> null;
        assertNull(o.zones(null));
        o.zoneProvider(a);
        assertSame(a, o.zoneProvider());
        assertNull(o.zones(null));                                     // нет карты — нет зон
        o.removeZoneProvider(b);                                       // чужой не снимает
        assertSame(a, o.zoneProvider());
        o.zoneProvider(b);                                             // новый заменяет прежний
        o.removeZoneProvider(a);                                       // прежний владелец не снимает нового
        assertSame(b, o.zoneProvider());
        o.removeZoneProvider(b);
        assertNull(o.zoneProvider());
        o.zoneProvider(a);
        o.clear();                                                     // gravity выключается
        assertNull(o.zoneProvider());
    }

    @Test
    void releaseForwardedToCurrentProvider() {
        VacuumOverrides o = new VacuumOverrides(new ConcurrentHashMap<>());
        o.released(null);                                              // источника нет — ничего
        int[] calls = {0};
        ZoneProvider p = new ZoneProvider() {
            @Override
            public ZoneLookup zones(net.minestom.server.instance.Instance instance) {
                return null;
            }

            @Override
            public void released(net.minestom.server.entity.ItemEntity item) {
                calls[0]++;
            }
        };
        o.zoneProvider(inst -> null);
        o.released(null);                                              // по умолчанию released — пустой
        o.zoneProvider(p);
        o.released(null);
        assertEquals(1, calls[0]);
        o.removeZoneProvider(p);
        o.released(null);
        assertEquals(1, calls[0]);
    }

    @Test
    void releasedKeepsCurrentGravityAndOriginalDrag() {
        Aerodynamics original = new Aerodynamics(0.04, 0.98, 0.98);
        Aerodynamics captured = new Aerodynamics(0.04 * 3, 1.0, 0.98);  // gravzones поменял гравитацию, пока висел
        Aerodynamics r = GravityModule.released(captured, original);
        assertEquals(0.12, r.gravity(), 1e-12);
        assertEquals(0.98, r.horizontalAirResistance(), 1e-12);
        assertEquals(0.98, r.verticalAirResistance(), 1e-12);
    }
}
