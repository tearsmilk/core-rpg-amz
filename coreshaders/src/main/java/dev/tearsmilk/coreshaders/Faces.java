package dev.tearsmilk.coreshaders;

public final class Faces {
    public static final Dir[] CUBE_FACES = {Dir.NORTH, Dir.SOUTH, Dir.EAST, Dir.WEST, Dir.UP, Dir.DOWN};
    public static final Dir[] PLANE_SIDES = {Dir.NORTH, Dir.SOUTH};
    public static final int CUBE_TINTS = CUBE_FACES.length;
    public static final int PLANE_TINTS = PLANE_SIDES.length;
    public static final int ALL_EDGES = 15;

    public interface Lookup {
        boolean cube(int x, int y, int z);

        boolean plane(int x, int y, int z, Dir face);
    }

    private Faces() {
    }

    static int[] uAxis(Dir modelFace) {
        return switch (modelFace) {
            case NORTH -> new int[] {-1, 0, 0};
            case SOUTH, UP, DOWN -> new int[] {1, 0, 0};
            case EAST -> new int[] {0, 0, -1};
            case WEST -> new int[] {0, 0, 1};
        };
    }

    static int[] vAxis(Dir modelFace) {
        return switch (modelFace) {
            case UP -> new int[] {0, 0, 1};
            case DOWN -> new int[] {0, 0, -1};
            default -> new int[] {0, -1, 0};
        };
    }

    static int[] normal(Dir d) {
        return new int[] {d.dx, d.dy, d.dz};
    }

    public static int[] toWorld(Shape shape, Dir effectFace, int[] local) {
        double[] v = {-local[0], local[1], -local[2]};
        if (shape == Shape.PLANE) {
            v = Geometry.rotate(Geometry.planeRotation(effectFace), v);
        }
        return new int[] {(int) Math.round(v[0]), (int) Math.round(v[1]), (int) Math.round(v[2])};
    }

    static int[][] edgeDirs(Shape shape, Dir effectFace, Dir modelFace) {
        int[] u = toWorld(shape, effectFace, uAxis(modelFace));
        int[] v = toWorld(shape, effectFace, vAxis(modelFace));
        return new int[][] {neg(u), u, neg(v), v};
    }

    private static int[] neg(int[] a) {
        return new int[] {-a[0], -a[1], -a[2]};
    }

    public static int[] cubeMasks(boolean rim, int x, int y, int z, Lookup l) {
        int[] out = new int[CUBE_TINTS];
        if (!rim) {
            return out;
        }
        for (int i = 0; i < CUBE_TINTS; i++) {
            int[] n = toWorld(Shape.CUBE, Dir.UP, normal(CUBE_FACES[i]));
            if (l.cube(x + n[0], y + n[1], z + n[2])) {
                continue;
            }
            int[][] edges = edgeDirs(Shape.CUBE, Dir.UP, CUBE_FACES[i]);
            int m = 0;
            for (int b = 0; b < 4; b++) {
                int[] e = edges[b];
                boolean side = l.cube(x + e[0], y + e[1], z + e[2]);
                boolean corner = l.cube(x + n[0] + e[0], y + n[1] + e[1], z + n[2] + e[2]);
                if (!side && !corner) {
                    m |= 1 << b;
                }
            }
            out[i] = m;
        }
        return out;
    }

    public static int[] planeMasks(boolean rim, int x, int y, int z, Dir face, Lookup l) {
        int[] out = new int[PLANE_TINTS];
        if (!rim) {
            return out;
        }
        for (int i = 0; i < PLANE_TINTS; i++) {
            int[][] edges = edgeDirs(Shape.PLANE, face, PLANE_SIDES[i]);
            int m = 0;
            for (int b = 0; b < 4; b++) {
                int[] e = edges[b];
                if (!l.plane(x + e[0], y + e[1], z + e[2], face)) {
                    m |= 1 << b;
                }
            }
            out[i] = m;
        }
        return out;
    }

    public static int encode(int rgb, int mask) {
        return (rgb & 0xFFFCFC) | ((mask & 3) << 8) | ((mask >> 2) & 3);
    }

    public static int decode(int rgb) {
        return ((rgb >> 8) & 3) | ((rgb & 3) << 2);
    }
}
