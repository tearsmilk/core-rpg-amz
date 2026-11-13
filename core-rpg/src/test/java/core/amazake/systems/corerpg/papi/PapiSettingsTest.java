package core.amazake.systems.corerpg.papi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class PapiSettingsTest {

    private static YamlConfiguration yaml(final String text) throws InvalidConfigurationException {
        final YamlConfiguration y = new YamlConfiguration();
        y.loadFromString(text);
        return y;
    }

    @Test
    void defaultFileParsesToDefaultsWithoutWarnings() throws Exception {
        final List<String> warnings = new ArrayList<>();
        final PapiSettings s = PapiSettings.from(yaml(PapiSettings.DEFAULT_YAML), warnings::add);
        assertEquals(PapiSettings.DEFAULTS, s);
        assertTrue(warnings.isEmpty(), warnings::toString);
    }

    @Test
    void emptyConfigGivesDefaults() throws Exception {
        final List<String> warnings = new ArrayList<>();
        assertEquals(PapiSettings.DEFAULTS, PapiSettings.from(yaml(""), warnings::add));
        assertTrue(warnings.isEmpty());
    }

    @Test
    void customValues() throws Exception {
        final List<String> warnings = new ArrayList<>();
        final PapiSettings s = PapiSettings.from(yaml("""
            none-name: "—"
            none-key: ""
            no-seal: "без печати"
            not-loaded: "..."
            progress-digits: 0
            legacy-names: false
            """), warnings::add);
        assertEquals(new PapiSettings("—", "", "без печати", "...", 0, false), s);
        assertTrue(warnings.isEmpty());
    }

    @Test
    void numbersAsTextAreAccepted() throws Exception {
        final PapiSettings s = PapiSettings.from(yaml("none-key: 0\n"), w -> { });
        assertEquals("0", s.noneKey());
    }

    @Test
    void badValuesWarnAndFallBack() throws Exception {
        final List<String> warnings = new ArrayList<>();
        final PapiSettings s = PapiSettings.from(yaml("""
            none-name: [a, b]
            no-seal:
              x: 1
            progress-digits: 7
            legacy-names: "может быть"
            """), warnings::add);
        assertEquals(PapiSettings.DEFAULTS, s);
        assertEquals(4, warnings.size(), warnings::toString);
    }

    @Test
    void nonIntegerDigitsWarn() throws Exception {
        final List<String> warnings = new ArrayList<>();
        final PapiSettings s = PapiSettings.from(yaml("progress-digits: два\n"), warnings::add);
        assertEquals(1, s.progressDigits());
        assertEquals(1, warnings.size());
        warnings.clear();
        assertEquals(1, PapiSettings.from(yaml("progress-digits: -1\n"), warnings::add).progressDigits());
        assertEquals(1, warnings.size());
    }
}
