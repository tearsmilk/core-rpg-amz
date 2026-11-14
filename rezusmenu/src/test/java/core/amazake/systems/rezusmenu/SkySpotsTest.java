package core.amazake.systems.rezusmenu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SkySpotsTest {

    @Test
    void neighboursGetDifferentCellsFarApart() {
        final SkySpots s = new SkySpots();
        final Set<Long> seen = new HashSet<>();
        for (int i = 0; i < 30; i++) {
            final long c = s.claim(UUID.randomUUID(), "world", 10.3, -5.2);
            assertTrue(seen.add(c), "cell reused");
        }
        final long a = s.claim(UUID.randomUUID(), "world", 1000, 1000);
        final long b = s.claim(UUID.randomUUID(), "world", 1000, 1000);
        final double d = Math.hypot(SkySpots.centerX(a) - SkySpots.centerX(b), SkySpots.centerZ(a) - SkySpots.centerZ(b));
        assertTrue(d >= SkySpots.CELL);
    }

    @Test
    void ownCellWhenFreeAndReleaseFreesIt() {
        final SkySpots s = new SkySpots();
        final UUID p = UUID.randomUUID();
        final long c = s.claim(p, "world", -3, 50);
        assertEquals(-1, SkySpots.cellX(c));
        assertEquals(2, SkySpots.cellZ(c));
        final UUID q = UUID.randomUUID();
        assertNotEquals(c, s.claim(q, "world", -3, 50));
        s.release(p);
        assertEquals(c, s.claim(UUID.randomUUID(), "world", -3, 50));
    }

    @Test
    void otherWorldDoesNotCollide() {
        final SkySpots s = new SkySpots();
        final long a = s.claim(UUID.randomUUID(), "world", 0, 0);
        final long b = s.claim(UUID.randomUUID(), "world_nether", 0, 0);
        assertEquals(a, b);
    }
}
