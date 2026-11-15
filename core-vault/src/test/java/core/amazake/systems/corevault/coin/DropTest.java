package core.amazake.systems.corevault.coin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class DropTest {

    @Test
    void ranges() {
        assertArrayEquals(new int[] {2, 2}, DropRule.parseRange("2"));
        assertArrayEquals(new int[] {1, 3}, DropRule.parseRange(" 1 - 3 "));
        assertThrows(IllegalArgumentException.class, () -> DropRule.parseRange("3-1"));
        assertThrows(IllegalArgumentException.class, () -> DropRule.parseRange("x"));
        assertThrows(IllegalArgumentException.class, () -> DropRule.parseRange("-1"));
    }

    @Test
    void rollRespectsChanceAndRange() {
        final SplittableRandom r = new SplittableRandom(42);
        final DropRule always = new DropRule(100, 1, 3, 0);
        final DropRule never = new DropRule(0, 1, 3, 0);
        final DropRule half = new DropRule(50, 2, 2, 0);
        int hits = 0;
        for (int i = 0; i < 10_000; i++) {
            final int a = always.roll(r);
            assertTrue(a >= 1 && a <= 3);
            assertEquals(0, never.roll(r));
            final int h = half.roll(r);
            assertTrue(h == 0 || h == 2);
            if (h > 0) {
                hits++;
            }
        }
        assertTrue(hits > 4700 && hits < 5300, "hits " + hits);
        assertEquals(0, DropRule.NONE.roll(r));
    }

    @Test
    void tableOrder() {
        final MobTable<String> t = new MobTable<>(Map.of("forest_zombie", "core"), Map.of("ZOMBIE", "type"), "hostile", "any");
        assertEquals("core", t.find("Forest_Zombie", "ZOMBIE", true));
        assertEquals("type", t.find("other", "zombie", true));
        assertEquals("hostile", t.find(null, "SKELETON", true));
        assertEquals("any", t.find(null, "COW", false));
        assertNull(MobTable.<String>empty().find(null, "COW", true));
    }
}
