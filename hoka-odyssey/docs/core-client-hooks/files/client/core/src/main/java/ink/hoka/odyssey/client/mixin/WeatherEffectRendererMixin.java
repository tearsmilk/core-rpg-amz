package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.Hooks;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.WeatherRenderState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Погода для модулей: не рисовать полосы дождя и снега. Проход «weather» целиком не отменяем — в нём же рисуется
 * граница мира. Брызги дождя и звук — отдельно (тик), их этот крючок не трогает.
 */
@Mixin(WeatherEffectRenderer.class)
public abstract class WeatherEffectRendererMixin {

    @Inject(method = "render(Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/client/renderer/state/WeatherRenderState;)V",
            at = @At("HEAD"), cancellable = true)
    private void hokaOdyssey$weather(MultiBufferSource buffers, Vec3 cameraPos, WeatherRenderState state, CallbackInfo ci) {
        if (Hooks.hideWeather()) ci.cancel();
    }
}
