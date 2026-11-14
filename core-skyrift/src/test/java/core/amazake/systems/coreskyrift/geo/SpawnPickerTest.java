package core.amazake.systems.coreskyrift.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class SpawnPickerTest {

    @Test
    void nearPlayerRingAndRiftFirst() {
        final RiftBand b = RiftBand.of(1.0, 0);
        final RandomGenerator rnd = SpawnPicker.seeded(7);
        // player standing at the edge of the band: some candidates fall under the rift, they come first
        final List<double[]> c = SpawnPicker.nearPlayer(100, 0, b, 1.0, 6, 24, rnd, 40);
        assertEquals(40, c.size());
        boolean seenOutside = false;
        int inside = 0;
        for (final double[] p : c) {
            final double d = Math.hypot(p[0] - 100, p[1]);
            assertTrue(d >= 6 - 1e-9 && d <= 24 + 1e-9, "ring " + d);
            final boolean in = b.contains(p[0], p[1], 1.0, SpawnPicker.INSET);
            if (in) {
                inside++;
                assertTrue(!seenOutside, "inside candidates first");
            } else {
                seenOutside = true;
            }
        }
        assertTrue(inside > 0 && inside < 40);
    }

    @Test
    void underRiftOnlyInsideAndInRadius() {
        final RiftBand b = RiftBand.of(1.0, 45);
        final List<double[]> pts = SpawnPicker.underRift(b, 1.0, 160, SpawnPicker.seeded(3), 50);
        assertEquals(50, pts.size());
        for (final double[] p : pts) {
            assertTrue(b.contains(p[0], p[1], 1.0, 0.999));
            assertTrue(Math.hypot(p[0], p[1]) <= 160);
        }
        assertTrue(SpawnPicker.underRift(b, 0.15, 160, SpawnPicker.seeded(3), 5).isEmpty(), "still a crack");
    }

    @Test
    void weightedAndBetween() {
        final RandomGenerator rnd = SpawnPicker.seeded(11);
        final int[] counts = new int[3];
        for (int i = 0; i < 6000; i++) {
            counts[SpawnPicker.weighted(new int[] {3, 2, 1}, rnd)]++;
        }
        assertTrue(counts[0] > counts[1] && counts[1] > counts[2], java.util.Arrays.toString(counts));
        assertEquals(-1, SpawnPicker.weighted(new int[] {0, 0}, rnd));
        for (int i = 0; i < 100; i++) {
            final int n = SpawnPicker.between(2, 4, rnd);
            assertTrue(n >= 2 && n <= 4);
        }
        assertEquals(3, SpawnPicker.between(3, 3, rnd));
    }
}
