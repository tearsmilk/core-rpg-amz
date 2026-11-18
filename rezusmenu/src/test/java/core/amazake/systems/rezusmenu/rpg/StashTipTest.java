package core.amazake.systems.rezusmenu.rpg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class StashTipTest {

    @Test
    void rowsFixedButNeverHideUsedSlots() {
        assertEquals(6, Stash.rows(6, -1));
        assertEquals(6, Stash.rows(6, 53));
        assertEquals(7, Stash.rows(6, 54));          // слот 54 — седьмой ряд (остался от купленных раньше рядов)
        assertEquals(9, Stash.rows(3, 80));
        assertEquals(36, Stash.rows(6, 100000));
        assertEquals(1, Stash.rows(0, -1));
        assertTrue(Stash.isStash(Stash.BASE));
        assertFalse(Stash.isStash(Jewelry.ARTIFACT));
        assertFalse(Stash.isStash(40));
    }

    @Test
    void bagIsPocketThenStash() {
        assertEquals(9, Stash.bagRows(6));
        assertEquals(6, Stash.maxOffset(9));
        assertEquals(0, Stash.clampOffset(-2, 9));
        assertEquals(6, Stash.clampOffset(99, 9));
        assertEquals(9, Stash.bagSlot(0, 0));
        assertEquals(35, Stash.bagSlot(2, 8));
        assertEquals(Stash.BASE, Stash.bagSlot(3, 0));
        assertEquals(Stash.BASE + 9 + 4, Stash.bagSlot(4, 4));
        assertTrue(RpgScreen.bagLabel(0, 9).contains("карман") && !RpgScreen.bagLabel(0, 9).contains("хранилище"));
        assertTrue(RpgScreen.bagLabel(1, 9).contains("карман") && RpgScreen.bagLabel(1, 9).contains("хранилище"));
        assertFalse(RpgScreen.bagLabel(3, 9).contains("карман"));
        assertTrue(RpgScreen.bagLabel(6, 9).contains("7–9"));
    }

    @Test
    void playtime() {
        assertEquals("0 мин", RpgScreen.playtime(0));
        assertEquals("7 мин", RpgScreen.playtime(7 * 60 * 20));
        assertEquals("5 ч 12 мин", RpgScreen.playtime((5 * 60 + 12) * 60 * 20L));
        assertEquals("3 д 4 ч", RpgScreen.playtime(((3 * 24 + 4) * 60 + 5) * 60 * 20L));
    }

    @Test
    void tipGoesRightThenLeftAndStaysOnCanvas() {
        final TipLayout right = TipLayout.of(List.of("Меч", "Урон +5"), 18, 74, 28);
        assertEquals(18 + 28 + 4, right.left());
        assertEquals(74 + 28, right.bottom());
        final TipLayout left = TipLayout.of(List.of("x".repeat(40)), 590, 74, 28);
        assertTrue(left.left() + left.width() <= TipLayout.CANVAS_RIGHT);
        assertTrue(left.left() < 590);
    }

    @Test
    void tallTipIsCutAndPushedDown() {
        final List<String> many = new ArrayList<>(Collections.nCopies(100, "строка"));
        final TipLayout t = TipLayout.of(many, 18, 0, 28);
        assertTrue(t.cut());
        assertTrue(t.lines() < 100);
        assertTrue(t.bottom() - (t.lines() + 1) * TipLayout.LH >= TipLayout.CANVAS_TOP - 0.001);
        assertTrue(t.bottom() <= TipLayout.CANVAS_BOTTOM);
    }
}
