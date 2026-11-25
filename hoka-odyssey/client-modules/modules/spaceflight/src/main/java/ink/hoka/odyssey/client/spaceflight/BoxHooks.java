package ink.hoka.odyssey.client.spaceflight;

import ink.hoka.odyssey.client.api.ClientContext;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Крючки рамки и глаз (ClientContext.onEntityBox / onEntityEye — мод 0.54+). Отдельный класс: в старом ядре
 * интерфейсов EntityBoxHook/EntityEyeHook нет, и он не загружается вовсе — модуль сначала проверяет их рефлексией.
 */
final class BoxHooks {
    private BoxHooks() {}

    /** Есть ли в ядре onEntityBox и onEntityEye (не пустые default-методы интерфейса). */
    static boolean available(ClientContext ctx) {
        try {
            Class<?> box = Class.forName("ink.hoka.odyssey.client.api.ClientContext$EntityBoxHook", false,
                    ClientContext.class.getClassLoader());
            Class<?> eye = Class.forName("ink.hoka.odyssey.client.api.ClientContext$EntityEyeHook", false,
                    ClientContext.class.getClassLoader());
            return !ctx.getClass().getMethod("onEntityBox", box).getDeclaringClass().isInterface()
                    && !ctx.getClass().getMethod("onEntityEye", eye).getDeclaringClass().isInterface();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }

    static void install(ClientContext ctx, BiFunction<Entity, Vec3, AABB> box, Function<Entity, Vec3> eye) {
        ctx.onEntityBox(box::apply);
        ctx.onEntityEye(eye::apply);
    }
}
