package core.amazake.systems.corenpc.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/** Выбор метода ModelEngine по типам аргументов, а не по имени и числу параметров (перегрузки как в R4). */
class ModelHookTest {

    public interface Base {
    }

    public interface Blueprint {
    }

    /** Как ModelEngineAPI: перегрузки с одним параметром. */
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

    @Test
    void picksOverloadByArgumentTypes() throws Exception {
        final Method m = ModelHook.find(Api.class, "createModeledEntity", "zombie");
        assertEquals(CharSequence.class, m.getParameterTypes()[0]);
        assertEquals("entity", m.invoke(null, "zombie"));
        assertEquals("id:elder", ModelHook.find(Api.class, "createActiveModel", "elder").invoke(null, "elder"));
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
            assertEquals("plain wave 1.0", ModelHook.call(h, "playAnimation", "wave", 0.25, 0.25, 1.0, Boolean.TRUE));
        }
    }
}
