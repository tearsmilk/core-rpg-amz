package dev.tearsmilk.chatshader;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatMarkTest {

    @Test
    void everyColorGetsANearMarkedColor() {
        for (int r = 0; r < 256; r += 3) {
            for (int g = 0; g < 256; g += 5) {
                for (int b = 0; b < 256; b += 7) {
                    int rgb = (r << 16) | (g << 8) | b;
                    int m = ChatMark.mark(rgb);
                    assertTrue(ChatMark.marked(m), Integer.toHexString(rgb));
                    assertTrue(Math.abs(((m >> 16) & 255) - r) <= (r < 8 ? 11 : 8));
                    assertTrue(Math.abs(((m >> 8) & 255) - g) <= (g < 8 ? 12 : 8));
                    assertTrue(Math.abs((m & 255) - b) <= (b < 8 ? 13 : 8));
                    assertFalse(ChatMark.textshader(m), Integer.toHexString(rgb));
                    assertFalse(ChatMark.fxText(m) || ChatMark.fxShadow(m), Integer.toHexString(rgb));
                    int shadow = ChatMark.markShadow(0xFF000000 | ChatMark.shadowOf(rgb)) & 0xFFFFFF;
                    assertTrue(ChatMark.marked(shadow) || ChatMark.fxShadow(shadow));
                }
            }
        }
    }

    @Test
    void onlyOneColorIn4096IsAMark() {
        int count = 0;
        for (int rgb = 0; rgb < (1 << 24); rgb++) {
            if (ChatMark.marked(rgb)) {
                count++;
            }
        }
        assertEquals(4096, count);
        for (int v = 0; v < 256; v++) {
            assertFalse(ChatMark.marked((v << 16) | (v << 8) | v), "grey " + v);
        }
    }

    @Test
    void vanillaInterfaceColorsAreNeverMarked() {
        int[] vanilla = {0xFFFFFF, 0xE0E0E0, 0xA0A0A0, 0x404040, 0x808080, 0xAAAAAA, 0xFFFF00, 0x555555, 0x3F3F3F,
                0x383838, 0x2A2A2A, 0x3F3F00, 0x151515, 0xFEFEFE, 0xF6F6F6, 0x000000, 0xFF5555, 0x55FF55, 0x5555FF,
                0xFFAA00, 0x00AAAA, 0xAA00AA, 0xD60400, 0x350100, 0xE7104D};
        for (int c : vanilla) {
            assertFalse(ChatMark.marked(c), Integer.toHexString(c));
        }
        for (NamedTextColor c : NamedTextColor.NAMES.values()) {
            assertFalse(ChatMark.marked(c.value()), c.toString());
            assertFalse(ChatMark.marked(ChatMark.shadowOf(c.value())), c.toString());
        }
    }

    private static void collect(Component c, List<Component> out) {
        out.add(c);
        for (Component k : c.children()) {
            collect(k, out);
        }
    }

    @Test
    void everyGlyphColorAndShadowIsMarkedAndTextshaderCodesUseTheChatCode() {
        Component msg = Component.text("a").append(Component.text("b", NamedTextColor.RED))
                .append(Component.text("c", TextColor.color(0xD60400)).append(Component.text("d")))
                .append(Component.text("e").shadowColor(ShadowColor.shadowColor(0x805A0A26)));
        Component out = ChatMark.apply(msg);
        List<Component> all = new ArrayList<>();
        collect(out, all);
        assertTrue(ChatMark.marked(out.color().value()));
        assertNotNull(out.shadowColor());
        assertTrue(ChatMark.marked(out.shadowColor().value() & 0xFFFFFF));
        assertEquals(0xFF000000 | ChatMark.mark(0x3F3F3F), out.shadowColor().value());
        Component red = all.get(1);
        assertTrue(ChatMark.marked(red.color().value()));
        assertTrue(ChatMark.marked(red.shadowColor().value() & 0xFFFFFF));
        Component fx = all.get(2);
        assertEquals(0xD50400, fx.color().value());
        assertTrue(ChatMark.fxText(fx.color().value()));
        int shadow = fx.shadowColor().value();
        assertEquals(0xFF000000 | (ChatMark.FX_SHADOW_R << 16) | (ChatMark.shadowOf(0xD60400) & 0xFFFF), shadow);
        assertTrue(ChatMark.fxShadow(shadow & 0xFFFFFF));
        Component inner = all.get(3);
        assertEquals(null, inner.color());
        Component custom = all.get(4);
        assertEquals(0x80000000, custom.shadowColor().value() & 0xFF000000);
        assertTrue(ChatMark.marked(custom.shadowColor().value() & 0xFFFFFF));
        assertEquals(msg.children().size(), out.children().size());
    }

    @Test
    void everyTextshaderCodeRoundTripsThroughTheChatCode() {
        for (int g = 0; g < 256; g++) {
            for (int b = 0; b < 256; b++) {
                int ts = (ChatMark.TEXTSHADER_R << 16) | (g << 8) | b;
                int[] p = ChatMark.pair(ts, null);
                if (!ChatMark.textshader(ts)) {
                    assertTrue(ChatMark.marked(p[0]));
                    continue;
                }
                assertTrue(ChatMark.fxText(p[0]));
                assertEquals(ts & 0xFFFF, p[0] & 0xFFFF);
                int vanillaShadow = ChatMark.shadowOf(ts);
                assertTrue(ChatMark.fxShadow(p[1] & 0xFFFFFF), Integer.toHexString(ts));
                assertEquals(vanillaShadow & 0xFFFF, p[1] & 0xFFFF);
            }
        }
    }

    @Test
    void translatableArgumentsAreMarked() {
        Component msg = Component.translatable("chat.type.text", Component.text("Bob", NamedTextColor.AQUA), Component.text("hi"));
        Component out = ChatMark.apply(msg);
        net.kyori.adventure.text.TranslatableComponent t = (net.kyori.adventure.text.TranslatableComponent) out;
        Component bob = t.arguments().get(0).asComponent();
        assertTrue(ChatMark.marked(bob.color().value()));
        assertTrue(ChatMark.marked(out.color().value()));
    }
}
