package dev.tearsmilk.tabshader;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogicTest {

    @Test
    void parts() {
        for (Part p : Part.values()) {
            assertEquals(p, Part.parse(p.key()));
            assertEquals(p, Part.parse(p.key().toUpperCase()));
        }
        assertEquals(Part.BOARD, Part.parse("sidebar"));
        assertEquals(Part.BOSSBAR, Part.parse("boss"));
        assertNull(Part.parse("chat"));
        assertNull(Part.parse(null));
    }

    @Test
    void togglesFormat() {
        Set<Part> off = EnumSet.of(Part.TITLE, Part.TAB);
        assertEquals("tab,title", Toggles.format(off));
        assertEquals(off, Toggles.parse("title, tab ,junk"));
        assertEquals(EnumSet.noneOf(Part.class), Toggles.parse(""));
        assertEquals(EnumSet.noneOf(Part.class), Toggles.parse(null));
    }

    @Test
    void fillEscapesValues() {
        MiniMessage mm = MiniMessage.miniMessage();
        Map<String, String> v = Map.of("player", "<red>evil", "online", "5");
        assertEquals("Игрок: \\<red>evil, 5 {unknown} {", Texts.fill("Игрок: {player}, {online} {unknown} {", v, mm));
        assertEquals("", Texts.fill(null, v, mm));
        assertEquals("plain", Texts.fill("plain", v, mm));
    }

    @Test
    void fillEscapesExternalPlaceholders() {
        MiniMessage mm = MiniMessage.miniMessage();
        Map<String, String> v = Map.of("player", "Neko");
        java.util.function.Function<String, String> papi = t -> t.equals("%prefix%") ? "<click:run_command:/op>x" : t;
        assertEquals("\\<click:run_command:/op>x Neko 100% ok", Texts.fill("%prefix% {player} 100% ok", v, mm, papi));
        assertEquals("<red>%unknown_tag%", Texts.fill("<red>%unknown_tag%", v, mm, t -> t));
        assertEquals("a % b", Texts.fill("a % b", v, mm, t -> "X"));
    }

    @Test
    void varsHelpers() {
        assertEquals("20.0", Vars.tps(20.7));
        assertEquals("19.4", Vars.tps(19.43));
        assertEquals("0.0", Vars.tps(-1));
        assertEquals("06:00", Vars.clock(0));
        assertEquals("12:00", Vars.clock(6000));
        assertEquals("00:00", Vars.clock(18000));
    }

    @Test
    void progressModes() {
        ZonedDateTime noon = ZonedDateTime.of(2026, 11, 20, 12, 0, 0, 0, ZoneId.of("UTC"));
        assertEquals(0.5f, Hud.progress("clock", noon, 0, 1, 10), 1e-6);
        assertEquals(0.25f, Hud.progress("day", noon, 0, 1, 10), 1e-6);
        assertEquals(0.1f, Hud.progress("online", noon, 0, 1, 10), 1e-6);
        assertEquals(0f, Hud.progress("online", noon, 0, 1, 0), 1e-6);
        assertEquals(1f, Hud.progress("online", noon, 0, 50, 10), 1e-6);
    }

    @Test
    void settingsParsing() {
        assertEquals(BossBar.Color.RED, Settings.color("red"));
        assertEquals(BossBar.Color.PINK, Settings.color("crimson"));
        assertEquals(BossBar.Overlay.NOTCHED_10, Settings.overlay("notched-10"));
        assertEquals(BossBar.Overlay.NOTCHED_6, Settings.overlay("6"));
        assertEquals(BossBar.Overlay.PROGRESS, Settings.overlay("x"));
    }

    @Test
    void commandHelpers() {
        assertArrayEquals(new String[]{"Привет", "мир"}, Commands.splitTitle("Привет | мир"));
        assertArrayEquals(new String[]{"a|b", ""}, Commands.splitTitle("a|b"));
        assertEquals(Commands.TOGGLE, Commands.permission("toggle"));
        assertEquals(Commands.TEST, Commands.permission("test-title"));
        assertEquals(Commands.ADMIN, Commands.permission("reload"));
        assertNull(Commands.permission("help"));
        for (String sub : Commands.SUBS) {
            if (!sub.equals("help")) {
                assertNotNull(Commands.permission(sub), sub);
            }
        }
    }

    @Test
    void boardEntriesUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < Board.MAX_LINES; i++) {
            assertTrue(seen.add(Board.entry(i)));
        }
    }
}
