package ink.hoka.odyssey.client.mixin;

import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.opengl.GlRenderPipeline;
import com.mojang.blaze3d.opengl.GlShaderModule;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * Кэши GlDevice — чтобы при выгрузке модуля убрать его pipeline и шейдеры ({@code Pipelines}). Иначе обновлённый
 * модуль с тем же id шейдера получил бы старый скомпилированный шейдер (кэш — по id, типу и defines) до F3+T.
 * Ключ кэша шейдеров ({@code GlDevice.ShaderCompilationKey}) недоступен из нашего пакета — отсюда {@code Map<?, …>}.
 */
@Mixin(GlDevice.class)
public interface GlDeviceAccessor {

    @Accessor("pipelineCache")
    Map<RenderPipeline, GlRenderPipeline> hokaOdyssey$pipelineCache();

    @Accessor("shaderCache")
    Map<?, GlShaderModule> hokaOdyssey$shaderCache();
}
