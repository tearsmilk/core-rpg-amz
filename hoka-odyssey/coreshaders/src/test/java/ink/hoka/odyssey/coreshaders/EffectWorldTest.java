package ink.hoka.odyssey.coreshaders;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class EffectWorldTest {
    private static Effect cube(int x, int y, int z, boolean rim) {
        return new Effect(UUID.randomUUID(), new EffectData(Shape.CUBE, "space", 0xFFFFFF, Dir.UP, x, y, z, rim), null);
    }

    @Test
    void indexesByBlockAndChunk() {
        EffectWorld w = new EffectWorld("t", null);
        Effect a = cube(0, 64, 0, false);
        Effect b = cube(17, 64, -1, false);
        w.add(a);
        w.add(b);
        assertTrue(w.dirty);
        assertEquals(1, w.at(0, 64, 0).size());
        assertEquals(1, w.inChunk(1, -1).size());
        assertEquals(1, w.near(0.5, 64.5, 0.5, 2).size());
        assertEquals(2, w.near(0.5, 64.5, 0.5, 40).size());
        w.change(b, b.data.withShape(Shape.PLANE));
        assertFalse(w.cube(17, 64, -1));
        assertTrue(w.plane(17, 64, -1, Dir.UP));
        w.change(a, new EffectData(Shape.CUBE, "space", 0, Dir.UP, 100, 64, 100, false));
        assertEquals(0, w.at(0, 64, 0).size());
        assertEquals(1, w.inChunk(6, 6).size());
        w.remove(a);
        assertEquals(1, w.size());
        assertEquals(0, w.inChunk(6, 6).size());
    }

    @Test
    void rimOnlyOnOuterEdgesOfGroup() {
        EffectWorld w = new EffectWorld("t", null);
        Effect a = cube(0, 64, 0, true);
        Effect b = cube(1, 64, 0, true);
        w.add(a);
        int[] alone = w.masks(a.data);
        w.add(b);
        int[] paired = w.masks(a.data);
        assertEquals(Faces.CUBE_TINTS, alone.length);
        assertFalse(java.util.Arrays.equals(alone, paired));
        assertArrayEquals(new int[Faces.CUBE_TINTS], w.masks(a.data.withRim(false)));
    }
}
