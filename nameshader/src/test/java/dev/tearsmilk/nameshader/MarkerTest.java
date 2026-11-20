package dev.tearsmilk.nameshader;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkerTest {

    @Test
    void markerIsNotVanillaAndNotBlack() {
        assertEquals(0xB45C0E2A, Marker.MARK_ARGB);
        assertTrue(Marker.MARK_ARGB != Marker.VANILLA_DISPLAY_BACKGROUND);
        assertTrue((Marker.MARK_ARGB & 0xFFFFFF) != 0);
    }

    @Test
    void eligibleOnlyForVanillaDefault() {
        assertTrue(Marker.eligible(true, 0x12345678, false, true));
        assertTrue(Marker.eligible(false, 0x40000000, false, true));
        assertFalse(Marker.eligible(false, 0x41000000, false, true));
        assertFalse(Marker.eligible(false, 0, false, true));
        assertFalse(Marker.eligible(false, 0x40000000, true, true));
        assertFalse(Marker.eligible(true, 0x40000000, false, false));
        assertFalse(Marker.eligible(false, Marker.MARK_ARGB, false, true));
    }

    @Test
    void originalRoundTrip() {
        for (boolean flag : new boolean[]{true, false}) {
            for (int argb : new int[]{0x40000000, 0, -1, 0x80FF00FF, Marker.MARK_ARGB}) {
                Marker.Original o = Marker.decode(Marker.encode(flag, argb));
                assertEquals(flag, o.defaultFlag());
                assertEquals(argb, o.argb());
            }
        }
        assertNull(Marker.decode(null));
        assertNull(Marker.decode(""));
        assertNull(Marker.decode("2:40000000"));
        assertNull(Marker.decode("1:zz"));
        assertNull(Marker.decode("1:100000000"));
        assertNull(Marker.decode("10:40000000"));
    }

    @Test
    void settingsDefaultsAndClamps() {
        YamlConfiguration c = new YamlConfiguration();
        Settings s = Settings.from(c);
        assertFalse(s.styleTextDisplays());
        assertEquals("", s.displayTag());
        assertEquals(30, s.testSeconds());
        assertTrue(s.tagAllowed(Set.of()));
        c.set("nametags.style-text-displays", true);
        c.set("nametags.text-display-tag", " npc ");
        c.set("test.seconds", 100000);
        c.set("test.distance", 0.1);
        s = Settings.from(c);
        assertTrue(s.styleTextDisplays());
        assertEquals("npc", s.displayTag());
        assertEquals(600, s.testSeconds());
        assertEquals(1.5, s.testDistance());
        assertFalse(s.tagAllowed(Set.of("other")));
        assertTrue(s.tagAllowed(Set.of("npc", "other")));
    }
}
