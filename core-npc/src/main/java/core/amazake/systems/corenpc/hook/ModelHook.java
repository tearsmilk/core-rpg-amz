package core.amazake.systems.corenpc.hook;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/**
 * ModelEngine R4 через рефлексию (мягкая зависимость: нет плагина — NPC работают без моделей). Все вызовы — в потоке
 * региона сущности (ModelEngine не обещает потокобезопасность своих объектов).
 *
 * <pre>
 * ModeledEntity me = ModelEngineAPI.createModeledEntity(entity);
 * ActiveModel am = ModelEngineAPI.createActiveModel("model_id");
 * am.setScale(scale); me.addModel(am, true); me.setBaseEntityVisible(false);
 * am.getAnimationHandler().playAnimation("idle", 0.25, 0.25, 1.0, true);
 * </pre>
 */
public final class ModelHook {

    private static final String API = "com.ticxo.modelengine.api.ModelEngineAPI";

    private final Logger logger;
    private final Map<String, Boolean> warned = new ConcurrentHashMap<>();
    private volatile Plugin owner;
    private volatile Class<?> api;

    public ModelHook(final Logger logger) {
        this.logger = logger;
    }

    private Class<?> api() {
        final Plugin p = Bukkit.getPluginManager().getPlugin("ModelEngine");
        if (p == null || !p.isEnabled()) {
            this.api = null;
            this.owner = null;
            return null;
        }
        Class<?> c = this.api;
        if (c != null && this.owner == p) {
            return c;
        }
        try {
            c = Class.forName(API, true, p.getClass().getClassLoader());
            this.api = c;
            this.owner = p;
            return c;
        } catch (final ReflectiveOperationException | LinkageError e) {
            this.warnOnce("load", "ModelEngine найден, но его API не загрузилось: " + e);
            return null;
        }
    }

    public boolean available() {
        return this.api() != null;
    }

    public String version() {
        final Plugin p = this.owner;
        return p == null ? "" : p.getPluginMeta().getVersion();
    }

    private void warnOnce(final String key, final String text) {
        if (this.warned.putIfAbsent(key, Boolean.TRUE) == null) {
            this.logger.warning(text);
        }
    }

