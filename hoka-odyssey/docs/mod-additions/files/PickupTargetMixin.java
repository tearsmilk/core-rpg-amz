package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.Hooks;
import net.minecraft.client.particle.ItemPickupParticle;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * (Необязательно.) Подбираемый предмет летит к середине повёрнутого тела своего игрока: «вверх» — по tilt позы.
 * НОВОЕ — плюс offset, если он задан (иначе предмет летит к телу, повёрнутому вокруг ног, а не к показанному).
 * <p>
 * Восстановлено по байткоду 0.48.0 (javap); в исходниках 0.25 этого миксина нет — сверить со своим.
 */
@Mixin(ItemPickupParticle.class)
public abstract class PickupTargetMixin {
    @Shadow @Final private Entity target;
    @Shadow protected double targetX;
    @Shadow protected double targetY;
    @Shadow protected double targetZ;

    @Inject(method = "updatePosition", at = @At("TAIL"))
    private void hoka$shipUp(CallbackInfo ci) {
        if (!(target instanceof LocalPlayer p)) return;
        var pose = Hooks.pose(p, 1f);
        if (pose == null || pose.tilt() == null) return;
        Vector3f up = pose.tilt().transform(new Vector3f(0, 1, 0), new Vector3f());
        double h = p.getEyeHeight() / 2.0;
        var off = pose.offset();                                                        // НОВОЕ
        double ox = off == null ? 0 : off.x(), oy = off == null ? 0 : off.y(), oz = off == null ? 0 : off.z();
        targetX = p.getX() + ox + up.x * h;
        targetY = p.getY() + oy + up.y * h;
        targetZ = p.getZ() + oz + up.z * h;
    }
}
