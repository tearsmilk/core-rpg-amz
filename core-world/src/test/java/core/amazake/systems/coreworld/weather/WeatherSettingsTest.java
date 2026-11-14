package core.amazake.systems.coreworld.weather;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WeatherSettingsTest {

    @Test
    void defaultsNoRainEverywhere() throws Exception {
        final WeatherSettings s = WeatherSettings.parse("", w -> { });
        assertTrue(s.affects("world"));
        assertTrue(s.affects("dungeon"));
    }

    @Test
    void worldsAndOff() throws Exception {
        assertFalse(WeatherSettings.parse("weather:\n  disabled: false\n", w -> { }).affects("world"));
        final WeatherSettings s = WeatherSettings.parse("weather:\n  worlds: [world]\n", w -> { });
        assertTrue(s.affects("WORLD"));
        assertFalse(s.affects("dungeon"));
    }
}
