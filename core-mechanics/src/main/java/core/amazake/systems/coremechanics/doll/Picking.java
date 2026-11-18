package core.amazake.systems.coremechanics.doll;

import core.amazake.systems.coremechanics.physics.RagdollSpec;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Выбор части куклы лучом из глаз (коробка части в её осях, с запасом pad). Чистая математика — любой поток. */
public final class Picking {

    private Picking() {
    }

    /** Попадание: часть и расстояние по лучу. */
    public record Hit(int part, double t) {
    }

    /**
     * Ближайшая часть позы под лучом (eye + dir·t, dir — единичный), не дальше maxT; null — мимо.
     */
    public static Hit pick(final Pose pose, final double ex, final double ey, final double ez, final double dx, final double dy,
                           final double dz, final double maxT, final double pad) {
        Hit best = null;
        double bestT = maxT;
        for (int i = 0; i < RagdollSpec.N; i++) {
            final double t = rayBox(pose.pos()[i], pose.quat(i), RagdollSpec.HALF[i], ex, ey, ez, dx, dy, dz, bestT, pad);
            if (t >= 0 && t < bestT) {
                bestT = t;
                best = new Hit(i, t);
            }
        }
        return best;
    }

    /** Луч против повёрнутой коробки (метод плит). Расстояние входа или -1. */
    public static double rayBox(final double[] c, final Quaternionf rot, final float[] half, final double ex, final double ey,
                                final double ez, final double dx, final double dy, final double dz, final double maxT,
                                final double pad) {
        final Quaternionf inv = new Quaternionf(rot).conjugate();
        final Vector3f o = inv.transform(new Vector3f((float) (ex - c[0]), (float) (ey - c[1]), (float) (ez - c[2])));
        final Vector3f v = inv.transform(new Vector3f((float) dx, (float) dy, (float) dz));
        final float[] oo = {o.x, o.y, o.z}, vv = {v.x, v.y, v.z};
        double t0 = 0, t1 = maxT;
        for (int k = 0; k < 3; k++) {
            final double hh = half[k] + pad;
            if (Math.abs(vv[k]) < 1e-6) {
                if (Math.abs(oo[k]) > hh) {
                    return -1;
                }
            } else {
                final double a = (-hh - oo[k]) / vv[k], b = (hh - oo[k]) / vv[k];
                t0 = Math.max(t0, Math.min(a, b));
                t1 = Math.min(t1, Math.max(a, b));
                if (t0 > t1) {
                    return -1;
                }
            }
        }
        return t0;
    }
}
