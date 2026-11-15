package core.amazake.systems.corenpc.dialog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class DialogParserTest {

    static YamlConfiguration yaml(final String s) {
        final YamlConfiguration y = new YamlConfiguration();
        try {
            y.loadFromString(s);
        } catch (final InvalidConfigurationException e) {
            throw new AssertionError(e);
        }
        return y;
    }

    static DialogParser.Result parse(final String s) {
        return DialogParser.parse("t", yaml(s));
    }

    @Test
    void bundledExampleIsClean() throws Exception {
        final YamlConfiguration y = new YamlConfiguration();
        try (var in = new InputStreamReader(DialogParserTest.class.getClassLoader().getResourceAsStream("dialogs/example.yml"),
            StandardCharsets.UTF_8)) {
            y.load(in);
        }
        final DialogParser.Result r = DialogParser.parse("example", y);
        assertEquals(java.util.List.of(), r.errors());
        assertEquals(java.util.List.of(), r.warnings());
        final Dialog d = r.dialog();
        assertEquals("greet", d.start());
        assertEquals(5, d.pages().size());
        final Page greet = d.page("greet");
        assertEquals("Староста Ведан", greet.speaker());
        assertEquals(4, greet.answers().size());
        assertEquals("who", greet.answers().get(0).target());
        assertNull(greet.answers().get(3).target());
        assertEquals(1, greet.answers().get(2).requires().size());
        assertEquals(Action.Type.CONSOLE, greet.answers().get(2).actions().get(0).type());
        assertEquals(1.5, d.page("work").speed());
        assertNull(d.page("accepted").next());
        assertEquals("greet", d.page("who").next());
    }

    @Test
    void missingGotoTargetIsError() {
        final DialogParser.Result r = parse("""
            pages:
              a:
                lines: ["x"]
                answers:
                  - text: "go"
                    goto: nowhere
                  - text: "act"
                    actions: ["goto: alsonowhere"]
              b:
                text: "y"
                next: missing
            """);
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("«nowhere»")), r.errors().toString());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("«alsonowhere»")), r.errors().toString());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("«missing»")), r.errors().toString());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("«b»") && w.contains("нельзя дойти")), r.warnings().toString());
        assertEquals("a", r.dialog().start());
    }

    @Test
    void badStartAndNoPages() {
        assertTrue(parse("start: q\npages:\n  a:\n    text: x\n").errors().stream().anyMatch(e -> e.contains("start")));
        assertTrue(parse("speaker: x\n").errors().stream().anyMatch(e -> e.contains("нет ни одной страницы")));
    }

    @Test
    void endTargetsAndWarnings() {
        final DialogParser.Result r = parse("""
            pages:
              a:
                lines: ["1", "2", "3", "4"]
                next: b
                answers:
                  - "просто строка"
                  - text: "конец"
                    goto: END
                  - text: "условие"
                    requires: ["уровень 5"]
              b:
                text: "y"
            """);
        assertEquals(3, r.dialog().page("a").lines().size());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("4 строк")));
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("next не используется")));
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("«b»")), "b достижима только через игнорируемый next");
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("уровень 5")));
        assertNull(r.dialog().page("a").answers().get(0).target());
        assertNull(r.dialog().page("a").answers().get(1).target());
    }

    @Test
    void externalReferences() {
        final Dialog a = parse("""
            pages:
              s:
                text: "x"
                answers:
                  - text: "ok"
                    goto: other:intro
                  - text: "bad dialog"
                    goto: ghost:p
                  - text: "bad page"
                    goto: other:nope
            """).dialog();
        assertTrue(parse("pages:\n  s:\n    text: x\n    answers:\n      - text: a\n        goto: other:intro\n").errors().isEmpty());
        final Dialog other = DialogParser.parse("other", yaml("pages:\n  intro:\n    text: hi\n")).dialog();
        final var errors = DialogParser.validateExternal(Map.of("t", a, "other", other));
        assertEquals(2, errors.size(), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("ghost")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("nope")));
    }

    @Test
    void textBlockSplitsLines() {
        final Page p = parse("pages:\n  a:\n    text: \"one\\ntwo\"\n").dialog().page("a");
        assertEquals(java.util.List.of("one", "two"), p.lines());
    }
}
