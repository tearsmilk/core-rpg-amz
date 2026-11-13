package core.amazake.systems.corerpg.papi;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PapiModuleLegacyTest {

    private static PapiSettings legacy(final boolean on) {
        final PapiSettings d = PapiSettings.DEFAULTS;
        return new PapiSettings(d.noneName(), d.noneKey(), d.noSeal(), d.notLoaded(), d.progressDigits(), on);
    }

    @Test
    void legacyPrefixRegisteredAlongsideCoreByDefault() {
        assertTrue(PapiModule.wantsLegacy(PapiSettings.DEFAULTS, "core"));
    }

    @Test
    void noLegacyPrefixWhenDisabled() {
        assertFalse(PapiModule.wantsLegacy(legacy(false), "core"));
    }

    @Test
    void noSecondExpansionWhenMainPrefixIsAlreadyRoyalquest() {
        assertFalse(PapiModule.wantsLegacy(legacy(true), "royalquest"));
        assertFalse(PapiModule.wantsLegacy(legacy(true), "RoyalQuest"));
    }
}
