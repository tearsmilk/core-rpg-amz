package dev.tearsmilk.tabshader;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackingTest {

    private final Widths widths = Widths.load();

    @Test
    void codeRoundTrip() {
        for (Backing.Kind k : Backing.Kind.values()) {
            for (int w = 0; w <= Backing.MAX_WIDTH; w += 7) {
                int c = Backing.code(k, w);
                assertEquals(Backing.MARK_R, (c >> 16) & 255);
                assertEquals(k, Backing.kindOf(c));
                assertEquals(w, Backing.widthOf(c));
            }
        }
        assertEquals(Backing.MAX_WIDTH, Backing.widthOf(Backing.code(Backing.Kind.TITLE, 99999)));
        assertEquals(0, Backing.widthOf(Backing.code(Backing.Kind.TITLE, -5)));
        assertNull(Backing.kindOf(0xD60404));
        assertNull(Backing.kindOf(0xE70000));
        assertNull(Backing.kindOf(0xE75000));
    }

    @Test
    void knownCodes() {
        assertEquals(0xE71064, Backing.code(Backing.Kind.ACTIONBAR, 100));
        assertEquals(0xE72101, Backing.code(Backing.Kind.TITLE, 257));
        assertEquals(0xE73000, Backing.code(Backing.Kind.SUBTITLE, 0));
    }

    @Test
    void wrapPutsMarkerFirst() {
        Component text = Component.text("Тест актионбара", NamedTextColor.WHITE)
                .decorate(TextDecoration.BOLD);
        Component wrapped = Backing.wrap(text, Backing.Kind.ACTIONBAR, widths);
        assertEquals(2, wrapped.children().size());
        TextComponent marker = (TextComponent) wrapped.children().get(0);
        assertEquals(Backing.GLYPHS, marker.content());
        assertEquals(Backing.FONT, marker.font());
        assertEquals(ShadowColor.none(), marker.style().shadowColor());
        assertEquals(TextDecoration.State.FALSE, marker.decoration(TextDecoration.BOLD));
        int w = widths.width(text);
        assertEquals(Backing.code(Backing.Kind.ACTIONBAR, w), marker.color().value());
        assertSame(text, wrapped.children().get(1));
        assertTrue(w > 0);
    }

    @Test
    void emptyStaysEmpty() {
        assertEquals(Component.empty(), Backing.wrap(Component.empty(), Backing.Kind.TITLE, widths));
        assertNull(Backing.wrap(null, Backing.Kind.TITLE, widths));
    }
}
