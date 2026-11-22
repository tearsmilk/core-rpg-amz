package ink.hoka.odyssey.client.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.framegraph.FramePass;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.systems.RenderSystem;
import ink.hoka.odyssey.client.Hooks;
import ink.hoka.odyssey.client.api.SkyFrame;
import net.minecraft.client.Camera;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.LevelRenderState;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Небо, облака для модулей. Ваниль строит кадр графом проходов (FrameGraphBuilder): clear → sky → main → … .
 * Если у модулей есть крючок неба, перед ванильным проходом «sky» добавляем свой «hoka_sky» (тот же основной буфер):
 * в нём зовём крючки, и если кто-то вернул true — ванильный SkyRenderer в этом кадре ничего не рисует
 * ({@link SkyRendererMixin}). Ванильный проход не трогаем — совместимо с модами, которые в него вмешиваются.
 * Где ваниль неба не рисует (лава, слепота, skybox none, туман босса) — проход всё равно добавляем, с причиной.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererSkyMixin {

    @Shadow @Final private LevelTargetBundle targets;
    @Shadow @Final private LevelRenderState levelRenderState;
    @Shadow private ClientLevel level;
    @Shadow private SkyRenderer skyRenderer;

    @Shadow protected abstract boolean doesMobEffectBlockSky(Camera camera);

    @Unique private Camera hokaOdyssey$camera;
    @Unique private Matrix4f hokaOdyssey$view;
    @Unique private Matrix4f hokaOdyssey$proj;
    @Unique private GpuBufferSlice hokaOdyssey$fog;
    @Unique private float hokaOdyssey$pt;
    @Unique private boolean hokaOdyssey$skyAdded;

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void hokaOdyssey$frame(GraphicsResourceAllocator allocator, DeltaTracker delta, boolean renderOutline, Camera camera,
                                   Matrix4f view, Matrix4f proj, Matrix4f cullProj, GpuBufferSlice fog, Vector4f fogColor,
                                   boolean renderSky, CallbackInfo ci) {
        Hooks.skyClaimed = false;
        hokaOdyssey$skyAdded = false;
        if (!Hooks.hasSky()) return;
        hokaOdyssey$camera = camera;
        hokaOdyssey$view = new Matrix4f(view);
        hokaOdyssey$proj = new Matrix4f(proj);
        hokaOdyssey$fog = fog;
        hokaOdyssey$pt = delta.getGameTimeDeltaPartialTick(false);
    }

    /** Ванильный проход неба: свой проход перед ним (условия пропуска — как у ванили). */
    @Inject(method = "addSkyPass", at = @At("HEAD"))
    private void hokaOdyssey$sky(FrameGraphBuilder fg, Camera camera, GpuBufferSlice fog, CallbackInfo ci) {
        if (!Hooks.hasSky() || hokaOdyssey$camera == null) return;
        FogType fluid = camera.getFluidInCamera();
        SkyFrame.SkipReason skip;
        if (fluid == FogType.POWDER_SNOW || fluid == FogType.LAVA) skip = SkyFrame.SkipReason.FLUID;
        else if (doesMobEffectBlockSky(camera)) skip = SkyFrame.SkipReason.BLIND;
        else if (levelRenderState.skyRenderState.skybox == DimensionType.Skybox.NONE || skyRenderer == null) skip = SkyFrame.SkipReason.NO_SKYBOX;
        else skip = SkyFrame.SkipReason.NONE;
        hokaOdyssey$addPass(fg, skip);
    }

    /** Туман босса: ваниль не зовёт addSkyPass вовсе — добавляем свой проход перед основным. */
    @Inject(method = "addMainPass", at = @At("HEAD"))
    private void hokaOdyssey$bossFogSky(FrameGraphBuilder fg, Frustum frustum, Matrix4f view, GpuBufferSlice fog, boolean renderOutline,
                                        LevelRenderState state, DeltaTracker delta, ProfilerFiller profiler, CallbackInfo ci) {
        if (!hokaOdyssey$skyAdded && Hooks.hasSky() && hokaOdyssey$camera != null) hokaOdyssey$addPass(fg, SkyFrame.SkipReason.BOSS_FOG);
    }

    @Unique
    private void hokaOdyssey$addPass(FrameGraphBuilder fg, SkyFrame.SkipReason skip) {
        hokaOdyssey$skyAdded = true;
        Camera camera = hokaOdyssey$camera;
        Vec3 pos = camera.position();
        GpuBufferSlice fog = hokaOdyssey$fog;
        ClientLevel lvl = level;
        SkyRenderer renderer = skyRenderer;
        Matrix4f view = hokaOdyssey$view, proj = hokaOdyssey$proj;
        float pt = hokaOdyssey$pt;
        long gameTime = levelRenderState.gameTime;
        hokaOdyssey$camera = null;                      // не держать камеру/мир до следующего кадра
        if (lvl == null) return;
        FramePass pass = fg.addPass("hoka_sky");
        targets.main = pass.readsAndWrites(targets.main);
        pass.executes(() -> {
            RenderSystem.setShaderFog(fog);
            RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
            SkyFrame frame = new SkyFrame(view, proj, pos.x, pos.y, pos.z, pt, gameTime, lvl.dimension().identifier(),
                    lvl.dimensionType(), levelRenderState.skyRenderState, renderer, fog,
                    main.getColorTextureView(), main.getDepthTextureView(), skip);
            Hooks.skyClaimed = Hooks.sky(frame);
        });
    }

    @Inject(method = "addCloudsPass", at = @At("HEAD"), cancellable = true)
    private void hokaOdyssey$clouds(FrameGraphBuilder fg, CloudStatus status, Vec3 cameraPos, long gameTime, float partialTick,
                                    int color, float height, CallbackInfo ci) {
        if (Hooks.hideClouds()) ci.cancel();
    }
}
