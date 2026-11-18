package core.amazake.systems.coreworld.regen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.event.entity.EntityRegainHealthEvent.RegainReason;
import org.junit.jupiter.api.Test;

class RegenPolicyTest {

    @Test
    void onlySatiatedAndRegenAreNatural() {
        for (final RegainReason reason : RegainReason.values()) {
            final boolean expected = reason == RegainReason.SATIATED || reason == RegainReason.REGEN;
            if (expected) {
                assertTrue(RegenPolicy.natural(reason.name()), reason.name());
            } else {
                // Магия, зелья, эффект «Регенерация» и прочее лечение не блокируются.
                assertFalse(RegenPolicy.natural(reason.name()), reason.name());
            }
        }
    }

    @Test
    void mmocorePassiveHealthOnly() {
        assertTrue(RegenPolicy.passiveHealth("HEALTH", "REGENERATION", 0.5));
        assertFalse(RegenPolicy.passiveHealth("HEALTH", "REGENERATION", 0.0));
        assertFalse(RegenPolicy.passiveHealth("HEALTH", "REGENERATION", -1.0));
        assertFalse(RegenPolicy.passiveHealth("HEALTH", "SKILL_REGENERATION", 2.0));
        assertFalse(RegenPolicy.passiveHealth("MANA", "REGENERATION", 2.0));
    }
}
