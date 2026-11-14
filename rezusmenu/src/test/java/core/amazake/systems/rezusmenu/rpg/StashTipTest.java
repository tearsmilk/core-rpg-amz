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
    void rowsAndScroll() {
        assertEquals(3, Stash.rows(3, 0));
        assertEquals(9, Stash.rows(3, 6));
        assertEquals(36, Stash.rows(6, 1000));
        assertEquals(0, Stash.maxOffset(3));
        assertEquals(6, Stash.maxOffset(9));
        assertEquals(0, Stash.clampOffset(-2, 9));
        assertEquals(6, Stash.clampOffset(99, 9));
        assertEquals(0, Stash.clampOffset(5, 3));
        assertTrue(Stash.isStash(Stash.BASE));
        assertFalse(Stash.isStash(Jewelry.ARTIFACT));
        assertFalse(Stash.isStash(40));
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
