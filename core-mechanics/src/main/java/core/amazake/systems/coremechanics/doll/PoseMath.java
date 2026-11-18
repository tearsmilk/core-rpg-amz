package core.amazake.systems.coremechanics.doll;

import core.amazake.systems.coremechanics.physics.RagdollSpec;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Позы без физики (перенесено из RagdollManager.Puppet): стоя (начальная поза рэгдолла) и поза статуи из массива
 * {@link #POSE_LEN} углов. Оси тела: x — вправо, y — вверх, z — назад.
 */
public final class PoseMath {

    /**
     * Длина массива позы: высота таза, тело x/y/z, голова x/y/z, правая рука x/y/z, левая рука, правая нога, левая нога
     * (градусы; x — вперёд/назад, z — в сторону, y — кручение).
     */
    public static final int POSE_LEN = 19;
    /** Начало тройки углов части (по индексу части RagdollSpec) в массиве позы. */
    public static final int[] PART_IDX = {4, 1, 7, 10, 13, 16};

    private PoseMath() {
    }

    /** Поворот локальных осей куклы в мир для yaw Minecraft (градусы). */
    public static Quaternionf yawRotation(final float yawDeg) {
        return new Quaternionf().rotationY((float) (Math.PI - Math.toRadians(yawDeg)));
    }

    /** Стоя: ступни в (fx, fy, fz), лицом по yaw — так рэгдолл создаётся в физике. */
    public static Pose standing(final double fx, final double fy, final double fz, final float yawDeg) {
        final Quaternionf q = yawRotation(yawDeg);
        final Pose p = Pose.blank();
        for (int i = 0; i < RagdollSpec.N; i++) {
            final double[] c = RagdollSpec.CENTER[i];
            final Vector3f v = q.transform(new Vector3f((float) c[0], (float) c[1], (float) c[2]));
            p.set(i, fx + v.x, fy + v.y, fz + v.z, q);
        }
        return p;
    }

    /** Поза статуи: ноги в (fx, fy, fz), поворот yaw, v — {@link #POSE_LEN} чисел. */
    public static Pose full(final double fx, final double fy, final double fz, final float yaw, final float[] v) {
        final Pose p = Pose.blank();
        final Quaternionf body = eul(yawRotation(yaw), v, 1);
        final double hx = fx, hy = fy + v[0], hz = fz;
        final Vector3f t = body.transform(new Vector3f(0, 0.375f, 0));
        p.set(RagdollSpec.TORSO, hx + t.x, hy + t.y, hz + t.z, body);
        final Quaternionf hq = eul(new Quaternionf(body), v, 4);
        final Vector3f neck = body.transform(new Vector3f(0, 0.75f, 0));
        final Vector3f hc = hq.transform(new Vector3f(0, 0.25f, 0));
        p.set(RagdollSpec.HEAD, hx + neck.x + hc.x, hy + neck.y + hc.y, hz + neck.z + hc.z, hq);
        limb(p, RagdollSpec.ARM_R, hx, hy, hz, body, 0.375f, 0.70f, v, 7, 0.325f);
        limb(p, RagdollSpec.ARM_L, hx, hy, hz, body, -0.375f, 0.70f, v, 10, 0.325f);
        limb(p, RagdollSpec.LEG_R, hx, hy, hz, body, 0.125f, 0f, v, 13, 0.375f);
        limb(p, RagdollSpec.LEG_L, hx, hy, hz, body, -0.125f, 0f, v, 16, 0.375f);
        return p;
    }

    private static Quaternionf eul(final Quaternionf q, final float[] v, final int i) {
        return q.rotateY((float) Math.toRadians(v[i + 1])).rotateX((float) Math.toRadians(v[i])).rotateZ((float) Math.toRadians(v[i + 2]));
    }

    private static void limb(final Pose p, final int part, final double hx, final double hy, final double hz, final Quaternionf body,
                             final float px, final float py, final float[] v, final int i, final float down) {
        final Quaternionf q = eul(new Quaternionf(body), v, i);
        final Vector3f piv = body.transform(new Vector3f(px, py, 0));
        final Vector3f c = q.transform(new Vector3f(0, -down, 0));
        p.set(part, hx + piv.x + c.x, hy + piv.y + c.y, hz + piv.z + c.z, q);
    }

    /** Угол в (−180, 180]. */
    public static float wrap(float a) {
        while (a > 180) {
            a -= 360;
        }
        while (a <= -180) {
            a += 360;
        }
        return a;
    }
}
