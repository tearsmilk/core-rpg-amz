package core.amazake.systems.coreworld.explosion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExplosionSettingsTest {

    @Test
    void defaultsProtectFromTntCreeperDragonEverywhere() throws Exception {
        final ExplosionSettings s = ExplosionSettings.parse("", w -> { });
        for (final String t : List.of("TNT", "TNT_MINECART", "CREEPER", "ENDER_DRAGON", "creeper")) {
            assertTrue(s.protects("world", t), t);
        }
        assertFalse(s.protects("world", "WITHER"));
        assertFalse(s.protects("world", "FIREBALL"));
        assertFalse(s.protectsBlockExplosion("world"));
        assertTrue(s.protectHanging());
    }

    @Test
    void customSourcesWorldsAndOff() throws Exception {
        final ExplosionSettings s = ExplosionSettings.parse(
            "explosions:\n  sources: [minecraft:wither, Fireball]\n  worlds: [world]\n  block-explosions: true\n", w -> { });
        assertTrue(s.protects("WORLD", "WITHER"));
        assertTrue(s.protects("world", "FIREBALL"));
        assertFalse(s.protects("world", "TNT"));
        assertFalse(s.protects("dungeon", "WITHER"));
        assertTrue(s.protectsBlockExplosion("world"));
        assertFalse(ExplosionSettings.parse("explosions:\n  protect-blocks: false\n", w -> { }).protects("world", "TNT"));
    }

    @Test
    void badValuesWarnAndFallBack() throws Exception {
        final List<String> warns = new ArrayList<>();
        final ExplosionSettings s = ExplosionSettings.parse("explosions:\n  protect-blocks: maybe\n", warns::add);
        assertTrue(s.enabled());
        assertEquals(1, warns.size());
        assertEquals(ExplosionSettings.DEFAULTS.sources(),
            ExplosionSettings.parse("explosions: 5\n", warns::add).sources());
    }
}
