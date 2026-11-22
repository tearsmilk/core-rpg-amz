package ink.hoka.odyssey.client.hooksdemo;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import ink.hoka.odyssey.client.api.ClientContext;
import ink.hoka.odyssey.client.api.ClientModule;
import net.minecraft.client.Minecraft;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Проверка хуков патча ядра (docs/core-client-hooks) на стенде tools/devclient:
 * <ul>
 *   <li>onSky — вместо ванильного неба сплошной тёмно-синий цвет (очистка цвета основного кадра в проходе неба);</li>
 *   <li>onFog — туман (он же цвет очистки кадра) того же цвета: дальний террейн уходит в «небо» без светлой каймы;</li>
 *   <li>onAvatarRotations — чужие аватары лежат на боку (поворот на 90° вокруг оси взгляда тела), ванильный
 *       поворот при этом сохраняется (хук возвращает false);</li>
 *   <li>onWorld — строка в логе при входе в мир и смене мира.</li>
 * </ul>
 * Пишет в лог первый вызов каждого хука — по этим строкам скрипты стенда видят, что хук работает.
 */
public final class HooksDemoModule implements ClientModule {

    private static final int SKY = 0xFF101A3A;                 // ARGB
    private static final float R = 0x10 / 255f, G = 0x1A / 255f, B = 0x3A / 255f;

    private final AtomicLong sky = new AtomicLong(), fog = new AtomicLong(), avatars = new AtomicLong();

    @Override
    public void enable(ClientContext ctx) {
        ctx.onSky(f -> {
            var target = Minecraft.getInstance().getMainRenderTarget();
            RenderSystem.getDevice().createCommandEncoder().clearColorTexture(target.getColorTexture(), SKY);
            if (sky.incrementAndGet() == 1) ctx.logger().info("hooks-demo: onSky работает");
            return true;                                           // ванильное небо не рисовать
        });
        ctx.onFog((s, camera, pt) -> {
            s.r = R;
            s.g = G;
            s.b = B;
            if (fog.incrementAndGet() == 1) ctx.logger().info("hooks-demo: onFog работает");
        });
        ctx.onAvatarRotations((entityId, state, pose, bodyRot, scale) -> {
            var me = Minecraft.getInstance().player;
            if (me != null && me.getId() == entityId) return false; // себя (F5) не трогаем
            pose.mulPose(Axis.ZP.rotationDegrees(90));
            if (avatars.incrementAndGet() == 1) ctx.logger().info("hooks-demo: onAvatarRotations работает (игрок {})", entityId);
            return false;
        });
        ctx.onWorld(dim -> ctx.logger().info("hooks-demo: мир {}", dim == null ? "— (выход)" : dim));
        ctx.onHud((g, delta) -> g.drawString(Minecraft.getInstance().font,
                "hooks-demo · небо " + sky.get() + " · туман " + fog.get() + " · аватары " + avatars.get(),
                4, 28, 0xC060FFFF, true));
        ctx.logger().info("hooks-demo: загружен");
    }
}
