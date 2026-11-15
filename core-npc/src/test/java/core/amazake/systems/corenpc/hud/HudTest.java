package core.amazake.systems.corenpc.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.junit.jupiter.api.Test;

class HudTest {

    static FontWidths widths() throws Exception {
        try (InputStream in = HudTest.class.getClassLoader().getResourceAsStream("font-widths.txt")) {
            return FontWidths.load(in);
        }
    }

    @Test
    void shiftsAddUp() {
        for (final int n : new int[] {-1, -249, 1, 7, 512, 1023, 1500, -3000}) {
            assertEquals(n, Shift.measure(Shift.of(n)), "shift " + n);
        }
        assertEquals("", Shift.of(0));
    }

    @Test
    void widthsKnowCyrillicAndAscii() throws Exception {
        final FontWidths w = widths();
        assertTrue(w.size() > 500);
        assertTrue(w.known('Ж'));
        assertTrue(w.known('a'));
        assertEquals(4, w.width(' ', false));
        assertEquals(6, w.width('a', false));
        assertEquals(2, w.width('i', false));
        assertEquals(7, w.width('a', true));
        final Component c = MiniMessage.miniMessage().deserialize("ab<bold>cd</bold>");
        assertEquals(6 + 6 + 7 + 7, w.width(c));
    }

    /** Перо проходит ровно ширину окна: сумма шагов глифов и сдвигов = BOX_ADVANCE. */
    @Test
    void renderedBoxIsCentered() throws Exception {
        final FontWidths w = widths();
        final HudRenderer r = new HudRenderer(w);
        final Component out = r.render(Component.text("Староста"), List.of(Component.text("Привет!"), Component.text("Как дела?")),
            List.of(Component.text("1. Да"), Component.text("2. Нет")), 1, true);
        assertEquals(HudLayout.BOX_ADVANCE, advance(out, w));
        final Component empty = r.render(null, List.of(), List.of(), -1, false);
        assertEquals(HudLayout.BOX_ADVANCE, advance(empty, w));
    }

    /** Строки примера влезают в окно даже с самым длинным ником (16 символов). */
    @Test
    void exampleDialogFitsTheBox() throws Exception {
        final FontWidths w = widths();
        final org.bukkit.configuration.file.YamlConfiguration y = new org.bukkit.configuration.file.YamlConfiguration();
        try (var in = new java.io.InputStreamReader(HudTest.class.getClassLoader().getResourceAsStream("dialogs/example.yml"),
            java.nio.charset.StandardCharsets.UTF_8)) {
            y.load(in);
        }
        final var d = core.amazake.systems.corenpc.dialog.DialogParser.parse("example", y).dialog();
        for (final var p : d.pages().values()) {
            for (final String line : p.lines()) {
                final String mm = line.replace("{player}", "WWWWWWWWWWWWWWWW").replaceAll("<wait:\\d+>", "");
                final int px = w.width(MiniMessage.miniMessage().deserialize(mm));
                assertTrue(px <= HudLayout.TEXT_WIDTH, p.id() + ": " + px + " > " + HudLayout.TEXT_WIDTH + ": " + line);
            }
            for (final var a : p.answers()) {
                assertTrue(w.width(MiniMessage.miniMessage().deserialize("» 9. " + a.text())) <= HudLayout.TEXT_WIDTH, a.text());
            }
        }
    }

    private static int advance(final Component c, final FontWidths w) {
        int sum = 0;
        if (c instanceof TextComponent t) {
            for (int i = 0; i < t.content().length(); i++) {
                final char ch = t.content().charAt(i);
                if (ch == '\ue000') {
                    sum += HudLayout.BOX_ADVANCE;
                } else if (ch == '\ue010') {
                    sum += HudLayout.HIGHLIGHT_ADVANCE;
                } else if (ch == '\ue011') {
                    sum += 8;
                } else if (ch >= 0xF801 && ch <= 0xF82A) {
                    sum += Shift.measure(String.valueOf(ch));
                } else {
                    sum += w.width(ch, false);
                }
            }
        }
        for (final Component child : c.children()) {
            sum += advance(child, w);
        }
        return sum;
    }
}
