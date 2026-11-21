package ink.hoka.odyssey.coreshaders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class NameClashTest {
    @Test
    void glowSuffixIsReserved() {
        assertTrue(Panorama.reservedName("aurora_glow"));
        assertTrue(Panorama.reservedName("x_glow"));
        assertFalse(Panorama.reservedName("glow"));
        assertFalse(Panorama.reservedName("glowing"));
        assertFalse(Panorama.reservedName("my_pic"));
    }

    @Test
    void clashWithBuiltinGlowSprite() {
        List<String> c = PackFiles.clashes("aurora_glow", Settings.DEFAULT_IMAGES);
        assertEquals(List.of("coreshaders/textures/item/panorama/aurora_glow.png"), c);
    }

    @Test
    void clashBetweenCustomImages() {
        assertEquals(List.of("coreshaders/textures/item/panorama/x_glow.png"), PackFiles.clashes("x", List.of("x_glow")));
        assertTrue(PackFiles.clashes("x", List.of("x", "y")).isEmpty());
        assertTrue(PackFiles.clashes("castle", Settings.DEFAULT_IMAGES).isEmpty());
    }

    @Test
    void safePlayerNames() {
        assertTrue(Actions.safeName("Steve_01"));
        assertTrue(Actions.safeName(".BedrockGuy"));
        assertFalse(Actions.safeName("@a"));
        assertFalse(Actions.safeName("@e[type=player]"));
        assertFalse(Actions.safeName("a b"));
        assertFalse(Actions.safeName("x;op"));
        assertFalse(Actions.safeName("ThisNameIsWayTooLong"));
        assertFalse(Actions.safeName(""));
    }
}
