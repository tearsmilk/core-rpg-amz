package dev.tearsmilk.textshader;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NumberingTest {

    private static int letterOf(int code) {
        return FxCode.decode(code).letter();
    }

    @Test
    void typewriterNumbersEveryLetter() {
        List<FxText.Letter> l = FxText.letters("Привет мир", new FxSpec(Effect.TYPE, Palette.RED));
        assertEquals(10, l.size());
        for (int i = 0; i < l.size(); i++) {
            assertEquals(i, letterOf(l.get(i).code()));
            assertEquals(Palette.RED, FxCode.decode(l.get(i).code()).palette());
        }
    }

    @Test
    void numberingClampsAndResetsOnNewLine() {
        String longText = "x".repeat(80);
        List<FxText.Letter> l = FxText.letters(longText, FxSpec.of(Effect.BOUNCE));
        assertEquals(63, letterOf(l.get(63).code()));
        assertEquals(63, letterOf(l.get(79).code()));
        List<FxText.Letter> n = FxText.letters("ab\ncd", FxSpec.of(Effect.SCRAMBLE));
        assertEquals(0, letterOf(n.get(3).code()));
        assertEquals(1, letterOf(n.get(4).code()));
    }

    @Test
    void surrogatePairsCountAsOneLetter() {
        List<FxText.Letter> l = FxText.letters("a😀b", FxSpec.of(Effect.TYPE));
        assertEquals(3, l.size());
        assertEquals(2, letterOf(l.get(2).code()));
    }

    @Test
    void plainEffectsKeepOneRun() {
        List<FxText.Letter> l = FxText.letters("hello", FxSpec.of(Effect.WAVE));
        assertEquals(1, l.size());
        Component c = FxText.render("hello", FxSpec.of(Effect.WAVE));
        assertEquals(FxCode.encode(Effect.WAVE, Palette.WHITE), c.color().value());
        Component t = FxText.render("abc", FxSpec.of(Effect.TYPE));
        assertEquals(3, t.children().size());
        assertEquals(2, letterOf(((TextComponent) t.children().get(2)).color().value()));
    }

    @Test
    void legacyAndMiniMessageForms() {
        assertEquals("&#D60400hi", FxText.legacy("hi", FxSpec.of(Effect.WAVE)));
        String leg = FxText.legacy("ab", FxSpec.of(Effect.TYPE));
        assertEquals("&#D67C00a&#D67C01b", leg);
        assertTrue(FxText.miniMessage("<x>", FxSpec.of(Effect.WAVE)).endsWith("\\<x>"));
    }
}
