package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.Hooks;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Поза своего игрока от модуля ({@code forceLocalPose}). Клиент сам решает позу своего игрока каждый тик
 * ({@code Player.updatePlayerPose} в конце {@code Player.tick}) — поза от сервера перетирается, а SWIMMING вне воды
 * ваниль не даёт. Задана поза — ставим её вместо ванильного выбора (хитбокс и высота глаз — по ней).
 */
@Mixin(Player.class)
public abstract class PlayerPoseMixin {

    @Inject(method = "updatePlayerPose", at = @At("HEAD"), cancellable = true)
    private void hokaOdyssey$pose(CallbackInfo ci) {
        if (!((Object) this instanceof LocalPlayer)) return;
        Pose forced = Hooks.forcedPose();
        if (forced == null) return;
        ((Entity) (Object) this).setPose(forced);
        ci.cancel();
    }
}
