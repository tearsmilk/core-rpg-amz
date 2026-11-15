package core.amazake.systems.corenpc.dialog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class DialogFlowTest {

    private static Dialog dialog() {
        return DialogParser.parse("d", DialogParserTest.yaml("""
            pages:
              a:
                lines: ["Аб<wait:3>в", "<red>гд</red>"]
                answers:
                  - text: "1"
                    goto: b
                  - text: "2"
                    requires: ["permission: nope"]
                  - text: "3"
              b:
                text: "x"
                next: a
            """)).dialog();
    }

    private static DialogFlow open(final boolean preventSkip) {
        final Dialog d = dialog();
        final DialogFlow f = new DialogFlow(d, 1.0, preventSkip);
        final Page a = d.page("a");
        f.open(a, a.lines(), ans -> ans.requires().isEmpty());
        return f;
    }

    @Test
    void typesWithPausesAcrossLines() {
        final DialogFlow f = open(false);
        assertEquals(5, f.total());
        assertEquals(1, f.tick());
        assertEquals(List.of("А", ""), f.visibleLines());
        f.tick();
        assertEquals(List.of("Аб", ""), f.visibleLines());
        // пауза 3 тика после «б»
        assertEquals(0, f.tick());
        assertEquals(0, f.tick());
        assertEquals(0, f.tick());
        assertEquals(1, f.tick());
        assertEquals(List.of("Абв", ""), f.visibleLines());
        f.tick();
        assertEquals(List.of("Абв", "<red>г"), f.visibleLines());
        assertTrue(f.typing());
        f.tick();
        assertFalse(f.typing());
        assertEquals(List.of("Абв", "<red>гд</red>"), f.visibleLines());
    }

    @Test
    void confirmSkipsThenChooses() {
        final DialogFlow f = open(false);
        assertEquals(2, f.answers().size(), "ответ с невыполненным условием скрыт");
        assertFalse(f.move(1), "пока печатает — выбор не двигается");
        assertEquals(DialogFlow.Kind.SKIPPED, f.confirm().kind());
        assertFalse(f.typing());
        assertTrue(f.move(1));
        assertEquals(1, f.selected());
        assertTrue(f.move(1));
        assertEquals(0, f.selected(), "по кругу");
        f.move(-1);
        assertEquals(1, f.selected());
        final DialogFlow.Step st = f.confirm();
        assertEquals(DialogFlow.Kind.CHOSEN, st.kind());
        assertEquals("3", st.answer().text());
        assertNull(st.target());
    }

    @Test
    void preventSkip() {
        final DialogFlow f = open(true);
        assertEquals(DialogFlow.Kind.NONE, f.confirm().kind());
        assertTrue(f.typing());
        assertEquals(DialogFlow.Kind.CHOSEN, f.choose(0).kind(), "клик по ответу в чате всё равно выбирает");
    }

    @Test
    void pageWithoutAnswersGoesNext() {
        final Dialog d = dialog();
        final DialogFlow f = new DialogFlow(d, 2.0, false);
        f.open(d.page("b"), d.page("b").lines(), a -> true);
        f.tick();
        assertFalse(f.typing());
        final DialogFlow.Step st = f.confirm();
        assertEquals(DialogFlow.Kind.NEXT, st.kind());
        assertEquals("a", st.target());
    }

    @Test
    void fractionalSpeed() {
        final Dialog d = dialog();
        final DialogFlow f = new DialogFlow(d, 0.5, false);
        f.open(d.page("b"), d.page("b").lines(), a -> true);
        assertEquals(0, f.tick());
        assertEquals(1, f.tick());
    }
}
