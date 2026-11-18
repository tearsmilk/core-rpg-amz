package core.amazake.systems.coremechanics.doll;

import core.amazake.systems.coremechanics.physics.RagdollSpec;
import org.joml.Quaternionf;

/**
 * Поза куклы: центры 6 частей (мир) и их повороты (x, y, z, w). После публикации НЕ меняется — поэтому её можно
 * читать из любого потока (поток физики пишет новую позу, поток региона рисует, поток игрока выбирает часть лучом).
 */
public record Pose(double[][] pos, float[][] rot) {

    public static Pose blank() {
        return new Pose(new double[RagdollSpec.N][3], new float[RagdollSpec.N][4]);
    }

    public Quaternionf quat(final int part) {
        final float[] r = this.rot[part];
        return new Quaternionf(r[0], r[1], r[2], r[3]);
    }

    void set(final int part, final double x, final double y, final double z, final Quaternionf q) {
        this.pos[part][0] = x;
        this.pos[part][1] = y;
        this.pos[part][2] = z;
        this.rot[part][0] = q.x;
        this.rot[part][1] = q.y;
        this.rot[part][2] = q.z;
        this.rot[part][3] = q.w;
    }

    /** Центр туловища. */
    public double[] torso() {
        return this.pos[RagdollSpec.TORSO];
    }
}
