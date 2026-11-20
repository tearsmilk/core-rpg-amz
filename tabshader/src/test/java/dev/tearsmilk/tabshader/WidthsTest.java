package dev.tearsmilk.tabshader;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WidthsTest {

    private final Widths w = Widths.load();

    @Test
    void vanillaAdvances() {
        assertTrue(w.size() > 500);
        assertEquals(6, w.advance('A', false));
        assertEquals(2, w.advance('i', false));
        assertEquals(3, w.advance('l', false));
        assertEquals(4, w.advance(' ', false));
        assertEquals(2, w.advance('!', false));
        assertEquals(7, w.advance(0x44B, false));
        assertEquals(5, w.advance(' ', true));
        assertEquals(7, w.advance('A', true));
    }

    @Test
    void stringWidth() {
        assertEquals(24, w.width("Hello", false));
        assertEquals(w.width("Hello", false) + 5, w.width("Hello", true));
        assertEquals(0, w.width("", false));
    }

    @Test
    void componentWidthFollowsBoldInheritance() {
        Component c = Component.text("ab").decorate(TextDecoration.BOLD)
                .append(Component.text("cd"))
                .append(Component.text("ef").decoration(TextDecoration.BOLD, false));
        int expected = w.width("abcd", true) + w.width("ef", false);
        assertEquals(expected, w.width(c));
    }
}
