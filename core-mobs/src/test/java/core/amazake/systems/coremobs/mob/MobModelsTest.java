package core.amazake.systems.coremobs.mob;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.junit.jupiter.api.Test;

/** attack в ближнем бою — только от настоящего удара (не шипы, не снаряд, не огонь). */
class MobModelsTest {

    @Test
    void meleeCauses() {
        assertTrue(MobModels.isMelee(DamageCause.ENTITY_ATTACK));
        assertTrue(MobModels.isMelee(DamageCause.ENTITY_SWEEP_ATTACK));
        assertFalse(MobModels.isMelee(DamageCause.THORNS));
        assertFalse(MobModels.isMelee(DamageCause.PROJECTILE));
        assertFalse(MobModels.isMelee(DamageCause.FIRE_TICK));
    }
}
