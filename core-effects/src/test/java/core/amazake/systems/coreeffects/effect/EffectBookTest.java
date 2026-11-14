package core.amazake.systems.coreeffects.effect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EffectBookTest {

    @Test
    void giveExtendsOnlyUp() {
        final EffectBook b = new EffectBook();
        assertTrue(b.give(EffectType.BLEEDING, 100, 0));
        assertFalse(b.give(EffectType.BLEEDING, 50, 0));
        assertEquals(100, b.get(EffectType.BLEEDING).remaining());
        b.give(EffectType.BLEEDING, 300, 0);
        assertEquals(300, b.get(EffectType.BLEEDING).remaining());
        b.give(EffectType.BLEEDING, -1, 0);
        assertTrue(b.get(EffectType.BLEEDING).infinite());
        b.give(EffectType.BLEEDING, 10, 0);
        assertTrue(b.get(EffectType.BLEEDING).infinite());
    }

    @Test
    void advanceEnds() {
        final EffectBook b = new EffectBook();
        b.give(EffectType.PANIC, 3, 0);
        b.give(EffectType.CURSE, -1, 400);
        assertEquals(List.of(), b.advance(2));
        assertEquals(List.of(EffectType.PANIC), b.advance(1));
        assertFalse(b.has(EffectType.PANIC));
        assertTrue(b.has(EffectType.CURSE));
        assertEquals(3, b.get(EffectType.CURSE).elapsed());
        assertEquals(400, b.get(EffectType.CURSE).a());
    }

    @Test
    void codecRoundTrip() {
        final EffectBook b = new EffectBook();
        b.give(EffectType.BLEEDING, 1200, 0);
        b.edit(EffectType.BLEEDING, a -> {
            a.setA(900);
            a.setB(40);
        });
        b.give(EffectType.WARMTH, -1, 0);
        b.advance(5);
        final String s = b.encode();
        assertEquals("1|bleeding:1195:5:900:40|warmth:-1:5:0:0", s);
        final EffectBook back = EffectBook.decode(s, (p, w) -> { throw new AssertionError(p + " " + w); });
        assertEquals(s, back.encode());
    }

    @Test
    void decodeSkipsGarbage() {
        final List<String> warns = new ArrayList<>();
        final EffectBook b = EffectBook.decode("1|bleeding:100:0:0:0|nope:1:1:1:1|panic:x:0:0:0|curse:0:0:0:0|warmth:5:0:0",
            (p, w) -> warns.add(p));
        assertTrue(b.has(EffectType.BLEEDING));
        assertFalse(b.has(EffectType.PANIC));
        assertFalse(b.has(EffectType.CURSE));
        assertFalse(b.has(EffectType.WARMTH));
        assertEquals(4, warns.size());
        assertTrue(EffectBook.decode("2|bleeding:1:0:0:0", (p, w) -> { }).isEmpty());
        assertTrue(EffectBook.decode(null, (p, w) -> { }).isEmpty());
        assertTrue(EffectBook.decode("", (p, w) -> { }).isEmpty());
    }

    @Test
    void parseNames() {
        assertEquals(EffectType.BLEEDING, EffectType.parse("Кровотечение"));
        assertEquals(EffectType.PANIC, EffectType.parse("panic"));
        assertEquals(EffectType.WARMTH, EffectType.parse("тепло"));
        assertEquals(EffectType.CURSE, EffectType.parse("ПРОКЛЯТИЕ"));
        assertEquals(null, EffectType.parse("x"));
    }
}