    /**
     * Публичный метод по имени и типам аргументов: у ModelEngine есть перегрузки с тем же числом параметров
     * ({@code createModeledEntity(Entity)} и {@code (BaseEntity)}, {@code createActiveModel(String)} и
     * {@code (ModelBlueprint)}), а порядок {@code getMethods()} не задан — поэтому выбор только по тому, принимает ли
     * метод именно эти аргументы (примитивы — через обёртки).
     */
    static Method find(final Class<?> type, final String name, final Object... args) {
        for (final Method m : type.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == args.length && accepts(m.getParameterTypes(), args)) {
                return m;
            }
        }
        return null;
    }

    static boolean accepts(final Class<?>[] params, final Object[] args) {
        for (int i = 0; i < params.length; i++) {
            final Object a = args[i];
            if (a == null) {
                if (params[i].isPrimitive()) {
                    return false;
                }
            } else if (!box(params[i]).isInstance(a)) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> box(final Class<?> c) {
        if (!c.isPrimitive()) {
            return c;
        }
        if (c == double.class) {
            return Double.class;
        }
        if (c == boolean.class) {
            return Boolean.class;
        }
        if (c == float.class) {
            return Float.class;
        }
        if (c == int.class) {
            return Integer.class;
        }
        if (c == long.class) {
            return Long.class;
        }
        if (c == short.class) {
            return Short.class;
        }
        if (c == byte.class) {
            return Byte.class;
        }
        return Character.class;
    }

    /** Статический метод API, принимающий эти аргументы, — или NoSuchMethodException с понятным именем. */
    private static Object callStatic(final Class<?> type, final String name, final Object... args) throws ReflectiveOperationException {
        final Method m = find(type, name, args);
        if (m == null) {
            throw new NoSuchMethodException(type.getName() + "#" + name + describe(args));
        }
        return m.invoke(null, args);
    }

    private static String describe(final Object[] args) {
        final StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < args.length; i++) {
            sb.append(i == 0 ? "" : ", ").append(args[i] == null ? "null" : args[i].getClass().getSimpleName());
        }
        return sb.append(')').toString();
    }

    static Object call(final Object target, final String name, final Object... args) throws ReflectiveOperationException {
        Method m = null;
        // ищем метод у публичных интерфейсов объекта (реализация может быть непубличным классом)
        for (final Class<?> i : interfaces(target.getClass())) {
            m = find(i, name, args);
            if (m != null) {
                break;
            }
        }
        if (m == null) {
            m = find(target.getClass(), name, args);
        }
        if (m == null) {
            throw new NoSuchMethodException(target.getClass().getName() + "#" + name + describe(args));
        }
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    private static List<Class<?>> interfaces(final Class<?> c) {
        final List<Class<?>> out = new ArrayList<>();
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            collect(k, out);
        }
        return out;
    }

    private static void collect(final Class<?> k, final List<Class<?>> out) {
        for (final Class<?> i : k.getInterfaces()) {
            if (!out.contains(i)) {
                out.add(i);
                collect(i, out);
            }
        }
    }

    /** Есть ли blueprint модели (неизвестно — true, чтобы не мешать). */
    public boolean hasModel(final String id) {
        final Class<?> c = this.api();
        if (c == null) {
            return false;
        }
        try {
            final Method m = find(c, "getBlueprint", id);
            return m == null || m.invoke(null, id) != null;
        } catch (final ReflectiveOperationException | RuntimeException e) {
            return true;
        }
    }

    /** Список моделей для подсказок (пусто — не вышло узнать). */
    public List<String> models() {
        final Class<?> c = this.api();
        if (c == null) {
            return List.of();
        }
        try {
            final Object apiObj = callStatic(c, "getAPI");
            final Object registry = call(apiObj, "getModelRegistry");
            for (final String getter : new String[] {"getOrderedId", "getKeys", "keys"}) {
                try {
                    final Object r = call(registry, getter);
                    if (r instanceof Collection<?> col) {
                        final List<String> out = new ArrayList<>();
                        for (final Object o : col) {
                            out.add(String.valueOf(o));
                        }
                        return out;
                    }
                } catch (final ReflectiveOperationException ignored) {
                    // следующий вариант
                }
            }
        } catch (final ReflectiveOperationException | RuntimeException e) {
            // нет списка — без подсказок
        }
        return List.of();
    }

    /**
     * Повесить модель на сущность (поток региона сущности). false — ModelEngine нет или вызов не удался (в лог —
     * один раз).
     */
    public boolean attach(final Entity entity, final String modelId, final double scale, final String idle) {
        final Class<?> c = this.api();
        if (c == null) {
            return false;
        }
        try {
            final Object modeled = callStatic(c, "createModeledEntity", entity);
            final Object active = callStatic(c, "createActiveModel", modelId);
            if (active == null) {
                this.warnOnce("model:" + modelId, "ModelEngine: нет модели «" + modelId + "»");
                return false;
            }
            if (scale > 0 && scale != 1.0) {
                try {
                    call(active, "setScale", Double.valueOf(scale));
                } catch (final ReflectiveOperationException e) {
                    this.warnOnce("scale", "ModelEngine: setScale не вызвался (" + e + "), модель в масштабе 1");
                }
            }
            call(modeled, "addModel", active, Boolean.TRUE);
            try {
                call(modeled, "setBaseEntityVisible", Boolean.FALSE);
            } catch (final ReflectiveOperationException e) {
                entity.setInvisible(true);
            }
            if (idle != null && !idle.isBlank()) {
                this.play(active, idle, true);
            }
            return true;
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            this.warnOnce("attach:" + modelId, "ModelEngine: не удалось повесить «" + modelId + "» на " + entity.getType()
                + ": " + (e.getCause() != null ? e.getCause() : e));
            return false;
        }
    }

    private void play(final Object active, final String anim, final boolean force) throws ReflectiveOperationException {
        final Object handler = call(active, "getAnimationHandler");
        // playAnimation(String, double lerpIn, double lerpOut, double speed, boolean force) — по типам, не по числу
        call(handler, "playAnimation", anim, 0.25, 0.25, 1.0, Boolean.valueOf(force));
    }

    /** Проиграть анимацию у всех моделей сущности (поток региона сущности). */
    public void animate(final Entity entity, final String anim) {
        final Class<?> c = this.api();
        if (c == null || anim == null || anim.isBlank()) {
            return;
        }
        try {
            final Object modeled = invokeGet(c, entity);
            if (modeled == null) {
                return;
            }
            final Object models = call(modeled, "getModels");
            if (models instanceof Map<?, ?> m) {
                for (final Object am : m.values()) {
                    this.play(am, anim, true);
                }
            }
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            this.warnOnce("anim", "ModelEngine: анимация «" + anim + "» не проигралась: " + (e.getCause() != null ? e.getCause() : e));
        }
    }

    private static Object invokeGet(final Class<?> api, final Entity entity) throws ReflectiveOperationException {
        for (final Method m : api.getMethods()) {
            if (m.getName().equals("getModeledEntity") && m.getParameterCount() == 1) {
                final Class<?> p = m.getParameterTypes()[0];
                if (p.isInstance(entity)) {
                    return unwrap(m.invoke(null, entity));
                }
                if (p == java.util.UUID.class) {
                    return unwrap(m.invoke(null, entity.getUniqueId()));
                }
            }
        }
        return null;
    }

    private static Object unwrap(final Object o) {
        return o instanceof Optional<?> opt ? opt.orElse(null) : o;
    }

    /** Снять модели с сущности (поток региона сущности). */
    public void detach(final Entity entity) {
        final Class<?> c = this.api();
        if (c == null) {
            return;
        }
        try {
            final Object modeled = invokeGet(c, entity);
            if (modeled != null) {
                call(modeled, "destroy");
            }
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            this.warnOnce("detach", "ModelEngine: не удалось снять модель: " + (e.getCause() != null ? e.getCause() : e));
        }
    }
}
