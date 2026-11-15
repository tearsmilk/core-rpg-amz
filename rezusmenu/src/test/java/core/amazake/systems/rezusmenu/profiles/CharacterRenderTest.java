package core.amazake.systems.rezusmenu.profiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CharacterRenderTest {

    /** Скин, где у каждой части свой цвет: голова красная, тело зелёное, ноги синие, руки жёлтые; второй слой пуст. */
    private static int[] skin() {
        final int[] s = new int[64 * 64];
        fill(s, 0, 0, 32, 16, 0xFFC03030);
        fill(s, 16, 16, 24, 16, 0xFF30A030);
        fill(s, 0, 16, 16, 16, 0xFF3030C0);
        fill(s, 16, 48, 16, 16, 0xFF3030C0);
        fill(s, 40, 16, 16, 16, 0xFFC0C030);
        fill(s, 32, 48, 16, 16, 0xFFC0C030);
        return s;
    }

    private static void fill(final int[] s, final int x, final int y, final int w, final int h, final int c) {
        for (int j = y; j < y + h; j++) {
            for (int i = x; i < x + w; i++) {
                s[j * 64 + i] = c;
            }
        }
    }

    @Test
    void frontViewHeadOnTopFeetAtBottom() {
        final CharacterRender.Sprite sp = CharacterRender.render(skin(), false, 0, 0, 2);
        // 16 x 32 пикселя скина (+ второй слой: рукава на 0.25, шляпа на 0.5), по 2 на пиксель, + контур
        assertEquals(33 + 2, sp.w());
        assertEquals(66 + 2, sp.h());
        final int cx = sp.w() / 2;
        assertTrue(((sp.argb()[(1 + 4) * sp.w() + cx] >> 16) & 0xFF) > 0x80, "сверху — голова");
        assertTrue((sp.argb()[(sp.h() - 4) * sp.w() + cx - 3] & 0xFF) > 0x80, "внизу — ноги");
        assertEquals(sp.w() / 2.0, sp.feetX(), 1);
        assertEquals(sp.h() - 2, sp.feetY(), 1);
        assertEquals(0, sp.argb()[0], "угол прозрачный");
    }

    @Test
    void turnedViewIsWiderAndOutlined() {
        final CharacterRender.Sprite front = CharacterRender.render(skin(), false, 0, 0, 2);
        final CharacterRender.Sprite turned = CharacterRender.render(skin(), false, 25, 10, 2);
        assertTrue(turned.w() > front.w() - 4, "в три четверти видна глубина");
        boolean outline = false;
        for (final int c : turned.argb()) {
            outline |= c == 0xFF141218;
        }
        assertTrue(outline);
        // сверху видна макушка: у верхнего непрозрачного ряда есть цвет головы
        int top = 0;
        boolean found = false;
        for (int y = 0; y < turned.h() && !found; y++) {
            for (int x = 0; x < turned.w(); x++) {
                final int c = turned.argb()[y * turned.w() + x];
                if (c != 0 && c != 0xFF141218) {
                    top = c;
                    found = true;
                    break;
                }
            }
        }
        assertTrue(found);
        assertTrue(((top >> 16) & 0xFF) > 0x80, "верх — голова (красная)");
    }

    @Test
    void legacySkinIsMirrored() {
        final int[] old = new int[64 * 32];
        old[20 * 64 + 44] = 0xFF112233; // перед правой руки, левый столбец
        final int[] norm = CharacterRender.normalize(old, 64, 32);
        assertEquals(64 * 64, norm.length);
        // развёртка зеркальная: тот же пиксель оказывается в развёртке левой руки (32, 48)
        boolean found = false;
        for (int y = 48; y < 64; y++) {
            for (int x = 32; x < 48; x++) {
                found |= norm[y * 64 + x] == 0xFF112233;
            }
        }
        assertTrue(found);
        assertNull(CharacterRender.normalize(new int[10], 10, 1));
    }

    @Test
    void fitTruncatesWithEllipsis() {
        assertEquals("Деревня", ProfilesScreen.fit("Деревня", 200));
        final String s = ProfilesScreen.fit("Очень длинное название места", 60);
        assertTrue(s.endsWith("…"));
        assertTrue(FontWidth.of(s) <= 60);
        assertEquals(2 + 3 + 6, FontWidth.of("il" + "a"));
    }
}
