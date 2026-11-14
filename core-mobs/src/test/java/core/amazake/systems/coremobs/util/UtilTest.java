package core.amazake.systems.coremobs.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class UtilTest {

    @Test
    void intRange() {
        assertEquals(new IntRange(3, 3), IntRange.parse("3"));
        assertEquals(new IntRange(1, 4), IntRange.parse("1-4"));
        assertEquals(new IntRange(1, 4), IntRange.parse("4-1"));
        assertEquals(new IntRange(2, 5), IntRange.parse("2..5"));
        assertNull(IntRange.parse("x"));
        assertEquals("1-4", new IntRange(1, 4).format());
        assertEquals("3", IntRange.of(3).format());
        final Random r = new Random(1);
        for (int i = 0; i < 200; i++) {
            final int v = new IntRange(1, 4).roll(r);
            assertTrue(v >= 1 && v <= 4);
        }
    }

    @Test
    void weightedFollowsWeights() {
        final Map<String, Integer> w = new LinkedHashMap<>();
        w.put("a", 75);
        w.put("b", 25);
        w.put("c", 0);
        final Map<String, Integer> hits = new HashMap<>();
        final Random r = new Random(7);
        for (int i = 0; i < 20000; i++) {
            hits.merge(Weighted.pick(w, r), 1, Integer::sum);
        }
        assertNull(hits.get("c"));
        assertEquals(0.75, hits.get("a") / 20000.0, 0.02);
        assertNull(Weighted.pick(Map.of(), r));
    }
}
