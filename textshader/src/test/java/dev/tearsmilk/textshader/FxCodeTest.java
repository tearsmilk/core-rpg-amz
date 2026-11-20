package dev.tearsmilk.textshader;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FxCodeTest {

    @Test
    void knownCodesMatchTheShaderProtocol() {
        assertEquals(0xD60000, FxCode.encode(Effect.SHAKE, Palette.WHITE));
        assertEquals(0xD60404, FxCode.encode(Effect.WAVE, Palette.RED));
        assertEquals(0xD6CC3C, FxCode.encode(Effect.OCEAN, Palette.BROWN));
        assertEquals(0xD6EC00, FxCode.encode(Effect.SWEEP, Palette.WHITE));
        assertEquals(0xD6E000, FxCode.encode(Effect.BOUNCE, Palette.WHITE));
        assertEquals("#D60404", FxCode.hex(FxCode.encode(Effect.WAVE, Palette.RED)));
        assertEquals("&#D60404", FxCode.legacyHex(0xD60404));
    }

    @Test
    void roundTripEveryEffectColourAndLetter() {
        for (Effect e : Effect.values()) {
            for (Palette p : Palette.values()) {
                for (int i = 0; i <= Effect.MAX_LETTER; i++) {
                    int c = FxCode.encode(e, p, i);
                    assertEquals(FxCode.MARK, c >> 16);
                    FxCode.Decoded d = FxCode.decode(c);
                    assertNotNull(d);
                    assertEquals(e, d.effect());
                    assertEquals(p, d.palette());
                    assertEquals(i, d.letter());
                    assertFalse(d.shadow());
                    FxCode.Decoded s = FxCode.decode(FxCode.shadowOf(c));
                    assertNotNull(s);
                    assertTrue(s.shadow());
                    assertEquals(e, s.effect());
                    assertEquals(p, s.palette());
                }
            }
        }
    }

    @Test
    void lettersAreClampedAndForeignColoursIgnored() {
        assertEquals(FxCode.encode(Effect.TYPE, Palette.RED, 63), FxCode.encode(Effect.TYPE, Palette.RED, 500));
        assertEquals(FxCode.encode(Effect.TYPE, Palette.RED, 0), FxCode.encode(Effect.TYPE, Palette.RED, -4));
        assertNull(FxCode.decode(0xFFFFFF));
        assertNull(FxCode.decode(0xD6FF00));
        assertNull(FxCode.decode(0x55FFFF));
    }

    @Test
    void idsAreStableAndFitTheColourByte() {
        assertTrue(Effect.values().length <= Effect.MAX_EFFECTS);
        assertEquals(0, Effect.SHAKE.id());
        assertEquals(31, Effect.TYPE.id());
        assertEquals(44, Effect.WAVY_OUTLINE.id());
        assertEquals(45, Effect.TYPE_CURSOR.id());
        assertEquals(46, Effect.SPARKLE.id());
        assertEquals(51, Effect.OCEAN.id());
        assertEquals(56, Effect.BOUNCE.id());
        assertEquals(59, Effect.SWEEP.id());
        for (Effect e : Effect.values()) {
            assertFalse(Effect.reserved(e.id()), e.key());
            assertTrue(e.id() < Effect.ID_LIMIT, e.key());
            assertEquals(e, Effect.byId(e.id()));
        }
        assertTrue(Effect.TYPE_CURSOR.hidden());
        assertNull(Effect.byKey("typecursor"));
        assertEquals(Effect.RAINBOW_WAVE, Effect.byKey("rainbow_wave"));
        assertEquals(Effect.values().length - 1, Effect.visible().size());
    }

    @Test
    void lightGreysAndTheirShadowsAreNotEffects() {
        for (int v = 0xD0; v <= 0xDF; v++) {
            int grey = (0xD6 << 16) | (v << 8) | v;
            assertNull(FxCode.decode(grey), FxCode.hex(grey));
            for (int r = 0xD4; r <= 0xD7; r++) {
                int near = (r << 16) | (v << 8) | v;
                assertNull(FxCode.decode(FxCode.shadowOf(near)), FxCode.hex(near));
            }
        }
        assertNull(FxCode.decode(0xD6D6D6));
        assertNull(FxCode.decode(0x353535));
        assertNull(FxCode.decode(0xD6D0D0));
        assertNull(FxCode.decode(0xD6DCDC));
        for (int id = Effect.RESERVED_FROM; id <= Effect.RESERVED_TO; id++) {
            assertNull(Effect.byId(id));
        }
    }

    @Test
    void specParsing() {
        assertEquals(new FxSpec(Effect.WAVE, Palette.RED), FxSpec.parse("wave:red"));
        assertEquals(FxSpec.of(Effect.FIRE), FxSpec.parse("FIRE"));
        assertNull(FxSpec.parse("wave:nope"));
        assertNull(FxSpec.parse("nope"));
        assertEquals("sparkle:gold", FxSpec.parse("sparkle:gold").serialize());
        assertEquals(new FxSpec(Effect.WAVE, Palette.AQUA), Commands.specArgs(new String[]{"set", "wave", "aqua"}, 1));
        assertEquals(2, Commands.specWidth(new String[]{"preview", "wave", "aqua", "hi"}, 1));
        assertEquals(1, Commands.specWidth(new String[]{"preview", "wave", "hello"}, 1));
    }
}
