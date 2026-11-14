package core.amazake.systems.coremobs.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import core.amazake.systems.coremobs.geo.BlockPos;
import java.util.List;
import org.junit.jupiter.api.Test;

class OutlineTest {

    @Test
    void bottomFollowsPointsNotPlayerAndPostsReachCeiling() {
        final Region r = Region.fromPoints("a", "world", List.of(new BlockPos(0, 64, 0), new BlockPos(20, 70, 0),
            new BlockPos(20, 64, 10)), 5, 5);
        final List<Outline.Segment> edges = Outline.segments(r, 1000, false);
        assertEquals(3, edges.size());
        assertEquals(65 + Outline.LIFT, edges.get(0).ay(), 1e-9);
        assertEquals(71 + Outline.LIFT, edges.get(0).by(), 1e-9);
        final List<Outline.Segment> all = Outline.segments(r, 1000, true);
        assertEquals(6, all.size());
        for (final Outline.Segment post : all.subList(3, 6)) {
            assertEquals(r.maxY() + 1.0, post.by(), 1e-9);
        }
    }

    @Test
    void longEdgesAreSplitWithStableIndexes() {
        final Region r = Region.fromPoints("a", "world", List.of(new BlockPos(0, 64, 0), new BlockPos(30, 64, 0),
            new BlockPos(30, 64, 30)), 0, 5);
        final List<Outline.Segment> segs = Outline.segments(r, 8, false);
        for (int i = 0; i < segs.size(); i++) {
            assertEquals(i, segs.get(i).index());
            assertTrue(segs.get(i).length() <= 8 + 1e-9);
        }
        assertEquals(segs, Outline.segments(r, 8, false));
    }
}
