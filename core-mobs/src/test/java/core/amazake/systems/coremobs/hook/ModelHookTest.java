package core.amazake.systems.coremobs.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Выбор метода ModelEngine по типам аргументов (перегрузки как в R4) и проверка «модель уже висит». */
class ModelHookTest {

    public interface Base {
    }

    public interface Blueprint {
    }

    public static final class Api {
        public static String createModeledEntity(final Base b) {
            return "base";
        }

        public static String createModeledEntity(final CharSequence e) {
            return "entity";
        }

        public static String createActiveModel(final Blueprint b) {
            return "blueprint";
        }

        public static String createActiveModel(final String id) {
            return "id:" + id;
        }
    }

    public interface Handler {
        String playAnimation(String id, double in, double out, double speed, boolean force);

        String playAnimation(String id, Object property, double in, double out, boolean force);
    }

    /** Как ModeledEntity R4: getModel(String) → Optional, getModels() → карта. */
    public interface Modeled {
        Optional<Object> getModel(String id);

        Map<String, Object> getModels();
    }

    /** Без getModel — только карта. */
    public interface OldModeled {
        Map<String, Object> getModels();
    }

    @Test
    void picksOverloadByArgumentTypes() throws Exception {
        final Method m = ModelHook.find(Api.class, "createModeledEntity", "zombie");
        assertEquals(CharSequence.class, m.getParameterTypes()[0]);
        assertEquals("id:skeleton_archer", ModelHook.find(Api.class, "createActiveModel", "skeleton_archer")
            .invoke(null, "skeleton_archer"));
        assertNull(ModelHook.find(Api.class, "createActiveModel", 5));
    }

    @Test
    void playAnimationMatchesPrimitives() throws Exception {
        final Handler h = new Handler() {
            @Override
            public String playAnimation(final String id, final double in, final double out, final double speed, final boolean force) {
                return "plain " + id + " " + speed;
            }

            @Override
            public String playAnimation(final String id, final Object property, final double in, final double out, final boolean force) {
                return "property";
            }
        };
        for (int i = 0; i < 20; i++) {
            assertEquals("plain attack 1.0", ModelHook.call(h, "playAnimation", "attack", 0.1, 0.2, 1.0, Boolean.TRUE));
        }
    }

    @Test
    void detectsExistingModel() throws Exception {
        final Modeled withModel = new Modeled() {
            @Override
            public Optional<Object> getModel(final String id) {
                return id.equals("skeleton_archer") ? Optional.of(new Object()) : Optional.empty();
            }

            @Override
            public Map<String, Object> getModels() {
                return Map.of("skeleton_archer", new Object());
            }
        };
        assertTrue(ModelHook.hasActive(withModel, "skeleton_archer"));
        assertFalse(ModelHook.hasActive(withModel, "other"));

        final OldModeled old = () -> Map.of("skeleton_archer", new Object());
        assertTrue(ModelHook.hasActive(old, "skeleton_archer"));
        assertFalse(ModelHook.hasActive(old, "other"));
    }

    /** ModeledEntity с изменяемой картой моделей и removeModel(String) → Optional. */
    public static final class Mutable {
        public final Map<String, Object> models = new java.util.LinkedHashMap<>();

        public Map<String, Object> getModels() {
            return this.models;
        }

        public Optional<Object> removeModel(final String id) {
            return Optional.ofNullable(this.models.remove(id));
        }
    }

    @Test
    void removesOtherModelsOnly() throws Exception {
        final Mutable m = new Mutable();
        m.models.put("old_skeleton", new Object());
        m.models.put("skeleton_archer", new Object());
        m.models.put("another", new Object());
        ModelHook.removeOthers(m, "skeleton_archer");
        assertEquals(java.util.Set.of("skeleton_archer"), m.models.keySet());
        ModelHook.removeOthers(new Mutable(), "skeleton_archer");     // пустая карта — без ошибок
    }

    /** Как Vector3fc из joml: x() → float. */
    public interface Vec {
        float x();
    }

    /** Как ActiveModel R4: setScale/setHitboxScale(double) и Vector3fc-геттеры, плюс перегрузки (Vector3fc). */
    public static final class Active {
        public final java.util.List<String> calls = new java.util.ArrayList<>();
        public double scale = 1.0;
        public double hitbox = 1.0;

        public Vec getScale() {
            return () -> (float) this.scale;
        }

        public Vec getHitboxScale() {
            return () -> (float) this.hitbox;
        }

