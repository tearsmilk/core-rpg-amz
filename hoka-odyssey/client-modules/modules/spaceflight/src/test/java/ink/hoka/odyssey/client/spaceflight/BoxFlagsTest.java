package ink.hoka.odyssey.client.spaceflight;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Протокол op 3: бит 5 — рамка повёрнута по грани под ногами (стены и потолок). */
class BoxFlagsTest {
    @Test
    void boxBitOnlyForWallsAndCeiling() {
        for (int s = -1; s < 6; s++) {
            int f = BodyFrame.flags(true, false, s, true);
            assertEquals(s, BodyFrame.surfaceOf(f), "грань не портится");
            int expect = s >= 0 && s != SurfaceBox.FLOOR ? s : -1;
            assertEquals(expect, BodyFrame.boxOf(f), "грань " + s);
            assertEquals(expect >= 0 ? BodyFrame.FLAG_BOX : 0, f & BodyFrame.FLAG_BOX);
            assertEquals(-1, BodyFrame.boxOf(BodyFrame.flags(true, true, s, false)));
            assertEquals(BodyFrame.flags(true, true, s), BodyFrame.flags(true, true, s, false), "старый вызов");
        }
        assertEquals(32, BodyFrame.FLAG_BOX);
        assertEquals(-1, BodyFrame.boxOf(BodyFrame.FLAG_BOX), "бит без грани — ничего");
    }

    /** Сервер (gravity) отбрасывает незнакомые биты: бит 5 — известен ему с этой версии, бит 6–7 — нет. */
    @Test
    void boxBitFitsTheFlagsByte() {
        int all = BodyFrame.flags(true, true, 5, true);
        assertEquals(all, all & 0xFF);
        assertEquals(0, all & 0xC0);
    }
}
