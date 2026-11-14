package core.amazake.systems.coreeffects.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class OverlayTest {

    @Test
    void everyCompositionHasTheSameWidth() {
        assertEquals("", Overlay.compose(List.of()));
        for (final Overlay.Layer l : Overlay.Layer.values()) {
            for (int n = 1; n <= 8; n++) {
                final String s = Overlay.compose(List.of(new Overlay.Part(l, n)));
                assertEquals(Overlay.FULL_ADVANCE, Overlay.width(s), l + "×" + n);
            }
        }
        final String mix = Overlay.compose(List.of(new Overlay.Part(Overlay.Layer.DARK_FILL, 2),
            new Overlay.Part(Overlay.Layer.PANIC, 2), new Overlay.Part(Overlay.Layer.BLOOD, 3),
            new Overlay.Part(Overlay.Layer.HEAT, 1), new Overlay.Part(Overlay.Layer.CURSE, 1)));
        assertEquals(Overlay.FULL_ADVANCE, Overlay.width(mix));
    }

    @Test
    void tileArithmeticMatchesFont() {
        // клетка 256×128 при height 72: ширина round(256·72/128) = 144, шаг 145 (как считает клиент: +1)
        assertEquals(145, (int) (0.5 + 256 * (72.0 / 128)) + 1);
        // сплошной 16×8 при height 144: 16·18 = 288, шаг 289
        assertEquals(289, (int) (0.5 + 16 * (144.0 / 8)) + 1);
        assertEquals(Overlay.FULL_ADVANCE, 2 * Overlay.TILE_ADVANCE - 1);
    }
}
