package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.Hooks;
import ink.hoka.odyssey.client.api.InputState;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ввод своего игрока для модулей: после чтения клавиш игрой (KeyboardInput.tick), до движения и до отправки
 * серверу (LocalPlayer шлёт keyPresses, когда они меняются). Наследуемся от ClientInput — так видно его поля.
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends ClientInput {

    @Inject(method = "tick", at = @At("TAIL"))
    private void hokaOdyssey$input(CallbackInfo ci) {
        if (!Hooks.hasInput()) return;
        Input k = keyPresses;
        InputState s = new InputState();
        s.forward = k.forward();
        s.backward = k.backward();
        s.left = k.left();
        s.right = k.right();
        s.jump = k.jump();
        s.shift = k.shift();
        s.sprint = k.sprint();
        s.moveStrafe = moveVector.x;
        s.moveForward = moveVector.y;
        Hooks.input(s);
        Input n = new Input(s.forward, s.backward, s.left, s.right, s.jump, s.shift, s.sprint);
        boolean keysChanged = !n.equals(k);
        boolean vectorChanged = s.moveStrafe != moveVector.x || s.moveForward != moveVector.y;
        keyPresses = n;
        if (vectorChanged) {
            moveVector = new Vec2(s.moveStrafe, s.moveForward);
        } else if (keysChanged) {                           // как в KeyboardInput.tick
            float f = n.forward() == n.backward() ? 0 : n.forward() ? 1 : -1;
            float l = n.left() == n.right() ? 0 : n.left() ? 1 : -1;
            moveVector = new Vec2(l, f).normalized();
        }
    }
}
