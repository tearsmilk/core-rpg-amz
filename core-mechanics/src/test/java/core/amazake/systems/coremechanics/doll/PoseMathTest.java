package core.amazake.systems.coremechanics.doll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import core.amazake.systems.coremechanics.physics.RagdollSpec;
import core.amazake.systems.coremechanics.statue.DollPoses;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class PoseMathTest {

    private static final double EPS = 1e-4;

    @Test
    void standingFacingNorthIsSpecCenters() {
        // yaw 180 (смотрит на −Z) → поворот π − π = 0: оси куклы совпадают с миром
        final Pose p = PoseMath.standing(10, 64, -5, 180f);
        for (int i = 0; i < RagdollSpec.N; i++) {
            assertEquals(10 + RagdollSpec.CENTER[i][0], p.pos()[i][0], EPS);
            assertEquals(64 + RagdollSpec.CENTER[i][1], p.pos()[i][1], EPS);
            assertEquals(-5 + RagdollSpec.CENTER[i][2], p.pos()[i][2], EPS);
        }
    }

    @Test
    void standingFacingSouthMirrorsArms() {
        final Pose p = PoseMath.standing(0, 0, 0, 0f);
        // правая рука (x = +0.375 в осях куклы) при взгляде на +Z — на −X мира
        assertEquals(-0.375, p.pos()[RagdollSpec.ARM_R][0], EPS);
        assertEquals(0.375, p.pos()[RagdollSpec.ARM_L][0], EPS);
    }

    @Test
    void statueStandPoseLooksLikeStanding() {
        final Pose p = PoseMath.full(0, 0, 0, 180f, DollPoses.pose("stand"));
        assertEquals(0.75 + 0.375, p.torso()[1], EPS);
        assertEquals(0.75 + 0.75 + 0.25, p.pos()[RagdollSpec.HEAD][1], EPS);
        // ноги свисают от таза: центр на высоте 0.75 − 0.375
        assertEquals(0.375, p.pos()[RagdollSpec.LEG_R][1], EPS);
        assertEquals(0.125, p.pos()[RagdollSpec.LEG_R][0], EPS);
        assertEquals(-0.125, p.pos()[RagdollSpec.LEG_L][0], EPS);
    }

    @Test
    void tPoseArmsAreHorizontal() {
        final Pose p = PoseMath.full(0, 0, 0, 180f, DollPoses.pose("tpose"));
        final double shoulderY = 0.75 + 0.70;
        assertEquals(shoulderY, p.pos()[RagdollSpec.ARM_R][1], 1e-3);
        assertEquals(0.375 + 0.325, p.pos()[RagdollSpec.ARM_R][0], 1e-3);
        assertEquals(-(0.375 + 0.325), p.pos()[RagdollSpec.ARM_L][0], 1e-3);
    }

    @Test
    void wrapAngles() {
        assertEquals(-165f, PoseMath.wrap(195f));
        assertEquals(180f, PoseMath.wrap(-180f));
        assertEquals(0f, PoseMath.wrap(360f));
    }

    @Test
    void pieceTransformIsRelativeToAnchor() {
        final Piece block = Piece.block(RagdollSpec.TORSO, new Vector3f(), new Vector3f(0.5f, 0.75f, 0.25f), org.bukkit.Material.STONE);
        final Transformation t = PieceTransform.of(block, new double[] {11, 65, 5}, new Quaternionf(), 10, 64, 5);
        // угол блока = центр − половина размера, плюс смещение части от якоря (1, 1, 0)
        assertEquals(1 - 0.25f, t.getTranslation().x, 1e-5);
        assertEquals(1 - 0.375f, t.getTranslation().y, 1e-5);
        assertEquals(-0.125f, t.getTranslation().z, 1e-5);
        assertEquals(0.5f * 1.002f, t.getScale().x, 1e-5);
        final Piece head = Piece.head(RagdollSpec.HEAD, new Vector3f(), new Vector3f(1f), null, null);
        final Transformation h = PieceTransform.of(head, new double[] {10, 66, 5}, new Quaternionf(), 10, 64, 5);
        // голова-предмет рисуется ниже своей точки на 0.25
        assertEquals(2.25f, h.getTranslation().y, 1e-5);
    }

    @Test
    void pickHitsTorsoFromFrontAndMissesAside() {
        final Pose p = PoseMath.standing(0, 0, 0, 180f);
        // глаза в 3 блоках перед куклой (−Z), смотрим на +Z на высоте туловища
        final Picking.Hit h = Picking.pick(p, 0, 1.1, -3, 0, 0, 1, 6, 0.08);
        assertNotNull(h);
        assertEquals(RagdollSpec.TORSO, h.part());
        assertTrue(h.t() > 2.7 && h.t() < 3.0, "t=" + h.t());
        assertNull(Picking.pick(p, 2, 1.1, -3, 0, 0, 1, 6, 0.08));
        assertNull(Picking.pick(p, 0, 1.1, -3, 0, 0, 1, 2, 0.08));   // дальше досягаемости
        final Picking.Hit head = Picking.pick(p, 0, 1.75, -3, 0, 0, 1, 6, 0.08);
        assertEquals(RagdollSpec.HEAD, head.part());
    }
}
