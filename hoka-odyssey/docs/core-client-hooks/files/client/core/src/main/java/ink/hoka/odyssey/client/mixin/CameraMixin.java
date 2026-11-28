package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.Hooks;
import ink.hoka.odyssey.client.api.CameraState;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Камера для модулей: после расчёта игрой — курс, наклон, положение и КРЕН от модулей. Крен подставляется в сам
 * поворот камеры (третий угол rotationYXZ), поэтому видимость (отсечение) и звук считаются уже с ним.
 * Модуль может задать и полный поворот кватернионом ({@link CameraState#rotation}) — без вырождения при ±90°.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {

    @Shadow protected abstract void setRotation(float yRot, float xRot);
    @Shadow protected abstract void setPosition(double x, double y, double z);
    @Shadow public abstract Vec3 position();
    @Shadow public abstract float yRot();
    @Shadow public abstract float xRot();
    @Shadow @Final private Quaternionf rotation;
    @Shadow @Final private Vector3f forwards;
    @Shadow @Final private Vector3f up;
    @Shadow @Final private Vector3f left;

    @Inject(method = "setup", at = @At("TAIL"))
    private void hokaOdyssey$setup(Level level, Entity entity, boolean detached, boolean mirror, float partialTick, CallbackInfo ci) {
        if (!Hooks.hasCamera()) {
            if (Hooks.roll != 0) { Hooks.roll = 0; setRotation(yRot(), xRot()); }
            return;
        }
        CameraState s = new CameraState();
        Vec3 p = position();
        s.x = p.x;
        s.y = p.y;
        s.z = p.z;
        s.yaw = yRot();
        s.pitch = xRot();
        s.detached = detached;
        Hooks.camera(s, partialTick);
        Hooks.roll = (float) Math.toRadians(s.roll);
        setRotation(s.yaw, s.pitch);
        if (s.rotation != null) {                           // полный поворот от модуля — вместо углов Эйлера
            rotation.set(s.rotation).normalize();
            new Vector3f(0, 0, -1).rotate(rotation, forwards);
            new Vector3f(0, 1, 0).rotate(rotation, up);
            new Vector3f(-1, 0, 0).rotate(rotation, left);
        }
        setPosition(s.x, s.y, s.z);
    }

    @ModifyArg(method = "setRotation", at = @At(value = "INVOKE",
            target = "Lorg/joml/Quaternionf;rotationYXZ(FFF)Lorg/joml/Quaternionf;"), index = 2)
    private float hokaOdyssey$roll(float roll) {
        return Hooks.roll;
    }
}
