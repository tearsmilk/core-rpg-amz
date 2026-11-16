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
}
