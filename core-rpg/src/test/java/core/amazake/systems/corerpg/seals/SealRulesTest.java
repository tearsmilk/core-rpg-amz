package core.amazake.systems.corerpg.seals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import core.amazake.systems.corerpg.seals.SealRules.Action;
import core.amazake.systems.corerpg.seals.SealRules.Decision;
import core.amazake.systems.corerpg.seals.SealRules.Slots;
import core.amazake.systems.corerpg.util.Gear;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SealRulesTest {

    private static Slots slots(final Consumable stone, final Consumable crystal, final Consumable wax, final Consumable heart) {
        return new Slots(stone, crystal, wax, heart);
    }

    @Test
    void stoneByGrade() {
        assertEquals(Consumable.STONE_C, SealRules.stoneFor(Gear.Grade.C));
        assertEquals(Consumable.STONE_B, SealRules.stoneFor(Gear.Grade.B));
        assertEquals(Consumable.STONE_A, SealRules.stoneFor(Gear.Grade.A));
    }

    @Test
    void applyNeedsMatchingStone() {
        assertTrue(SealRules.decide(false, null, Gear.Grade.C, Slots.EMPTY).error().contains("камень C/B/A"));
        final Decision wrong = SealRules.decide(false, null, Gear.Grade.A, slots(Consumable.STONE_C, null, null, null));
        assertFalse(wrong.ok());
        assertTrue(wrong.error().contains("морионовый камень (A)"));
        final Decision ok = SealRules.decide(false, null, Gear.Grade.B, slots(Consumable.STONE_B, null, null, null));
        assertEquals(Action.APPLY, ok.action());
        assertFalse(ok.heart());
        final Decision heart = SealRules.decide(false, null, Gear.Grade.B, slots(Consumable.STONE_B, null, null, Consumable.HEART));
        assertEquals(Action.APPLY, heart.action());
        assertTrue(heart.heart());
    }

    @Test
    void applyRejectsExtraConsumables() {
        assertFalse(SealRules.decide(false, null, Gear.Grade.C,
            slots(Consumable.STONE_C, Consumable.BLOOD_CRYSTAL, null, null)).ok());
        assertFalse(SealRules.decide(false, null, Gear.Grade.C,
            slots(Consumable.STONE_C, null, Consumable.WAX, null)).ok());
        // Арконитовый сургуч при первом наложении не мешает (и не тратится), как в плагине.
        assertEquals(Action.APPLY, SealRules.decide(false, null, Gear.Grade.C,
            slots(Consumable.STONE_C, null, Consumable.ARKONITE_WAX, null)).action());
    }

    @Test
    void waxMakesOrange() {
        assertEquals(Action.WAX, SealRules.decide(true, SealColor.GREEN, Gear.Grade.C,
            slots(null, null, Consumable.WAX, null)).action());
        assertEquals(Action.ACCOUNT_WAX, SealRules.decide(true, SealColor.PURPLE, Gear.Grade.C,
            slots(null, null, Consumable.ACCOUNT_WAX, Consumable.HEART)).action());
        final Decision already = SealRules.decide(true, SealColor.ORANGE, Gear.Grade.C, slots(null, null, Consumable.WAX, null));
        assertFalse(already.ok());
        assertTrue(already.error().contains("уже оранжевая"));
        // Сургуч с камнем — не сургучное действие.
        assertFalse(SealRules.decide(true, SealColor.GREEN, Gear.Grade.C,
            slots(Consumable.STONE_C, null, Consumable.WAX, null)).ok());
    }

    @Test
    void crystalChangesAndArkoniteKeepsSeal() {
        final Decision change = SealRules.decide(true, SealColor.GREEN, Gear.Grade.A,
            slots(null, Consumable.BLOOD_CRYSTAL, Consumable.WAX, Consumable.HEART));
        assertEquals(Action.CHANGE, change.action());
        assertTrue(change.heart());
        assertEquals(Action.ARKONITE, SealRules.decide(true, SealColor.BLUE, Gear.Grade.A,
            slots(null, Consumable.BLOOD_CRYSTAL, Consumable.ARKONITE_WAX, null)).action());
        assertNull(SealRules.decide(true, SealColor.BLUE, Gear.Grade.A,
            slots(null, null, Consumable.ARKONITE_WAX, null)).action());
    }

    @Test
    void heartAloneIsRejected() {
        final Decision d = SealRules.decide(true, SealColor.GREEN, Gear.Grade.C, slots(null, null, null, Consumable.HEART));
        assertFalse(d.ok());
        assertTrue(d.error().contains("Сердце Аркона"));
    }

    @Test
    void rollColorBoundaries() {
        final int[] w = {60, 27, 10, 3};
        assertEquals(100, SealRules.totalWeight(w));
        assertEquals(SealColor.GREEN, SealRules.rollColor(w, 0));
        assertEquals(SealColor.GREEN, SealRules.rollColor(w, 59));
        assertEquals(SealColor.BLUE, SealRules.rollColor(w, 60));
        assertEquals(SealColor.BLUE, SealRules.rollColor(w, 86));
        assertEquals(SealColor.PURPLE, SealRules.rollColor(w, 87));
        assertEquals(SealColor.PURPLE, SealRules.rollColor(w, 96));
        assertEquals(SealColor.ORANGE, SealRules.rollColor(w, 97));
        assertEquals(SealColor.ORANGE, SealRules.rollColor(w, 99));
        assertEquals(SealColor.GREEN, SealRules.rollColor(new int[] {0, 0, 0, 0}, 0));
        assertEquals(SealColor.ORANGE, SealRules.rollColor(new int[] {0, 0, 0, 5}, 2));
    }

    @Test
    void rollDistributionMatchesWeights() {
        final int[] w = {60, 27, 10, 3};
        final Map<SealColor, Integer> counts = new EnumMap<>(SealColor.class);
        for (int r = 0; r < 100; r++) {
            counts.merge(SealRules.rollColor(w, r), 1, Integer::sum);
        }
        assertEquals(60, counts.get(SealColor.GREEN));
        assertEquals(27, counts.get(SealColor.BLUE));
        assertEquals(10, counts.get(SealColor.PURPLE));
        assertEquals(3, counts.get(SealColor.ORANGE));
    }

    @Test
    void heartFloorsToBlue() {
        assertEquals(SealColor.BLUE, SealRules.withHeart(SealColor.GREEN));
        assertEquals(SealColor.BLUE, SealRules.withHeart(SealColor.BLUE));
        assertEquals(SealColor.PURPLE, SealRules.withHeart(SealColor.PURPLE));
        assertEquals(SealColor.ORANGE, SealRules.withHeart(SealColor.ORANGE));
    }

    @Test
    void arkoniteChance() {
        assertTrue(SealRules.arkoniteRerolls(100, 99.999));
        assertFalse(SealRules.arkoniteRerolls(0, 0));
        assertTrue(SealRules.arkoniteRerolls(50, 49.9));
        assertFalse(SealRules.arkoniteRerolls(50, 50));
    }
}
