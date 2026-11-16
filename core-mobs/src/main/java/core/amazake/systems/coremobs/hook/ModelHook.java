package core.amazake.systems.coremobs.hook;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/**
 * ModelEngine R4 через рефлексию (мягкая зависимость: нет плагина — мобы работают без моделей). Подход тот же, что в
 * core-npc. Все вызовы — в потоке региона сущности (ModelEngine не обещает потокобезопасность своих объектов).
 *
 * <pre>
 * ModeledEntity me = ModelEngineAPI.getModeledEntity(entity);          // уже есть — не вешаем второй раз
 * if (me == null) me = ModelEngineAPI.createModeledEntity(entity);
 * ActiveModel am = ModelEngineAPI.createActiveModel("model_id");
 * am.setScale(scale); me.addModel(am, true); me.setBaseEntityVisible(false);
 * am.getAnimationHandler().playAnimation("attack", 0.1, 0.2, 1.0, true);
 * </pre>
 *
 * <p>idle, walk и death ModelEngine проигрывает сам (обработчик состояний по умолчанию).</p>
 */
public final class ModelHook {

    private static final String API = "com.ticxo.modelengine.api.ModelEngineAPI";

    private final Logger logger;
    private static final long WARN_EVERY_MS = 10 * 60 * 1000L;

    private final Map<String, Boolean> warned = new ConcurrentHashMap<>();
    private final Map<String, Long> lastWarn = new ConcurrentHashMap<>();
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

    /** Есть ли blueprint модели (ModelEngine нет или неизвестно — true, чтобы не мешать). */
    public boolean hasModel(final String id) {
        return this.blueprint(id) != Boolean.FALSE;
    }

