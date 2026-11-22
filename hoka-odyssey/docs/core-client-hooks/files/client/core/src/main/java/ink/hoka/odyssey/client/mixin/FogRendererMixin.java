package ink.hoka.odyssey.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import ink.hoka.odyssey.client.Hooks;
import ink.hoka.odyssey.client.api.FogState;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;

/**
 * Туман для модулей. {@code FogRenderer.setupFog} считает цвет и расстояния и пишет их в UBO тумана
 * ({@code updateBuffer}); тот же объект цвета он возвращает — им игра очищает кадр. Оборачиваем запись в UBO:
 * отдаём значения крючкам, пишем изменённые и правим объект цвета на месте — меняется и цвет очистки.
 */
@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {

    @Unique private Camera hokaOdyssey$camera;
    @Unique private float hokaOdyssey$pt;

    @Inject(method = "setupFog", at = @At("HEAD"))
    private void hokaOdyssey$begin(Camera camera, int renderDistance, DeltaTracker delta, float darken, ClientLevel level,
                                   CallbackInfoReturnable<Vector4f> cir) {
        hokaOdyssey$camera = camera;
        hokaOdyssey$pt = delta.getGameTimeDeltaPartialTick(false);
    }

    @WrapOperation(method = "setupFog", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/fog/FogRenderer;updateBuffer(Ljava/nio/ByteBuffer;ILorg/joml/Vector4f;FFFFFF)V"))
    private void hokaOdyssey$fog(FogRenderer self, ByteBuffer buf, int offset, Vector4f color, float envStart, float envEnd,
                                 float rdStart, float rdEnd, float skyEnd, float cloudEnd, Operation<Void> original) {
        if (Hooks.hasFog() && hokaOdyssey$camera != null) {
            FogState s = new FogState();
            s.r = color.x;
            s.g = color.y;
            s.b = color.z;
            s.a = color.w;
            s.envStart = envStart;
            s.envEnd = envEnd;
            s.rdStart = rdStart;
            s.rdEnd = rdEnd;
            s.skyEnd = skyEnd;
            s.cloudEnd = cloudEnd;
            Hooks.fog(s, hokaOdyssey$camera, hokaOdyssey$pt);
            color.set(s.r, s.g, s.b, s.a);                     // тот же объект вернёт setupFog — цвет очистки кадра
            envStart = s.envStart;
            envEnd = s.envEnd;
            rdStart = s.rdStart;
            rdEnd = s.rdEnd;
            skyEnd = s.skyEnd;
            cloudEnd = s.cloudEnd;
        }
        hokaOdyssey$camera = null;
        original.call(self, buf, offset, color, envStart, envEnd, rdStart, rdEnd, skyEnd, cloudEnd);
    }
}
