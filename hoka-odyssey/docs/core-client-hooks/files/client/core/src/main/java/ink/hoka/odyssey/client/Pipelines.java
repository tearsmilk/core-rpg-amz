package ink.hoka.odyssey.client;

import com.mojang.blaze3d.opengl.GlProgram;
import com.mojang.blaze3d.opengl.GlRenderPipeline;
import com.mojang.blaze3d.opengl.GlShaderModule;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.RenderSystem;
import ink.hoka.odyssey.client.api.ModulePipeline;
import ink.hoka.odyssey.client.mixin.GlDeviceAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import java.io.Reader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * RenderPipeline модулей со своими шейдерами. Игра при каждой перезагрузке ресурсов (F3+T, смена пака) чистит кэш
 * шейдеров и заново собирает только ванильные — поэтому модуль перед использованием зовёт {@link ModulePipeline#prepare()}:
 * {@code precompilePipeline} — это computeIfAbsent, готовый pipeline берётся из кэша.
 * Исходник модуля проходит через препроцессор игры: {@code #moj_import <ns:path>} берётся из ресурсов
 * ({@code assets/<ns>/shaders/include/<path>}), как у ванильных шейдеров.
 */
final class Pipelines {

    private Pipelines() {}

    private static final List<Impl> ALL = new ArrayList<>();

    static ModulePipeline create(String owner, RenderPipeline pipeline, ShaderSource source) {
        Impl p = new Impl(owner, pipeline, withImports(source));
        ALL.add(p);
        return p;
    }

    static void removeOwner(String owner) {
        List<Impl> gone = new ArrayList<>();
        for (Impl p : ALL) if (p.owner.equals(owner)) gone.add(p);
        ALL.removeAll(gone);
        close(gone);
    }

    static void clear() {
        List<Impl> gone = List.copyOf(ALL);
        ALL.clear();
        close(gone);
    }

    /**
     * Pipeline выгруженного модуля — недействителен; его программу и шейдеры убрать из кэша игры (и освободить в GL).
     * Кэш шейдеров игры — по (id, тип, defines): без этого модуль, обновлённый с сервера (тот же id шейдера, новый
     * исходник), до F3+T получил бы старый скомпилированный шейдер, а шейдер, однажды не собравшийся, — так и
     * остался бы несобранным. Шейдеры, которые ещё нужны pipeline других модулей, не трогаем.
     */
    private static void close(List<Impl> gone) {
        if (gone.isEmpty()) return;
        for (Impl p : gone) p.closed = true;
        if (!(RenderSystem.tryGetDevice() instanceof GlDeviceAccessor dev)) return;
        try {
            Set<Identifier> live = new HashSet<>();
            for (Impl p : ALL) {
                live.add(p.pipeline.getVertexShader());
                live.add(p.pipeline.getFragmentShader());
            }
            Set<Identifier> drop = new HashSet<>();
            for (Impl p : gone) {
                GlRenderPipeline c = dev.hokaOdyssey$pipelineCache().remove(p.pipeline);
                if (c != null && c.program() != GlProgram.INVALID_PROGRAM) c.program().close();
                for (Identifier id : List.of(p.pipeline.getVertexShader(), p.pipeline.getFragmentShader())) {
                    if (!live.contains(id)) drop.add(id);
                }
            }
            if (drop.isEmpty()) return;
            dev.hokaOdyssey$shaderCache().entrySet().removeIf(e -> {
                GlShaderModule s = e.getValue();
                boolean invalid = s == GlShaderModule.INVALID_SHADER;
                Identifier id = invalid ? keyId(e.getKey()) : s.getId();
                if (id == null || !drop.contains(id)) return false;
                if (!invalid) s.close();
                return true;
            });
        } catch (Throwable e) {
            HokaOdysseyClient.LOG.warn("Hoka Odyssey: шейдеры выгруженного модуля не убраны из кэша игры (уберёт F3+T)", e);
        }
    }

    private static java.lang.reflect.Method keyIdMethod;

    /** id шейдера из ключа кэша (record GlDevice.ShaderCompilationKey; имена в игре — intermediary, ищем по типу). */
    private static Identifier keyId(Object key) {
        try {
            if (keyIdMethod == null || keyIdMethod.getDeclaringClass() != key.getClass()) {
                keyIdMethod = null;
                for (var m : key.getClass().getDeclaredMethods()) {
                    if (m.getParameterCount() == 0 && m.getReturnType() == Identifier.class) {
                        m.setAccessible(true);
                        keyIdMethod = m;
                        break;
                    }
                }
                if (keyIdMethod == null) return null;
            }
            return (Identifier) keyIdMethod.invoke(key);
        } catch (Throwable e) {
            return null;
        }
    }

    private static final class Impl implements ModulePipeline {
        final String owner;
        final RenderPipeline pipeline;
        final ShaderSource source;
        volatile boolean closed;
        boolean valid;
        boolean warned;

        Impl(String owner, RenderPipeline pipeline, ShaderSource source) {
            this.owner = owner;
            this.pipeline = pipeline;
            this.source = source;
        }

        @Override public RenderPipeline pipeline() { return pipeline; }

        @Override public boolean prepare() {
            if (closed) return valid = false;
            CompiledRenderPipeline c = RenderSystem.getDevice().precompilePipeline(pipeline, source);
            valid = c.isValid();
            if (!valid && !warned) {
                warned = true;
                HokaOdysseyClient.LOG.error("Модуль {}: шейдеры {} не собрались (подробности — в логе выше)", owner, pipeline.getLocation());
            }
            if (valid) warned = false;
            return valid;
        }

        @Override public boolean isValid() { return !closed && valid; }
    }

    /** Исходник модуля → через препроцессор игры (#moj_import из ресурсов, #version). */
    private static ShaderSource withImports(ShaderSource src) {
        return (Identifier id, ShaderType type) -> {
            String s = src.get(id, type);
            if (s == null || !s.contains("#moj_import")) return s;
            Set<Identifier> seen = new HashSet<>();
            GlslPreprocessor pre = new GlslPreprocessor() {
                @Override
                public String applyImport(boolean relative, String path) {
                    if (relative) return "#error Hoka Odyssey: относительный #moj_import \"" + path + "\" в шейдере модуля не поддерживается\n";
                    Identifier file;
                    try {
                        file = Identifier.parse(path).withPrefix("shaders/include/");
                    } catch (Exception e) {
                        return "#error " + e.getMessage() + "\n";
                    }
                    if (!seen.add(file)) return null;
                    var res = Minecraft.getInstance().getResourceManager().getResource(file);
                    if (res.isEmpty()) return "#error нет include " + file + "\n";
                    try (Reader r = res.get().openAsReader()) {
                        StringBuilder b = new StringBuilder();
                        char[] buf = new char[4096];
                        for (int n; (n = r.read(buf)) > 0; ) b.append(buf, 0, n);
                        return b.toString();
                    } catch (Exception e) {
                        return "#error " + e.getMessage() + "\n";
                    }
                }
            };
            return String.join("", pre.process(s));
        };
    }
}
