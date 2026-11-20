package dev.tearsmilk.textshader;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MenuTest {

    private static final int[] CONTROLS = {MenuLayout.NICK_INFO, MenuLayout.NICK_COLOR, MenuLayout.NICK_RESET,
            MenuLayout.HEADER, MenuLayout.CHAT_RESET, MenuLayout.CHAT_COLOR, MenuLayout.CHAT_INFO,
            MenuLayout.PREV, MenuLayout.CLOSE, MenuLayout.NEXT};

    @Test
    void everyVisibleEffectHasExactlyOneSlot() {
        List<Effect> all = MenuLayout.effects();
        assertFalse(all.contains(Effect.TYPE_CURSOR));
        assertEquals(Effect.values().length - 1, all.size());
        int pages = MenuLayout.pages(all.size());
        assertEquals((all.size() + MenuLayout.PER_PAGE - 1) / MenuLayout.PER_PAGE, pages);
        Set<Effect> seen = new HashSet<>();
        for (int page = 0; page < pages; page++) {
            for (int slot = 0; slot < MenuLayout.SIZE; slot++) {
                Effect e = MenuLayout.effectAt(page, slot);
                if (e != null) {
                    assertTrue(seen.add(e), e.key());
                    int index = all.indexOf(e);
                    assertEquals(page, MenuLayout.pageOf(index));
                    assertEquals(slot, MenuLayout.slotOf(index));
                }
            }
        }
        assertEquals(new HashSet<>(all), seen);
        assertNull(MenuLayout.effectAt(pages, MenuLayout.FIRST_EFFECT));
        assertNull(MenuLayout.effectAt(-1, MenuLayout.FIRST_EFFECT));
    }

    @Test
    void controlsDoNotOverlapEffects() {
        Set<Integer> used = new HashSet<>();
        for (int c : CONTROLS) {
            assertTrue(c >= 0 && c < MenuLayout.SIZE);
            assertTrue(used.add(c), String.valueOf(c));
            for (int page = 0; page < MenuLayout.pages(MenuLayout.effects().size()); page++) {
                assertNull(MenuLayout.effectAt(page, c));
            }
        }
    }

    @Test
    void pagingMath() {
        assertEquals(1, MenuLayout.pages(0));
        assertEquals(1, MenuLayout.pages(MenuLayout.PER_PAGE));
        assertEquals(2, MenuLayout.pages(MenuLayout.PER_PAGE + 1));
        assertEquals(0, MenuLayout.clampPage(-3, 100));
        assertEquals(MenuLayout.pages(100) - 1, MenuLayout.clampPage(99, 100));
        assertEquals(-1, MenuLayout.indexAt(0, MenuLayout.FIRST_EFFECT + 5, 5));
        assertEquals(4, MenuLayout.indexAt(0, MenuLayout.FIRST_EFFECT + 4, 5));
        assertEquals(MenuLayout.PER_PAGE, MenuLayout.indexAt(1, MenuLayout.FIRST_EFFECT, MenuLayout.PER_PAGE + 1));
        assertEquals(-1, MenuLayout.indexAt(0, MenuLayout.FIRST_EFFECT - 1, 100));
        assertEquals(-1, MenuLayout.indexAt(0, MenuLayout.FIRST_EFFECT + MenuLayout.PER_PAGE, 100));
    }

    @Test
    void colourSlotsRoundTrip() {
        Set<Integer> used = new HashSet<>();
        for (Palette p : Palette.values()) {
            int slot = MenuLayout.colorSlot(p.ordinal());
            assertTrue(slot >= 9 && slot < MenuLayout.SIZE - 9, p.key());
            assertTrue(used.add(slot));
            assertEquals(p.ordinal(), MenuLayout.colorAt(slot));
            assertTrue(slot != MenuLayout.COLOR_SAMPLE && slot != MenuLayout.BACK && slot != MenuLayout.COLOR_INFO);
        }
        assertEquals(-1, MenuLayout.colorAt(MenuLayout.BACK));
        assertEquals(-1, MenuLayout.colorSlot(Palette.values().length));
    }

    @Test
    void clicksMapToTargets() {
        assertSame(Choices.Target.NICK, MenuLayout.action(ClickType.LEFT).target());
        assertFalse(MenuLayout.action(ClickType.LEFT).color());
        assertSame(Choices.Target.CHAT, MenuLayout.action(ClickType.RIGHT).target());
        assertFalse(MenuLayout.action(ClickType.RIGHT).color());
        assertSame(Choices.Target.NICK, MenuLayout.action(ClickType.SHIFT_LEFT).target());
        assertTrue(MenuLayout.action(ClickType.SHIFT_LEFT).color());
        assertSame(Choices.Target.CHAT, MenuLayout.action(ClickType.SHIFT_RIGHT).target());
        assertTrue(MenuLayout.action(ClickType.SHIFT_RIGHT).color());
        for (ClickType t : new ClickType[]{ClickType.NUMBER_KEY, ClickType.DOUBLE_CLICK, ClickType.MIDDLE,
                ClickType.DROP, ClickType.CONTROL_DROP, ClickType.SWAP_OFFHAND, ClickType.CREATIVE}) {
            assertSame(MenuLayout.Action.NONE, MenuLayout.action(t), t.name());
            assertFalse(MenuLayout.button(t), t.name());
        }
        assertSame(MenuLayout.Action.NONE, MenuLayout.action(null));
    }

    private static final class MapStore implements Choices.Store {
        final Map<Choices.Target, String> data = new EnumMap<>(Choices.Target.class);

        @Override
        public String read(Choices.Target t) {
            return data.get(t);
        }

        @Override
        public void write(Choices.Target t, String value) {
            if (value == null) {
                data.remove(t);
            } else {
                data.put(t, value);
            }
        }
    }

    @Test
    void choicesPersistPerTarget() {
        Choices c = new Choices("TextShader");
        assertEquals("textshader:nick", c.key(Choices.Target.NICK).toString());
        assertEquals("textshader:chat", c.key(Choices.Target.CHAT).toString());
        UUID id = UUID.randomUUID();
        MapStore store = new MapStore();
        c.set(id, store, Choices.Target.NICK, new FxSpec(Effect.SPARKLE, Palette.GOLD));
        c.set(id, store, Choices.Target.CHAT, new FxSpec(Effect.WAVE, Palette.RED));
        assertEquals("sparkle:gold", store.data.get(Choices.Target.NICK));
        assertEquals("wave:red", store.data.get(Choices.Target.CHAT));
        c.forget(id);
        assertNull(c.get(id, Choices.Target.NICK));
        Choices restarted = new Choices("textshader");
        restarted.load(id, store);
        assertEquals(new FxSpec(Effect.SPARKLE, Palette.GOLD), restarted.get(id, Choices.Target.NICK));
        assertEquals(new FxSpec(Effect.WAVE, Palette.RED), restarted.get(id, Choices.Target.CHAT));
        restarted.set(id, store, Choices.Target.NICK, null);
        assertNull(restarted.get(id, Choices.Target.NICK));
        assertFalse(store.data.containsKey(Choices.Target.NICK));
        assertEquals(new FxSpec(Effect.WAVE, Palette.RED), restarted.get(id, Choices.Target.CHAT));
    }

    @Test
    void choicesIgnoreBrokenOrHiddenValues() {
        Choices c = new Choices("textshader");
        UUID id = UUID.randomUUID();
        MapStore store = new MapStore();
        store.data.put(Choices.Target.NICK, "typecursor:white");
        store.data.put(Choices.Target.CHAT, "nonsense");
        c.load(id, store);
        assertNull(c.get(id, Choices.Target.NICK));
        assertNull(c.get(id, Choices.Target.CHAT));
        for (Effect e : Effect.visible()) {
            for (Palette p : Palette.values()) {
                FxSpec spec = new FxSpec(e, p);
                assertEquals(spec, FxSpec.parse(spec.serialize()));
            }
        }
    }

    @Test
    void targetAliases() {
        assertSame(Choices.Target.CHAT, Choices.Target.byKey("chat"));
        assertSame(Choices.Target.CHAT, Choices.Target.byKey("MSG"));
        assertSame(Choices.Target.NICK, Choices.Target.byKey("nick"));
        assertNull(Choices.Target.byKey("x"));
        assertNull(Choices.Target.byKey(null));
    }

    private static Set<Integer> colours(Component c, Set<Integer> out) {
        if (c.color() != null) {
            out.add(c.color().value());
        }
        for (Component k : c.children()) {
            colours(k, out);
        }
        return out;
    }

    @Test
    void chatNameGetsNickEffectAndKeepsForeignColours() {
        FxSpec nick = new FxSpec(Effect.WAVE, Palette.RED);
        Component plain = ChatListener.nickName(Component.text("Steve"), nick);
        assertTrue(colours(plain, new HashSet<>()).contains(nick.code()));
        FxSpec bounce = FxSpec.of(Effect.BOUNCE);
        Component idx = ChatListener.nickName(Component.text("abc"), bounce);
        Set<Integer> cs = colours(idx, new HashSet<>());
        for (int i = 0; i < 3; i++) {
            assertTrue(cs.contains(bounce.code(i)), String.valueOf(i));
        }
        Component prefixed = Component.text().append(Component.text("[A] ", TextColor.color(0xFF0000)))
                .append(Component.text("Steve")).build();
        Set<Integer> pc = colours(ChatListener.nickName(prefixed, nick), new HashSet<>());
        assertTrue(pc.contains(0xFF0000));
        assertTrue(pc.contains(nick.code()));
        Component colored = Component.text("Steve", TextColor.color(0x00FF00));
        assertSame(colored, ChatListener.nickName(colored, nick));
        assertTrue(plain instanceof TextComponent);
    }
}
