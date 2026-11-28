package ink.hoka.odyssey.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import ink.hoka.odyssey.client.Hooks;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Тела игроков для модулей: состояние для рисования (после расчёта игрой) и поворот тела. Ванилла умеет только
 * курс, наклон ±90 и «плашмя» — крен и «вверх ногами» другим игрокам показывает модуль через этот крючок.
 * Дескрипторы указаны явно: у AvatarRenderer есть мосты (bridge) с типами родителей.
 */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {

    @Inject(method = "setupRotations(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;FF)V",
            at = @At("HEAD"), cancellable = true)
    private void hokaOdyssey$rotations(AvatarRenderState state, PoseStack pose, float bodyRot, float scale, CallbackInfo ci) {
        if (Hooks.hasAvatarRot() && Hooks.avatarRotations(state, pose, bodyRot, scale)) ci.cancel();
    }

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
            at = @At("TAIL"))
    private void hokaOdyssey$state(Avatar entity, AvatarRenderState state, float partialTick, CallbackInfo ci) {
        if (Hooks.hasAvatarState()) Hooks.avatarState(entity, state, partialTick);
    }
}
