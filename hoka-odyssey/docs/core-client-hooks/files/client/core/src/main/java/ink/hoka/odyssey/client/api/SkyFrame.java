package ink.hoka.odyssey.client.api;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.SkyRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.dimension.DimensionType;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

/**
 * Кадр неба для {@link ClientContext.SkyHook}. Действителен только во время вызова крючка (не сохранять).
 * Рисовать — в {@link #color} (и при желании с {@link #depth}), как ванильный SkyRenderer: свой RenderPass через
 * {@code RenderSystem.getDevice().createCommandEncoder().createRenderPass(...)}. Глубина в этот момент — 1.0
 * (очищена), террейн рисуется после неба. Projection, Fog (туман этого кадра) и Globals уже выставлены в
 * RenderSystem — в своём RenderPass привязать их вызовом {@code RenderSystem.bindDefaultUniforms(pass)}.
 */
public final class SkyFrame {

    /** Почему ванильное небо в этом кадре НЕ рисовалось бы. */
    public enum SkipReason {
        /** Ваниль рисует небо. */
        NONE,
        /** Камера в лаве или порошковом снегу. */
        FLUID,
        /** Слепота или тьма. */
        BLIND,
        /** У типа мира skybox = none (или SkyRenderer ещё не создан). */
        NO_SKYBOX,
        /** Туман босса (визер, дракон): ваниль не делает проход неба вовсе. */
        BOSS_FOG
    }

    /** Поворот камеры (вид, без переноса), крен уже учтён. Мир → камера. */
    public final Matrix4fc modelView;
    /** Проекция кадра (с покачиванием камеры при ходьбе). */
    public final Matrix4fc projection;
    /** Положение камеры в мире. */
    public final double camX, camY, camZ;
    public final float partialTick;
    /** Игровое время мира (тики). */
    public final long gameTime;
    /** Мир: {@code level.dimension().identifier()} — у Minestom свой для каждого мира. */
    public final Identifier dimension;
    public final DimensionType type;
    /** Ванильное небо этого кадра (углы солнца/луны/звёзд, цвета) — можно взять и смешать со своим. */
    public final SkyRenderState vanilla;
    /** Ванильный рендер неба: его публичные методы (renderSkyDisc, renderSunMoonAndStars…) можно звать из крючка. */
    public final @Nullable SkyRenderer renderer;
    /** UBO тумана кадра (уже выставлен через RenderSystem.setShaderFog). */
    public final GpuBufferSlice fog;
    /** Цвет и глубина основного буфера кадра. */
    public final GpuTextureView color, depth;
    public final SkipReason vanillaSkip;

    public SkyFrame(Matrix4fc modelView, Matrix4fc projection, double camX, double camY, double camZ, float partialTick,
                    long gameTime, Identifier dimension, DimensionType type, SkyRenderState vanilla,
                    @Nullable SkyRenderer renderer, GpuBufferSlice fog, GpuTextureView color, GpuTextureView depth,
                    SkipReason vanillaSkip) {
        this.modelView = modelView;
        this.projection = projection;
        this.camX = camX;
        this.camY = camY;
        this.camZ = camZ;
        this.partialTick = partialTick;
        this.gameTime = gameTime;
        this.dimension = dimension;
        this.type = type;
        this.vanilla = vanilla;
        this.renderer = renderer;
        this.fog = fog;
        this.color = color;
        this.depth = depth;
        this.vanillaSkip = vanillaSkip;
    }
}
