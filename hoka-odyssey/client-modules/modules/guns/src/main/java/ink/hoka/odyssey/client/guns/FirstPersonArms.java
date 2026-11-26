package ink.hoka.odyssey.client.guns;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelPart;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * Руки своего игрока от первого лица — его скин, его модель (тонкая или широкая), рукава по настройкам скина. Рисуются
 * сразу в буфер (без очереди игры), частями той же модели игрока, что и от третьего лица: рука в «ванильной»
 * позе руки от первого лица (как AvatarRenderer.renderHand: поза сброшена, наклон ±0,1 рад), а куда её поставить,
 * решает анимация оружия (кость righthand_pos / lefthand_pos). Состояние общей модели после себя возвращаем.
 */
final class FirstPersonArms {
    private FirstPersonArms() {
    }

    static void render(MultiBufferSource buffers, Matrix4f m, boolean right, int light) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || p.isInvisible()) return;
        PlayerModel model = mc.getEntityRenderDispatcher().getPlayerRenderer(p).getModel();
        ModelPart arm = right ? model.rightArm : model.leftArm;
        ModelPart sleeve = right ? model.rightSleeve : model.leftSleeve;
        PartPose saved = arm.storePose();
        boolean armVisible = arm.visible;
        boolean sleeveVisible = sleeve.visible;
        try {
            arm.resetPose();
            arm.zRot = right ? 0.1f : -0.1f;
            arm.visible = true;
            sleeve.visible = p.isModelPartShown(right ? PlayerModelPart.RIGHT_SLEEVE : PlayerModelPart.LEFT_SLEEVE);
            PoseStack ps = new PoseStack();
            ps.last().pose().set(m);
            ps.last().normal().set(normal(m));
            Identifier skin = p.getSkin().body().texturePath();
            arm.render(ps, buffers.getBuffer(RenderTypes.entityTranslucent(skin)), light, OverlayTexture.NO_OVERLAY);
        } finally {
            arm.loadPose(saved);
            arm.visible = armVisible;
            sleeve.visible = sleeveVisible;
        }
    }

    /** Матрица нормалей: поворот m без масштаба (масштаб у нас одинаковый по осям). */
    static Matrix3f normal(Matrix4f m) {
        Matrix3f n = new Matrix3f(m);
        float det = n.determinant();
        float s = det > 1e-12f ? (float) Math.cbrt(det) : 1f;
        return n.scale(1f / s);
    }
}
