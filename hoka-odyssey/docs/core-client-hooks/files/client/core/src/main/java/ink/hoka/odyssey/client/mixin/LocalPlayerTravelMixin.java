package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.Hooks;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Перед расчётом движения своего игрока ({@code LivingEntity.travel}) — модуль меняет скорость в тот же тик
 * (тяга, гравитация), а не на тик позже, как из onTick (END_CLIENT_TICK — уже после движения).
 */
@Mixin(LivingEntity.class)
public abstract class LocalPlayerTravelMixin {

    @Inject(method = "travel", at = @At("HEAD"))
    private void hokaOdyssey$travel(Vec3 input, CallbackInfo ci) {
        if (Hooks.hasTravel() && (Object) this instanceof LocalPlayer p) Hooks.travel(p);
    }
}
