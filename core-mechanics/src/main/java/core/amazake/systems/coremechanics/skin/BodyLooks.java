package core.amazake.systems.coremechanics.skin;

import core.amazake.systems.coremechanics.doll.Piece;
import core.amazake.systems.coremechanics.physics.RagdollSpec;
import java.util.ArrayList;
import java.util.List;
import org.joml.Vector3f;

/** Из чего рисуется кукла (перенесено из RagdollManager.addBlocks/addCubes). */
public final class BodyLooks {

    private BodyLooks() {
    }

    /** Голова — настоящая голова игрока (текстура профиля; null — голова Стива). */
    public static Piece head(final String texture, final String signature) {
        return Piece.head(RagdollSpec.HEAD, new Vector3f(), new Vector3f(1f), texture, signature);
    }

    /** Тело цветными блоками (пока кубики скина не готовы): туловище, руки (рукав + кисть), ноги (штаны + обувь). */
    public static List<Piece> blocks(final BodyColors c) {
        final List<Piece> out = new ArrayList<>();
        out.add(Piece.block(RagdollSpec.TORSO, new Vector3f(), new Vector3f(0.5f, 0.75f, 0.25f), c.shirt()));
        for (final int arm : new int[] {RagdollSpec.ARM_R, RagdollSpec.ARM_L}) {
            out.add(Piece.block(arm, new Vector3f(0, 0.125f, 0), new Vector3f(0.25f, 0.5f, 0.25f), c.sleeve()));
            out.add(Piece.block(arm, new Vector3f(0, -0.25f, 0), new Vector3f(0.25f, 0.25f, 0.25f), c.hand()));
        }
        for (final int leg : new int[] {RagdollSpec.LEG_R, RagdollSpec.LEG_L}) {
            out.add(Piece.block(leg, new Vector3f(0, 0.0625f, 0), new Vector3f(0.25f, 0.625f, 0.25f), c.pants()));
            out.add(Piece.block(leg, new Vector3f(0, -0.3125f, 0), new Vector3f(0.25f, 0.125f, 0.25f), c.shoes()));
        }
        return out;
    }

    /** Тело из кубиков скина: 10 голов MineSkin ([value, signature] на коробку). */
    public static List<Piece> cubes(final String[][] tex) {
        final List<Piece> out = new ArrayList<>();
        for (int i = 0; i < SkinBoxes.COUNT; i++) {
            out.add(Piece.head(SkinBoxes.part(i), SkinBoxes.offset(i), SkinBoxes.scale(i), tex[i][0], tex[i][1]));
        }
        return out;
    }
}
