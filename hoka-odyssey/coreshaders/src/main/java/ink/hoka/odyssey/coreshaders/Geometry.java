package ink.hoka.odyssey.coreshaders;

public final class Geometry {
    public static final double PLANE_SLAB = 0.0625;

    private Geometry() {
    }

    public static double[] cubeCenter(int bx, int by, int bz) {
        return new double[] {bx + 0.5, by + 0.5, bz + 0.5};
    }

    public static double[] planeCenter(int bx, int by, int bz, Dir face, double offset) {
        double k = 0.5 - offset;
        return new double[] {bx + 0.5 - face.dx * k, by + 0.5 - face.dy * k, bz + 0.5 - face.dz * k};
    }

    public static double[] center(Shape shape, int bx, int by, int bz, Dir face, double offset) {
        return shape == Shape.CUBE ? cubeCenter(bx, by, bz) : planeCenter(bx, by, bz, face, offset);
    }

    public static float[] planeRotation(Dir face) {
        float s = (float) Math.sqrt(0.5);
        return switch (face) {
            case NORTH, SOUTH -> new float[] {0f, 0f, 0f, 1f};
            case EAST, WEST -> new float[] {0f, s, 0f, s};
            case UP, DOWN -> new float[] {s, 0f, 0f, s};
        };
    }

    public static double[] rotate(float[] q, double[] v) {
        double qx = q[0];
        double qy = q[1];
        double qz = q[2];
        double qw = q[3];
        double tx = 2 * (qy * v[2] - qz * v[1]);
        double ty = 2 * (qz * v[0] - qx * v[2]);
        double tz = 2 * (qx * v[1] - qy * v[0]);
        return new double[] {
            v[0] + qw * tx + (qy * tz - qz * ty),
            v[1] + qw * ty + (qz * tx - qx * tz),
            v[2] + qw * tz + (qx * ty - qy * tx)
        };
    }

    public static double[] hitbox(Shape shape, int bx, int by, int bz, Dir face, double offset) {
        if (shape == Shape.CUBE) {
            return new double[] {bx + 0.5, by, bz + 0.5, 1.0, 1.0};
        }
        double[] c = planeCenter(bx, by, bz, face, offset);
        double thin = 0.05;
        if (face == Dir.UP) {
            return new double[] {c[0], by, c[2], 1.0, thin};
        }
        if (face == Dir.DOWN) {
            return new double[] {c[0], by + 1.0 - thin, c[2], 1.0, thin};
        }
        return new double[] {c[0], by, c[2], 0.5, 1.0};
    }

    public static double[] box(Shape shape, int bx, int by, int bz, Dir face) {
        double[] b = {bx, by, bz, bx + 1.0, by + 1.0, bz + 1.0};
        if (shape == Shape.CUBE) {
            return b;
        }
        int axis = face.dx != 0 ? 0 : face.dy != 0 ? 1 : 2;
        int sign = face.dx + face.dy + face.dz;
        if (sign > 0) {
            b[axis + 3] = b[axis] + PLANE_SLAB;
        } else {
            b[axis] = b[axis + 3] - PLANE_SLAB;
        }
        return b;
    }

    public static double rayBox(double[] o, double[] d, double[] box, double maxT) {
        double t0 = 0.0;
        double t1 = maxT;
        for (int a = 0; a < 3; a++) {
            double lo = box[a];
            double hi = box[a + 3];
            if (Math.abs(d[a]) < 1e-12) {
                if (o[a] < lo || o[a] > hi) {
                    return -1.0;
                }
                continue;
            }
            double ta = (lo - o[a]) / d[a];
            double tb = (hi - o[a]) / d[a];
            if (ta > tb) {
                double t = ta;
                ta = tb;
                tb = t;
            }
            t0 = Math.max(t0, ta);
            t1 = Math.min(t1, tb);
            if (t0 > t1) {
                return -1.0;
            }
        }
        return t0;
    }

    public static Dir faceFromLocal(double x, double y, double z) {
        double ax = Math.abs(x);
        double ay = Math.abs(y);
        double az = Math.abs(z);
        if (ay >= ax && ay >= az) {
            return y >= 0 ? Dir.UP : Dir.DOWN;
        }
        if (ax >= az) {
            return x >= 0 ? Dir.EAST : Dir.WEST;
        }
        return z >= 0 ? Dir.SOUTH : Dir.NORTH;
    }
}
