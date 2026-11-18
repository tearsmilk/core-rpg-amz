package core.amazake.systems.coremechanics.doll;

import core.amazake.systems.coremechanics.physics.RagdollSpec;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Трансформация дисплея куска. Все дисплеи куклы стоят в одной точке — якоре (см. DollRenderer), поэтому смещение
 * центра части от якоря входит в translation (как в экспорте /summon), а поза задаётся только трансформацией.
 */
public final class PieceTransform {

    private PieceTransform() {
    }

    public static Transformation of(final Piece pc, final double[] partPos, final Quaternionf q, final double ax, final double ay,
                                    final double az) {
        final Vector3f rel = new Vector3f((float) (partPos[0] - ax), (float) (partPos[1] - ay), (float) (partPos[2] - az));
        if (pc.head()) {
            // голова-предмет рисуется ниже своей точки на 0.25·масштаб; item_display сам разворачивает её на 180°,
            // поэтому лицо уже смотрит вперёд (−Z осей куклы). size — масштаб по осям (кусок тела — растянутая голова)
            final Vector3f sc = pc.size();
            final Vector3f tr = q.transform(new Vector3f(pc.off()).add(0, 0.25f * sc.y, 0), new Vector3f()).add(rel);
            final float k = pc.part() == RagdollSpec.HEAD ? 1.002f : 1.01f;
            return new Transformation(tr, new Quaternionf(q), new Vector3f(sc).mul(k), new Quaternionf());
        }
        final Vector3f corner = new Vector3f(pc.off()).sub(pc.size().x / 2, pc.size().y / 2, pc.size().z / 2);
        final Vector3f tr = q.transform(corner, new Vector3f()).add(rel);
        return new Transformation(tr, new Quaternionf(q), new Vector3f(pc.size()).mul(1.002f), new Quaternionf());
    }
}
