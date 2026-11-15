package core.amazake.systems.corenpc.dialog;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class TypewriterTest {

    private static String plain(final String mini) {
        return PlainTextComponentSerializer.plainText().serialize(MiniMessage.miniMessage().deserialize(mini));
    }

    @Test
    void countsOnlyLetters() {
        final Typewriter t = Typewriter.of("<gold>Привет</gold>, <white>{player}</white>!");
        assertEquals("Привет, {player}!".length(), t.length());
    }

    @Test
    void sliceKeepsTagsAndNeverCutsThem() {
        final Typewriter t = Typewriter.of("<gold>Привет</gold> <#ff0000>мир</#ff0000>");
        assertEquals("", t.slice(0));
        assertEquals("<gold>П", t.slice(1));
        assertEquals("<gold>Привет", t.slice(6));
        assertEquals("<gold>Привет</gold> ", t.slice(7));
        assertEquals("<gold>Привет</gold> <#ff0000>м", t.slice(8));
        assertEquals("<gold>Привет</gold> <#ff0000>мир</#ff0000>", t.slice(100));
        for (int i = 0; i <= t.length(); i++) {
            assertEquals("Привет мир".substring(0, i), plain(t.slice(i)));
        }
    }

    @Test
    void tagsWithArgumentsAndQuotes() {
        final Typewriter t = Typewriter.of("<hover:show_text:'<red>a>b'>Да</hover> <gradient:#fff:#000>ok</gradient>");
        assertEquals(5, t.length());
        assertEquals("<hover:show_text:'<red>a>b'>Д", t.slice(1));
        assertEquals("Да ok", plain(t.full()));
    }

    @Test
    void escapedBracketIsOneLetter() {
        final Typewriter t = Typewriter.of("a \\<b> c");
        assertEquals(6, t.length());
        assertEquals("a \\<", t.slice(3));
        assertEquals("a <b> c", plain(t.full()));
    }

    @Test
    void lonelyBracketIsLetter() {
        final Typewriter t = Typewriter.of("1 < 2");
        assertEquals(5, t.length());
        assertEquals("1 <", t.slice(3));
    }

    @Test
    void waitTags() {
        final Typewriter t = Typewriter.of("<wait:5>Да.<wait:10><wait:3> Нет<WAIT:2>");
        assertEquals(7, t.length());
        assertEquals(5, t.waitAfter(0));
        assertEquals(0, t.waitAfter(1));
        assertEquals(13, t.waitAfter(3));
        assertEquals(2, t.waitAfter(7));
        assertEquals("Да. Нет", t.full());
        assertEquals("Да", t.slice(2));
    }

    @Test
    void surrogatePairsAreOneLetter() {
        final Typewriter t = Typewriter.of("a😀b");
        assertEquals(3, t.length());
        assertEquals("a😀", t.slice(2));
    }
}
