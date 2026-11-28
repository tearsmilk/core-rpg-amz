package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.Hooks;
import net.minecraft.client.renderer.debug.EntityHitboxDebugRenderer;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Хитбокс (F3+B) сущности, модель которой повёрнута модулем (onEntityTilt), — повёрнутым так же, вокруг ног:
 * именно такую коробку (стоит по палубе корабля) считают столкновения ядра. Плюс линия глаз и стрелка взгляда.
 * <p>
 * НОВОЕ: поза onEntityPose со сдвигом offset — коробка модели рисуется так же, как модель (сдвиг, потом поворот),
 * чтобы было видно, где модель на самом деле. Поза без offset — как в 0.48.0 (только onEntityTilt).
 * <p>
 * Восстановлено по байткоду 0.48.0 (javap) + исходникам 0.25.
 */
@Mixin(EntityHitboxDebugRenderer.class)
public abstract class HitboxTiltMixin {

    @Inject(method = "showHitboxes", at = @At("HEAD"), cancellable = true)
    private void hokaOdyssey$tilted(Entity e, float pt, boolean server, CallbackInfo ci) {
        if (server) return;
        Quaternionfc t = null;
        Vec3 feet = e.getPosition(pt);
        var pose = Hooks.pose(e, pt);                                                    // НОВОЕ
        if (pose != null && pose.tilt() != null && pose.offset() != null) {             // НОВОЕ
            t = pose.tilt();
            feet = feet.add(pose.offset().x(), pose.offset().y(), pose.offset().z());
        }
        if (t == null) t = Hooks.tilt(e, pt);
        if (t == null) return;
        Quaternionf q = new Quaternionf(t);
        float hw = e.getBbWidth() / 2, h = e.getBbHeight(), eye = e.getEyeHeight();
        Vec3[] c = new Vec3[8];
        for (int i = 0; i < 8; i++) c[i] = at(q, feet, (i & 1) == 0 ? -hw : hw, (i & 2) == 0 ? 0 : h, (i & 4) == 0 ? -hw : hw);
        int white = 0xFFFFFFFF, red = 0xFFFF0000;
        int[][] edges = {{0, 1}, {2, 3}, {4, 5}, {6, 7}, {0, 2}, {1, 3}, {4, 6}, {5, 7}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
        for (int[] ed : edges) Gizmos.line(c[ed[0]], c[ed[1]], white);
        Vec3 e0 = at(q, feet, -hw, eye, -hw), e1 = at(q, feet, hw, eye, -hw), e2 = at(q, feet, hw, eye, hw), e3 = at(q, feet, -hw, eye, hw);
        Gizmos.line(e0, e1, red);
        Gizmos.line(e1, e2, red);
        Gizmos.line(e2, e3, red);
        Gizmos.line(e3, e0, red);
        Vec3 eyeW = at(q, feet, 0, eye, 0);
        Gizmos.arrow(eyeW, eyeW.add(e.getViewVector(pt).scale(2)), 0xFF0000FF);
        ci.cancel();
    }

    @Unique
    private static Vec3 at(Quaternionf q, Vec3 feet, float x, float y, float z) {
        Vector3f v = q.transform(new Vector3f(x, y, z));
        return feet.add(v.x, v.y, v.z);
    }
}
