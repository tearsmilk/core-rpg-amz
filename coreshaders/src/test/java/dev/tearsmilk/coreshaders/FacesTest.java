package dev.tearsmilk.coreshaders;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FacesTest {
    private static final class Grid implements Faces.Lookup {
        final Set<String> cubes = new HashSet<>();
        final Set<String> planes = new HashSet<>();

        Grid addCube(int x, int y, int z) {
            cubes.add(x + "," + y + "," + z);
            return this;
        }

        Grid addPlane(int x, int y, int z, Dir f) {
            planes.add(x + "," + y + "," + z + "," + f);
            return this;
        }

        @Override
        public boolean cube(int x, int y, int z) {
            return cubes.contains(x + "," + y + "," + z);
        }

        @Override
        public boolean plane(int x, int y, int z, Dir face) {
            return planes.contains(x + "," + y + "," + z + "," + face);
        }
    }

    private static int tintFacing(int dx, int dy, int dz) {
        for (int i = 0; i < Faces.CUBE_TINTS; i++) {
            int[] n = Faces.toWorld(Shape.CUBE, Dir.UP, Faces.normal(Faces.CUBE_FACES[i]));
            if (n[0] == dx && n[1] == dy && n[2] == dz) {
                return i;
            }
        }
        throw new AssertionError();
    }

    @Test
    void encodeKeepsColorAndRoundTrips() {
        for (int m = 0; m <= 15; m++) {
            int c = Faces.encode(0xFF5050, m);
            assertEquals(m, Faces.decode(c));
            assertEquals(0xFF, (c >> 16) & 255);
            assertEquals(0x50, ((c >> 8) & 255) & 0xFC);
            assertEquals(0x50, (c & 255) & 0xFC);
        }
        assertEquals(15, Faces.decode(0xFFFFFF));
    }

    @Test
    void itemDisplayFlipsModelFaces() {
        assertEquals(0, tintFacing(0, 0, 1));
        assertEquals(1, tintFacing(0, 0, -1));
        assertEquals(2, tintFacing(-1, 0, 0));
        assertEquals(3, tintFacing(1, 0, 0));
        assertEquals(4, tintFacing(0, 1, 0));
        assertEquals(5, tintFacing(0, -1, 0));
    }

    @Test
    void edgesArePerpendicularAndDistinct() {
        for (Dir mf : Faces.CUBE_FACES) {
            for (Dir ef : Dir.values()) {
                for (Shape sh : Shape.values()) {
                    int[] n = Faces.toWorld(sh, ef, Faces.normal(mf));
                    int[][] e = Faces.edgeDirs(sh, ef, mf);
                    Set<String> seen = new HashSet<>();
                    for (int[] v : e) {
                        assertEquals(0, v[0] * n[0] + v[1] * n[1] + v[2] * n[2]);
                        assertEquals(1, Math.abs(v[0]) + Math.abs(v[1]) + Math.abs(v[2]));
                        seen.add(v[0] + "," + v[1] + "," + v[2]);
                    }
                    assertEquals(4, seen.size());
                }
            }
        }
    }

    @Test
    void rimOffMeansNoEdges() {
        Grid g = new Grid();
        assertArrayEquals(new int[6], Faces.cubeMasks(false, 0, 0, 0, g));
        assertArrayEquals(new int[2], Faces.planeMasks(false, 0, 0, 0, Dir.NORTH, g));
    }

    @Test
    void lonelyCubeHasFullRim() {
        int[] m = Faces.cubeMasks(true, 0, 0, 0, new Grid().addCube(0, 0, 0));
        for (int v : m) {
            assertEquals(Faces.ALL_EDGES, v);
        }
    }

    @Test
    void sharedFaceAndSharedEdgesLoseRim() {
        Grid g = new Grid().addCube(0, 0, 0).addCube(1, 0, 0);
        int[] m = Faces.cubeMasks(true, 0, 0, 0, g);
        assertEquals(0, m[tintFacing(1, 0, 0)]);
        assertEquals(Faces.ALL_EDGES, m[tintFacing(-1, 0, 0)]);
        int up = m[tintFacing(0, 1, 0)];
        assertEquals(3, Integer.bitCount(up));
        int[][] e = Faces.edgeDirs(Shape.CUBE, Dir.UP, Faces.CUBE_FACES[tintFacing(0, 1, 0)]);
        for (int b = 0; b < 4; b++) {
            boolean towardNeighbour = e[b][0] == 1;
            assertEquals(!towardNeighbour, (up & (1 << b)) != 0);
        }
    }

    @Test
    void wallOfCubesHasOnlyOuterOutline() {
        Grid g = new Grid();
        for (int x = 0; x < 3; x++) {
            for (int y = 0; y < 3; y++) {
                g.addCube(x, y, 0);
            }
        }
        int front = tintFacing(0, 0, 1);
        assertEquals(0, Faces.cubeMasks(true, 1, 1, 0, g)[front]);
        assertEquals(2, Integer.bitCount(Faces.cubeMasks(true, 0, 0, 0, g)[front]));
        assertEquals(1, Integer.bitCount(Faces.cubeMasks(true, 1, 0, 0, g)[front]));
    }

    @Test
    void concaveCornerHasNoRim() {
        Grid g = new Grid().addCube(0, 0, 0).addCube(1, 1, 0);
        int up = Faces.cubeMasks(true, 0, 0, 0, g)[tintFacing(0, 1, 0)];
        assertNotEquals(Faces.ALL_EDGES, up);
        assertEquals(3, Integer.bitCount(up));
    }

    @Test
    void planesInAWallJoin() {
        Grid g = new Grid();
        for (int x = 0; x < 3; x++) {
            for (int y = 0; y < 3; y++) {
                g.addPlane(x, y, 0, Dir.SOUTH);
            }
        }
        assertArrayEquals(new int[2], Faces.planeMasks(true, 1, 1, 0, Dir.SOUTH, g));
        for (int m : Faces.planeMasks(true, 0, 1, 0, Dir.SOUTH, g)) {
            assertEquals(1, Integer.bitCount(m));
        }
        g.addPlane(5, 5, 0, Dir.EAST);
        for (Dir d : Dir.values()) {
            for (int m : Faces.planeMasks(true, 9, 9, 9, d, g)) {
                assertEquals(Faces.ALL_EDGES, m);
            }
        }
    }

    @Test
    void planeEdgesFollowTheFace() {
        for (Dir face : Dir.values()) {
            for (Dir side : Faces.PLANE_SIDES) {
                int[] n = Faces.toWorld(Shape.PLANE, face, Faces.normal(side));
                assertEquals(1, Math.abs(n[0] * face.dx + n[1] * face.dy + n[2] * face.dz), face + " " + side);
            }
        }
    }
}
