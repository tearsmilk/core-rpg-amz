package core.amazake.systems.corenpc.dialog;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConditionTest {

    private static Condition.Env env(final Set<String> perms, final Map<String, String> papi) {
        return new Condition.Env() {
            @Override
            public boolean hasPermission(final String node) {
                return perms.contains(node);
            }

            @Override
            public String placeholders(final String text) {
                String s = text;
                for (final var e : papi.entrySet()) {
                    s = s.replace(e.getKey(), e.getValue());
                }
                return s;
            }
        };
    }

    @Test
    void permissions() {
        final Condition.Env e = env(Set.of("a.b"), Map.of());
        assertTrue(Condition.parse("permission: a.b").test(e));
        assertFalse(Condition.parse("!permission: a.b").test(e));
        assertTrue(Condition.parse("!permission: c").test(e));
        assertTrue(Condition.parse("permission: x || permission: a.b").test(e));
    }

    @Test
    void placeholdersCompare() {
        final Condition.Env e = env(Set.of(), Map.of("%lvl%", "12", "%class%", "Воин", "%money%", "1,500"));
        assertTrue(Condition.parse("papi: %lvl% >= 10").test(e));
        assertFalse(Condition.parse("papi: %lvl% < 10").test(e));
        assertTrue(Condition.parse("papi: %lvl% = 12").test(e));
        assertTrue(Condition.parse("papi: %class% == воин").test(e));
        assertTrue(Condition.parse("papi: %class% != маг").test(e));
        assertTrue(Condition.parse("papi: %class% contains ои").test(e));
        assertTrue(Condition.parse("papi: %money% > 999").test(e));
        assertTrue(Condition.all(List.of(Condition.parse("papi: %lvl% > 1"), Condition.parse("!permission: z")), e));
        assertFalse(Condition.all(List.of(Condition.parse("papi: %lvl% > 1"), Condition.parse("permission: z")), e));
    }

    @Test
    void badConditions() {
        assertThrows(IllegalArgumentException.class, () -> Condition.parse("level 5"));
        assertThrows(IllegalArgumentException.class, () -> Condition.parse("papi: %x%"));
        assertThrows(IllegalArgumentException.class, () -> Condition.parse("money: 5"));
        assertThrows(IllegalArgumentException.class, () -> Condition.parse("permission:"));
    }
}
