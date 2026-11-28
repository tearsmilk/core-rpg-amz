package ink.hoka.odyssey.client.skyspike;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import ink.hoka.odyssey.client.api.ClientContext;
import ink.hoka.odyssey.client.api.ClientModule;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Спайк пути неба без патча ядра: в {@code WorldRenderEvents.START_MAIN} (после загрузки секций, до отрисовки
 * террейна) рисуем полноэкранный треугольник своим шейдером в основной кадр — без теста и записи глубины.
 * Террейн рисуется после и закрывает «небо» там, где есть блоки. Направление взгляда на пиксель восстанавливается из
 * ProjMat и ModelViewMat (поворот камеры вместе с roll), поэтому сетка «неба» (линии через 15°) должна стоять на месте
 * при повороте головы. Шейдер — из строки ({@code precompilePipeline(p, ShaderSource)}), не из ресурспака.
 *
 * <p>События Fabric нельзя снять: слушатель регистрируется один раз на весь запуск игры, а работу берёт из
 * {@link System#getProperties()} (общий для всех загрузчиков классов модулей объект; ключ {@value #KEY}).
 * При выгрузке модуль кладёт туда null — старый слушатель молчит, повторная загрузка не добавляет второй.
 * Это и есть утечка, которую уберёт хук ядра onWorldPass (WP0): один закреплённый слушатель на запуск.
 */
public final class SkySpikeModule implements ClientModule {

    static final String KEY = "hoka.skyspike.startMain";
    private static final String REGISTERED = "hoka.skyspike.registered";
    private static final String LOADS = "hoka.skyspike.loads";

    private static final String VSH = """
            #version 330
            layout(std140) uniform DynamicTransforms {
                mat4 ModelViewMat;
                vec4 ColorModulator;
                vec3 ModelOffset;
                mat4 TextureMat;
            };
            layout(std140) uniform Projection {
                mat4 ProjMat;
            };
            in vec3 Position;
            out vec3 viewDir;
            void main() {
                gl_Position = vec4(Position.xy, 0.0, 1.0);
                vec4 v = inverse(ProjMat) * vec4(Position.xy, 1.0, 1.0);
                // ModelViewMat — только поворот камеры: обратный поворот = транспонирование
                viewDir = transpose(mat3(ModelViewMat)) * (v.xyz / v.w);
            }
            """;

    private static final String FSH = """
            #version 330
            layout(std140) uniform DynamicTransforms {
                mat4 ModelViewMat;
                vec4 ColorModulator;
                vec3 ModelOffset;
                mat4 TextureMat;
            };
            in vec3 viewDir;
            out vec4 fragColor;
            const float PI = 3.14159265;
            void main() {
                vec3 d = normalize(viewDir);
                float el = asin(clamp(d.y, -1.0, 1.0));
                float az = atan(d.z, d.x);
                // градиент: зенит — тёмно-синий, горизонт — пурпурный, низ — почти чёрный
                vec3 c = d.y > 0.0 ? mix(vec3(0.45, 0.05, 0.45), vec3(0.02, 0.03, 0.18), pow(d.y, 0.6))
                                   : mix(vec3(0.45, 0.05, 0.45), vec3(0.01), pow(-d.y, 0.5));
                // сетка через 15° по азимуту и высоте — проверка, что направление стоит на месте
                float step15 = PI / 12.0;
                float ge = abs(fract(el / step15 + 0.5) - 0.5) * step15;
                float ga = abs(fract(az / step15 + 0.5) - 0.5) * step15 * cos(el);
                float line = 1.0 - smoothstep(0.0, 0.004, min(ge, ga));
                c = mix(c, vec3(0.9, 0.9, 0.3), line * 0.8);
                fragColor = vec4(c, 1.0) * ColorModulator;
            }
            """;

    // Кэш шейдеров игры (GlDevice.shaderCache) — по id шейдера, а не по тексту, и чистится только F3+T/сменой пака.
    // С постоянным id после горячей перезагрузки модуля с изменённым GLSL игра молча берёт старый шейдер, поэтому в id —
    // хэш исходников: новый текст — новый id. Цена — старая программа остаётся в кэше до F3+T (единицы КБ на перезагрузку).
    private static final String SRC_HASH = Integer.toHexString((VSH + FSH).hashCode());
    private static final Identifier SHADER = Identifier.fromNamespaceAndPath("hoka_odyssey", "skyspike/sky_" + SRC_HASH);
    private static final RenderPipeline PIPELINE = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("hoka_odyssey", "pipeline/skyspike_" + SRC_HASH))
            .withVertexShader(SHADER)
            .withFragmentShader(SHADER)
            .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
            .withUniform("Projection", UniformType.UNIFORM_BUFFER)
            .withVertexFormat(DefaultVertexFormat.POSITION, VertexFormat.Mode.TRIANGLES)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .withCull(false)
            .withoutBlend()
            .build();

    private ClientContext ctx;
    private GpuBuffer triangle;
    private final AtomicLong frames = new AtomicLong();
    private boolean warned;

    @Override
    public void enable(ClientContext ctx) {
        this.ctx = ctx;
        synchronized (System.getProperties()) {
            if (System.getProperties().get(REGISTERED) == null) {
                System.getProperties().put(REGISTERED, Boolean.TRUE);
                WorldRenderEvents.START_MAIN.register(c -> {
                    if (System.getProperties().get(KEY) instanceof Runnable r) r.run();
                });
                ctx.logger().info("skyspike: слушатель START_MAIN зарегистрирован");
            }
        }
        System.getProperties().put(KEY, (Runnable) this::draw);
        // сколько раз модуль загружался за запуск игры (проверка горячей перезагрузки: слушатель при этом один)
        int loads = (System.getProperties().get(LOADS) instanceof Integer n ? n : 0) + 1;
        System.getProperties().put(LOADS, loads);
        ctx.onHud((g, delta) -> g.drawString(Minecraft.getInstance().font,
                "skyspike · START_MAIN кадров " + frames.get() + " · загрузок " + loads, 4, 4, 0xC0FFFF60, true));
        ctx.logger().info("skyspike: загружен (загрузка {} за запуск игры)", loads);
    }

    @Override
    public void disable() {
        System.getProperties().remove(KEY);
        if (triangle != null) {
            triangle.close();
            triangle = null;
        }
    }

    private void draw() {
        try {
            var device = RenderSystem.getDevice();
            // precompilePipeline — computeIfAbsent: дёшево каждый кадр, и переживает сброс кэша (F3+T, ресурспак)
            CompiledRenderPipeline compiled = device.precompilePipeline(PIPELINE,
                    (id, type) -> type == ShaderType.VERTEX ? VSH : FSH);
            if (!compiled.isValid()) {
                if (!warned) ctx.logger().error("skyspike: шейдер не собрался — проход пропущен");
                warned = true;
                return;
            }
            if (triangle == null) {
                ByteBuffer b = ByteBuffer.allocateDirect(9 * Float.BYTES).order(ByteOrder.nativeOrder());
                b.putFloat(-1).putFloat(-1).putFloat(0).putFloat(3).putFloat(-1).putFloat(0)
                        .putFloat(-1).putFloat(3).putFloat(0).flip();
                triangle = device.createBuffer(() -> "skyspike fullscreen", GpuBuffer.USAGE_VERTEX, b);
            }
            var transforms = RenderSystem.getDynamicUniforms().writeTransform(
                    RenderSystem.getModelViewMatrix(), new Vector4f(1, 1, 1, 1), new Vector3f(), new Matrix4f());
            var target = Minecraft.getInstance().getMainRenderTarget();
            try (RenderPass pass = device.createCommandEncoder().createRenderPass(() -> "skyspike sky",
                    target.getColorTextureView(), OptionalInt.empty(), target.getDepthTextureView(), OptionalDouble.empty())) {
                pass.setPipeline(PIPELINE);
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("DynamicTransforms", transforms);
                pass.setVertexBuffer(0, triangle);
                pass.draw(0, 3);
            }
            if (frames.incrementAndGet() == 1) ctx.logger().info("skyspike: первый кадр нарисован в START_MAIN");
        } catch (RuntimeException e) {
            // упасть внутри кадра нельзя — выключаемся сами и пишем причину
            System.getProperties().remove(KEY);
            ctx.logger().error("skyspike: проход упал, выключаю", e);
        }
    }
}