        public void setScale(final double v) {
            this.calls.add("scale " + v);
            this.scale = v;
        }

        public void setScale(final Vec v) {
            throw new AssertionError("Vector3fc overload");
        }

        public void setHitboxScale(final double v) {
            this.calls.add("hitbox " + v);
            this.hitbox = v;
        }

        public void setHitboxScale(final Vec v) {
            throw new AssertionError("Vector3fc overload");
        }
    }

    /** Старый ActiveModel без setHitboxScale. */
    public static final class NoHitbox {
        public double scale = 1.0;

        public void setScale(final double v) {
            this.scale = v;
        }
    }

    private static final class Log extends java.util.logging.Handler {
        final java.util.List<String> lines = new java.util.ArrayList<>();

        @Override
        public void publish(final java.util.logging.LogRecord r) {
            this.lines.add(r.getMessage());
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    private static ModelHook hook(final Log log) {
        final java.util.logging.Logger l = java.util.logging.Logger.getAnonymousLogger();
        l.setUseParentHandlers(false);
        l.addHandler(log);
        return new ModelHook(l);
    }

    @Test
    void newModelGetsScaleAndHitboxScale() {
        final Log log = new Log();
        final ModelHook h = hook(log);
        // cave_crawler: model-scale 0.45 — хитбокс тоже 0.45
        final Active a = new Active();
        h.applyScales(a, 0.45, 0.45, false);
        assertEquals(java.util.List.of("scale 0.45", "hitbox 0.45"), a.calls);
        // bone_lord: модель 1.4, хитбокс отдельно 1.2
        final Active b = new Active();
        h.applyScales(b, 1.4, 1.2, false);
        assertEquals(java.util.List.of("scale 1.4", "hitbox 1.2"), b.calls);
        // масштаб 1 — значение ModelEngine по умолчанию, не трогаем
        final Active c = new Active();
        h.applyScales(c, 1.0, 1.0, false);
        assertTrue(c.calls.isEmpty());
        assertTrue(log.lines.isEmpty());
    }

    @Test
    void existingModelSyncedOnlyWhenChanged() {
        final ModelHook h = hook(new Log());
        final Active a = new Active();
        a.scale = 0.45;                         // повешена до правки: масштаб есть, хитбокс 1
        h.applyScales(a, 0.45, 0.45, true);
        assertEquals(java.util.List.of("hitbox 0.45"), a.calls);
        a.calls.clear();
        h.applyScales(a, 0.45, 0.45, true);     // уже совпадает — ничего
        assertTrue(a.calls.isEmpty());
        h.applyScales(a, 1.0, 1.0, true);       // в mobs.yml вернули 1 — вернуть и в ModelEngine
        assertEquals(java.util.List.of("scale 1.0", "hitbox 1.0"), a.calls);
    }

    @Test
    void missingHitboxScaleWarnsOnce() {
        final Log log = new Log();
        final ModelHook h = hook(log);
        final NoHitbox a = new NoHitbox();
        h.applyScales(a, 0.5, 0.5, false);
        h.applyScales(a, 0.5, 0.5, false);
        h.applyScales(a, 0.7, 0.7, true);
        assertEquals(0.7, a.scale);              // масштаб модели всё равно ставится
        assertEquals(1, log.lines.size(), log.lines.toString());
        assertTrue(log.lines.get(0).contains("setHitboxScale"), log.lines.get(0));
    }

    @Test
    void differsReadsVectorX() {
        final Active a = new Active();
        a.hitbox = 0.45;
        assertFalse(ModelHook.differs(a, "getHitboxScale", 0.45));
        assertTrue(ModelHook.differs(a, "getHitboxScale", 1.0));
        assertTrue(ModelHook.differs(a, "getNothing", 1.0));
    }

    @Test
    void activeReturnsModel() throws Exception {
        final Object am = new Object();
        final Modeled m = new Modeled() {
            @Override
            public Optional<Object> getModel(final String id) {
                return id.equals("bone_lord") ? Optional.of(am) : Optional.empty();
            }

            @Override
            public Map<String, Object> getModels() {
                return Map.of("bone_lord", am);
            }
        };
        assertEquals(am, ModelHook.active(m, "bone_lord"));
        assertNull(ModelHook.active(m, "other"));
        final OldModeled old = () -> Map.of("bone_lord", am);
        assertEquals(am, ModelHook.active(old, "bone_lord"));
    }
}
