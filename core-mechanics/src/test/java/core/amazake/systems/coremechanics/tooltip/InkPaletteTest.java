package core.amazake.systems.coremechanics.tooltip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.junit.jupiter.api.Test;

class InkPaletteTest {

    private static float light(final TextColor c) {
        return (Math.max(c.red(), Math.max(c.green(), c.blue())) + Math.min(c.red(), Math.min(c.green(), c.blue()))) / 510f;
    }

    @Test
    void whiteAndGrayBecomeInk() {
        assertEquals(InkPalette.INK, InkPalette.ink(NamedTextColor.WHITE));
        assertEquals(InkPalette.INK, InkPalette.ink(null));
        assertEquals(InkPalette.FADED, InkPalette.ink(NamedTextColor.DARK_GRAY));
    }

    @Test
    void colorsKeepHueButGetDark() {
        final TextColor g = InkPalette.ink(NamedTextColor.GREEN);
        assertTrue(g.green() > g.red() && g.green() > g.blue() && light(g) <= 0.31f, g.asHexString());
        final TextColor o = InkPalette.ink(TextColor.color(0xFFAA00));
        assertTrue(o.red() > o.green() && o.green() > o.blue() && light(o) <= 0.37f, o.asHexString());
    }

    @Test
    void blankLinesBecomeSingleDividersAndFlavorIsAppended() {
        final List<Component> out = ParchmentTooltip.build(List.of(Component.text("a"), Component.empty(), Component.empty(),
            Component.text("b")), "«x»");
        assertEquals(5, out.size());
        assertTrue(ParchmentTooltip.isDivider(out.get(1)));
        assertTrue(ParchmentTooltip.isDivider(out.get(3)));
        assertEquals("«x»", ParchmentTooltip.plain(out.get(4)));
    }

    @Test
    void classLineBecomesBadges() {
        final Component c = ClassBadges.replace("Для: Маг, Волшебник, Чернокнижник");
        assertTrue(c != null);
        assertEquals("Для: \uE500 \uE501 \uE502", ParchmentTooltip.plain(c));
        assertEquals(null, ClassBadges.replace("Печать: Интеллекта"));
        assertEquals("Для: \uE508 Повар", ParchmentTooltip.plain(ClassBadges.replace("Для: Тёмный рыцарь, Повар")));
    }
}
