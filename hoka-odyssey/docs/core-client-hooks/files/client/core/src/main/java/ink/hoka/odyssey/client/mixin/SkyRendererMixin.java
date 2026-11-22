package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.Hooks;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Модуль забрал небо в этом кадре ({@link Hooks#skyClaimed}) — ванильные части неба не рисуются. Флаг ставится
 * после крючков, поэтому сам модуль из крючка может звать эти методы (взять ванильные солнце и звёзды).
 */
@Mixin(SkyRenderer.class)
public abstract class SkyRendererMixin {

    @Inject(method = {
            "renderSkyDisc(I)V",
            "renderDarkDisc()V",
            "renderSunMoonAndStars(Lcom/mojang/blaze3d/vertex/PoseStack;FFFLnet/minecraft/world/level/MoonPhase;FF)V",
            "renderSunriseAndSunset(Lcom/mojang/blaze3d/vertex/PoseStack;FI)V",
            "renderEndSky()V",
            "renderEndFlash(Lcom/mojang/blaze3d/vertex/PoseStack;FFF)V"
    }, at = @At("HEAD"), cancellable = true)
    private void hokaOdyssey$claimed(CallbackInfo ci) {
        if (Hooks.skyClaimed) ci.cancel();
    }
}
