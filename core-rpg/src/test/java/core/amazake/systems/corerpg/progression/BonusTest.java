package core.amazake.systems.corerpg.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BonusTest {

    private static Bonus parse(final String raw, final TestYaml.Warnings w) {
        return Bonus.parse(raw, "test", w::add);
    }

    @Test
    void aspectBonuses() {
        final TestYaml.Warnings w = new TestYaml.Warnings();
        assertEquals(new Bonus.AspectBonus("max_health", 4), parse("aspect:max_health:4", w));
        assertEquals(new Bonus.AspectBonus("resistance:chaos", 0.05), parse("aspect:resistance:chaos:0.05", w));
        assertEquals(new Bonus.AspectBonus("damage_vs:monster", 10), parse("aspect:damage_vs:monster:10", w));
        assertEquals(new Bonus.AspectBonus("crit_chance", 5), parse("ASPECT:Crit_Chance:5", w));
        assertTrue(w.list.isEmpty());
    }

    @Test
    void attributeCompatibility() {
        final TestYaml.Warnings w = new TestYaml.Warnings();
        assertEquals(new Bonus.AspectBonus("max_health", 4), parse("attribute:GENERIC_MAX_HEALTH:4.0", w));
        assertEquals(new Bonus.AspectBonus("attack_damage", 2), parse("attribute:ATTACK_DAMAGE:2", w));
        assertEquals(new Bonus.AspectBonus("knockback", 1), parse("attribute:GENERIC_ATTACK_KNOCKBACK:1", w));
        assertEquals(new Bonus.AspectBonus("range", 1), parse("attribute:PLAYER_ENTITY_INTERACTION_RANGE:1", w));
        assertEquals(new Bonus.AspectBonus("movement_speed", 0.02), parse("attribute:generic.movement_speed:0.02", w));
        assertTrue(w.list.isEmpty());
        assertNull(parse("attribute:GENERIC_ARMOR_TOUGHNESS:2", w));
        assertTrue(w.any("не поддерживается"));
    }

    @Test
    void effects() {
        final TestYaml.Warnings w = new TestYaml.Warnings();
        assertEquals(new Bonus.EffectBonus("night_vision", 0, 0), parse("effect:NIGHT_VISION:0", w));
        assertEquals(new Bonus.EffectBonus("strength", 1, 0), parse("effect:INCREASE_DAMAGE:1:0", w));
        assertEquals(new Bonus.EffectBonus("speed", 0, 30), parse("effect:SPEED:0:30", w));
        assertTrue(((Bonus.EffectBonus) parse("effect:speed:0", w)).permanent());
        assertTrue(w.list.isEmpty());
        assertNull(parse("effect:SPEED:-1", w));
        assertNull(parse("effect:SPEED:0:abc", w));
        assertNull(parse("effect:SP EED:0", w));
        assertEquals(3, w.list.size());
    }

    @Test
    void permissions() {
        final TestYaml.Warnings w = new TestYaml.Warnings();
        assertEquals(new Bonus.PermissionBonus("essentials.fly"), parse("permission:essentials.fly", w));
        assertEquals(new Bonus.PermissionBonus("a:b"), parse("permission:a:b", w));
        assertNull(parse("permission:", w));
        assertEquals(1, w.list.size());
    }

    @Test
    void badBonuses() {
        final TestYaml.Warnings w = new TestYaml.Warnings();
        assertNull(parse("aspect:mana:5", w));
        assertNull(parse("aspect:armor:много", w));
        assertNull(parse("aspect:armor", w));
        assertNull(parse("aspect:resistance::0.1", w));
        assertNull(parse("buff:armor:1", w));
        assertNull(parse("", w));
        assertNull(parse(null, w));
        assertEquals(7, w.list.size());
    }
}
