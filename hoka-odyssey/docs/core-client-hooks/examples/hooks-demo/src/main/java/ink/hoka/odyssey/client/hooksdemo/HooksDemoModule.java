package ink.hoka.odyssey.client.hooksdemo;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import ink.hoka.odyssey.client.api.ClientContext;
import ink.hoka.odyssey.client.api.ClientModule;
import ink.hoka.odyssey.client.api.ModulePipeline;
import ink.hoka.odyssey.client.api.SkyFrame;
import ink.hoka.odyssey.client.api.WorldStage;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Pose;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.OptionalInt;

/**
 * Проверка крючков ядра 0.10 (патч core-client-hooks). Что видно в игре:
 * <ul>
 *   <li>небо — свой градиент (тёмно-синий → фиолетовый) своим шейдером вместо ванильного; облаков и дождя нет;</li>
 *   <li>туман — того же цвета, что низ неба (горизонт без шва);</li>
 *   <li>другие игроки (и сам в F5) медленно вращаются вокруг оси «спина — грудь» (крен), руки и ноги анимируются как обычно;</li>
 *   <li>в полёте (креатив) клавиша бега переключает «лёжа» (поза SWIMMING, хитбокс 0,6); сам бег при этом не включается;</li>
 *   <li>HUD слева вверху — счётчики крючков и id мира.</li>
 * </ul>
 * Если ядро старое (нет onSky) — модуль пишет это в лог и ничего не делает.
 */
public final class HooksDemoModule implements ClientModule {

    private static final String VSH = """
            #version 330
            in vec3 Position;
            void main() {
                gl_Position = vec4(Position.xy, 0.0, 1.0);
            }
            """;

    private static final String FSH = """
            #version 330
            #moj_import <minecraft:globals.glsl>
            out vec4 fragColor;
            void main() {
                float t = clamp(gl_FragCoord.y / ScreenSize.y, 0.0, 1.0);
                vec3 bottom = vec3(0.06, 0.09, 0.19);
                vec3 top = vec3(0.22, 0.08, 0.30);
                fragColor = vec4(mix(bottom, top, t), 1.0);
            }
            """;

    private ClientContext ctx;
    private ModulePipeline sky;
    private GpuBuffer triangle;
    private long skyFrames, travels;
    private String world = "-";
    private boolean prone, sprintWasDown, startMainLogged;

    @Override
    public void enable(ClientContext ctx) {
        this.ctx = ctx;
        try {
            ClientContext.class.getMethod("onSky", ClientContext.SkyHook.class);
        } catch (NoSuchMethodException e) {
            ctx.logger().warn("hooks-demo: ядро без крючков неба (нужно 0.10+) — ничего не делаю");
            return;
        }

        RenderPipeline p = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("hoka_odyssey", "pipeline/hooks_demo_sky"))
                .withVertexShader(Identifier.fromNamespaceAndPath("hoka_odyssey", "hooks_demo/sky_v1"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("hoka_odyssey", "hooks_demo/sky_v1"))
                .withUniform("Globals", UniformType.UNIFORM_BUFFER)
                .withVertexFormat(DefaultVertexFormat.POSITION, VertexFormat.Mode.TRIANGLES)
                .withDepthWrite(false)
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                .withCull(false)
                .withoutBlend()
                .build();
        sky = ctx.pipeline(p, (id, type) -> type == ShaderType.VERTEX ? VSH : FSH);

        // один треугольник на весь экран (в координатах экрана -1..1)
        ByteBuffer b = MemoryUtil.memAlloc(3 * 3 * 4);
        try {
            b.putFloat(-1).putFloat(-1).putFloat(0)
             .putFloat(3).putFloat(-1).putFloat(0)
             .putFloat(-1).putFloat(3).putFloat(0)
             .flip();
            triangle = ctx.track(RenderSystem.getDevice().createBuffer(() -> "hooks-demo sky", GpuBuffer.USAGE_VERTEX, b));
        } finally {
            MemoryUtil.memFree(b);
        }

        ctx.onSky(this::sky);
        ctx.onFog((fog, camera, pt) -> {
            fog.r = 0.06f;
            fog.g = 0.09f;
            fog.b = 0.19f;
        });
        ctx.onClouds(() -> true);
        ctx.onWeather(() -> true);
        ctx.onWorld(id -> {
            world = id == null ? "-" : id.toString();
            ctx.logger().info("hooks-demo: мир {}", world);
        });
        ctx.onWorldPass(WorldStage.START_MAIN, c -> {
            if (!startMainLogged) ctx.logger().info("hooks-demo: START_MAIN работает");
            startMainLogged = true;
        });
        ctx.onAvatarRotations((entityId, state, pose, bodyRot, scale) -> {
            float angle = (System.currentTimeMillis() % 8000L) / 8000f * 360f;
            float h = 0.9f * scale;                        // вокруг середины тела
            pose.translate(0, h, 0);
            pose.mulPose(Axis.YP.rotationDegrees(180f - bodyRot));
            pose.mulPose(Axis.ZP.rotationDegrees(angle));
            pose.mulPose(Axis.YP.rotationDegrees(bodyRot - 180f));
            pose.translate(0, -h, 0);
            return false;                                   // ваниль доделает курс тела и позу
        });
        ctx.onInput(in -> {
            var mc = Minecraft.getInstance();
            boolean flying = mc.player != null && mc.player.getAbilities().flying;
            if (in.sprint && !sprintWasDown && flying) {
                prone = !prone;
                ctx.forceLocalPose(prone ? Pose.SWIMMING : null);
            }
            if (!flying && prone) {
                prone = false;
                ctx.forceLocalPose(null);
            }
            sprintWasDown = in.sprint;
            if (prone) in.sprint = false;                   // бег не включать: клавиша занята под «лёжа»
        });
        ctx.onLocalTravel(player -> travels++);
        ctx.onHud((g, delta) -> {
            var font = Minecraft.getInstance().font;
            g.drawString(font, Component.literal("hooks-demo: небо " + skyFrames + ", travel " + travels
                    + (prone ? ", лёжа" : "") + ", мир " + world), 4, 4, 0xFFE8F0FF, true);
        });
        ctx.logger().info("hooks-demo: крючки поставлены");
    }

    private boolean sky(SkyFrame f) {
        if (f.vanillaSkip != SkyFrame.SkipReason.NONE) return false;   // лава, слепота и т.п. — как в ванили
        skyFrames++;
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        if (!sky.prepare()) {                                          // шейдер не собрался — хотя бы сплошной цвет
            encoder.clearColorTexture(f.color.texture(), 0xFF101830);
            return true;
        }
        try (RenderPass pass = encoder.createRenderPass(() -> "hooks-demo sky", f.color, OptionalInt.empty())) {
            pass.setPipeline(sky.pipeline());
            RenderSystem.bindDefaultUniforms(pass);
            pass.setVertexBuffer(0, triangle);
            pass.draw(0, 3);
        }
        return true;
    }
}
