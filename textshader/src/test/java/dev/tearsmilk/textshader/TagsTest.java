package dev.tearsmilk.textshader;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TagsTest {

    private static final MiniMessage MM = MiniMessage.builder().editTags(b -> b.resolver(FxTag.resolver())).build();

    @Test
    void chatTagsSplitIntoSegments() {
        List<ChatTags.Segment> s = ChatTags.parse("hi <wave>big</wave> <rainbow:red>end", null);
        assertEquals(4, s.size());
        assertEquals("hi ", s.get(0).text());
        assertNull(s.get(0).spec());
        assertEquals(FxSpec.of(Effect.WAVE), s.get(1).spec());
        assertEquals("big", s.get(1).text());
        assertNull(s.get(2).spec());
        assertEquals(new FxSpec(Effect.RAINBOW, Palette.RED), s.get(3).spec());
    }

    @Test
    void chatTagsAcceptPrefixesClosersAndBase() {
        FxSpec base = FxSpec.of(Effect.FIRE);
        List<ChatTags.Segment> s = ChatTags.parse("a<fx:glow:aqua>b</>c<textshader:sparkle>d", base);
        assertEquals(base, s.get(0).spec());
        assertEquals(new FxSpec(Effect.GLOW, Palette.AQUA), s.get(1).spec());
        assertEquals(base, s.get(2).spec());
        assertEquals(FxSpec.of(Effect.SPARKLE), s.get(3).spec());
    }

    @Test
    void unknownTagsStayAsText() {
        assertFalse(ChatTags.hasTags("i <3 you <b>"));
        assertTrue(ChatTags.hasTags("<wave>x"));
        List<ChatTags.Segment> s = ChatTags.parse("x <nope>y</nope>", null);
        assertEquals(1, s.size());
        assertEquals("x <nope>y</nope>", s.get(0).text());
    }

    @Test
    void nestedTagsRestoreOuter() {
        List<ChatTags.Segment> s = ChatTags.parse("<wave>a<fire>b</fire>c</wave>d", null);
        assertEquals(List.of("a", "b", "c", "d"), s.stream().map(ChatTags.Segment::text).toList());
        assertEquals(Effect.WAVE, s.get(2).spec().effect());
        assertNull(s.get(3).spec());
    }

    private static List<TextComponent> leaves(Component c) {
        List<TextComponent> out = new ArrayList<>();
        if (c instanceof TextComponent t && !t.content().isEmpty()) {
            out.add(t);
        }
        for (Component child : c.children()) {
            out.addAll(leaves(child));
        }
        return out;
    }

    @Test
    void miniMessageTagColours() {
        Component c = MM.deserialize("<fx:wave:red>hello");
        assertEquals("hello", PlainTextComponentSerializer.plainText().serialize(c));
        TextComponent leaf = leaves(c).get(0);
        int colour = leaf.color() != null ? leaf.color().value() : c.color().value();
        assertEquals(FxCode.encode(Effect.WAVE, Palette.RED), colour);
        Component d = MM.deserialize("<textshader:rainbow>x</textshader>");
        assertEquals(FxCode.encode(Effect.RAINBOW, Palette.WHITE), leaves(d).get(0).color() != null
                ? leaves(d).get(0).color().value() : d.children().get(0).color().value());
    }

    @Test
    void miniMessageTypewriterNumbersAcrossNodes() {
        Component c = MM.deserialize("<fx:type:aqua>ab<bold>cd</bold></fx>");
        assertEquals("abcd", PlainTextComponentSerializer.plainText().serialize(c));
        List<TextComponent> l = leaves(c);
        assertEquals(4, l.size());
        for (int i = 0; i < 4; i++) {
            FxCode.Decoded d = FxCode.decode(l.get(i).color().value());
            assertEquals(Effect.TYPE, d.effect());
            assertEquals(Palette.AQUA, d.palette());
            assertEquals(i, d.letter());
        }
    }
}