    /**
     * Blueprint модели: TRUE — есть, FALSE — нет (ещё не загружен или такой модели нет), null — ModelEngine нет или
     * проверить нельзя. ModelEngine R4 регистрирует модели асинхронно уже после включения плагина, поэтому FALSE
     * сразу после старта — обычное дело.
     */
    Boolean blueprint(final String id) {
        final Class<?> c = this.api();
        if (c == null) {
            return null;
        }
        try {
            final Method m = find(c, "getBlueprint", id);
            return m == null ? null : Boolean.valueOf(m.invoke(null, id) != null);
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** Предупреждение не чаще раза в 10 минут на ключ (ошибки вызова не глушатся навсегда). */
    private void warnEvery(final String key, final String text) {
        final long now = System.currentTimeMillis();
        final Long last = this.lastWarn.get(key);
        if (last == null || now - last >= WARN_EVERY_MS) {
            this.lastWarn.put(key, now);
            this.logger.warning(text);
        }
    }

    /**
     * Повесить модель на сущность, если её там ещё нет (поток региона сущности). Уже есть ModeledEntity с этой
     * моделью (повесили раньше или ModelEngine восстановил при загрузке) — ничего не делает. Другие модели на
     * сущности (в mobs.yml сменили {@code model}) снимаются. Blueprint ещё не загружен — тихо false (повесим, когда
     * ModelEngine догрузит модели). false — ModelEngine нет или вызов не удался (в лог — не чаще раза в 10 минут).
     */
    public boolean ensure(final Entity entity, final String modelId, final double scale, final boolean hideBase) {
        final Class<?> c = this.api();
        if (c == null || entity == null || !entity.isValid()) {
            return false;
        }
        Object created = null;
        try {
            Object modeled = invokeGet(c, entity);
            if (modeled != null && hasActive(modeled, modelId)) {
                return true;
            }
            if (this.blueprint(modelId) == Boolean.FALSE) {
                return false;                       // ModelEngine ещё грузит модели (или её нет — скажет checkModels)
            }
            final Object active = callStatic(c, "createActiveModel", modelId);
            if (active == null) {
                return false;
            }
            if (modeled == null) {
                modeled = callStatic(c, "createModeledEntity", entity);
                created = modeled;
            } else {
                removeOthers(modeled, modelId);
            }
            if (scale > 0 && scale != 1.0) {
                try {
                    call(active, "setScale", Double.valueOf(scale));
                } catch (final ReflectiveOperationException e) {
                    this.warnOnce("scale", "ModelEngine: setScale не вызвался (" + e + "), модель в масштабе 1");
                }
            }
            call(modeled, "addModel", active, Boolean.TRUE);
            created = null;
            // восстанавливает модель после загрузки чанка только core-mobs (MobModels#onAdd), не ModelEngine:
            // иначе при позднем восстановлении ModelEngine модель могла бы повиснуть дважды
            try {
                call(modeled, "setSaved", Boolean.FALSE);
            } catch (final NoSuchMethodException e) {
                this.warnOnce("saved", "ModelEngine: нет ModeledEntity#setSaved — модели восстанавливает и ModelEngine");
            }
            if (hideBase) {
                try {
                    call(modeled, "setBaseEntityVisible", Boolean.FALSE);
                } catch (final ReflectiveOperationException e) {
                    entity.setInvisible(true);
                }
            }
            return true;
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            this.warnEvery("attach:" + modelId, "ModelEngine: не удалось повесить «" + modelId + "» на " + entity.getType()
                + ": " + (e.getCause() != null ? e.getCause() : e));
            if (created != null) {
                this.discard(c, entity, created);
            }
            return false;
        }
    }

    /** Снять с ModeledEntity модели с другим id (mobs.yml поменяли). */
    static void removeOthers(final Object modeled, final String modelId) throws ReflectiveOperationException {
        final Object models = call(modeled, "getModels");
        if (!(models instanceof Map<?, ?> m) || m.isEmpty()) {
            return;
        }
        for (final Object key : List.copyOf(m.keySet())) {
            if (key instanceof String id && !id.equals(modelId)) {
                call(modeled, "removeModel", id);
            }
        }
    }

    /** Убрать пустой ModeledEntity, который только что создали, а модель повесить не вышло. */
    private void discard(final Class<?> api, final Entity entity, final Object modeled) {
        try {
            final Method m = find(api, "removeModeledEntity", entity.getUniqueId());
            if (m != null) {
                m.invoke(null, entity.getUniqueId());
                return;
            }
            final Method byEntity = find(api, "removeModeledEntity", entity);
            if (byEntity != null) {
                byEntity.invoke(null, entity);
                return;
            }
            call(modeled, "destroy");
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            this.warnOnce("discard", "ModelEngine: пустой ModeledEntity не убран: " + (e.getCause() != null ? e.getCause() : e));
        }
    }

    /** Есть ли у ModeledEntity активная модель с этим id ({@code getModel(String)} → Optional или карта моделей). */
    static boolean hasActive(final Object modeled, final String modelId) throws ReflectiveOperationException {
        try {
            final Object o = unwrap(call(modeled, "getModel", modelId));
            if (o != null) {
                return true;
            }
        } catch (final NoSuchMethodException ignored) {
            // ниже — по карте моделей
        }
        final Object models = call(modeled, "getModels");
        return models instanceof Map<?, ?> m && m.containsKey(modelId);
    }

    private void play(final Object active, final String anim) throws ReflectiveOperationException {
        final Object handler = call(active, "getAnimationHandler");
        // playAnimation(String, double lerpIn, double lerpOut, double speed, boolean force) — по типам, не по числу
        call(handler, "playAnimation", anim, 0.1, 0.2, 1.0, Boolean.TRUE);
    }

    /** Проиграть анимацию у всех моделей сущности (поток региона сущности). Нет модели — ничего. */
    public void animate(final Entity entity, final String anim) {
        if (anim == null || anim.isBlank()) {
            return;
        }
        final Class<?> c = this.api();
        if (c == null) {
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
                    this.play(am, anim);
                }
            }
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            this.warnEvery("anim:" + anim, "ModelEngine: анимация «" + anim + "» не проигралась: "
                + (e.getCause() != null ? e.getCause() : e));
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

    static Object unwrap(final Object o) {
        return o instanceof Optional<?> opt ? opt.orElse(null) : o;
    }
}
