package ink.hoka.odyssey.client.api;

import com.mojang.blaze3d.pipeline.RenderPipeline;

/**
 * RenderPipeline модуля со своими шейдерами ({@link ClientContext#pipeline}). Использование:
 * <pre>{@code
 * if (p.prepare()) { pass.setPipeline(p.pipeline()); ... }
 * }</pre>
 */
public interface ModulePipeline {

    RenderPipeline pipeline();

    /**
     * Собрать шейдеры, если игра сбросила кэш (F3+T, смена ресурспака), — вызывать перед каждым использованием,
     * это дёшево (готовый — просто берётся из кэша). false — шейдер не собрался (ошибка в логе) или модуль выгружен:
     * не рисовать этим pipeline (иначе игра молча пропустит отрисовку).
     */
    boolean prepare();

    /** Модуль ещё загружен и последняя сборка удалась. */
    boolean isValid();
}
