package dev.tearsmilk.worldshader;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZoneCodeTest {

    private static Path root() {
        return Path.of(System.getProperty("worldshader.root", "."));
    }

    @Test
    void everyZoneRoundTripsThroughTheGlowColor() {
        Set<Integer> seen = new HashSet<>();
        for (int slot = 0; slot < ZoneCode.SLOTS; slot++) {
            for (int r = 1; r <= ZoneCode.MAX_RADIUS; r++) {
                for (int s = 1; s <= ZoneCode.MAX_STRENGTH; s++) {
                    int rgb = ZoneCode.rgb(slot, r, s);
                    assertTrue(seen.add(rgb));
                    assertEquals(ZoneCode.RED, rgb >> 16);
                    ZoneCode.Decoded d = ZoneCode.decode(rgb);
                    assertNotNull(d);
                    assertEquals(new ZoneCode.Decoded(slot, r, s), d);
                }
            }
        }
        assertEquals(ZoneCode.SLOTS * ZoneCode.MAX_RADIUS * ZoneCode.MAX_STRENGTH, seen.size());
    }

    @Test
    void designExampleAndInvalidColors() {
        assertEquals(14093834, ZoneCode.rgb(0, 14, 10));
        assertEquals((215 << 16) | (10 << 8) | (3 * 32 + 20), ZoneCode.rgb(3, 10, 20));
        assertNull(ZoneCode.decode(0xFFFFFF));
        assertNull(ZoneCode.decode((215 << 16) | (0 << 8) | 5));
        assertNull(ZoneCode.decode((215 << 16) | (65 << 8) | 5));
        assertNull(ZoneCode.decode((215 << 16) | (10 << 8) | 64));
        assertThrows(IllegalArgumentException.class, () -> ZoneCode.rgb(8, 10, 10));
        assertThrows(IllegalArgumentException.class, () -> ZoneCode.rgb(0, 10, 0));
        assertThrows(IllegalArgumentException.class, () -> ZoneCode.rgb(0, 65, 10));
    }

    @Test
    void noVanillaTeamColorIsAMarker() {
        int[] team = {0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA, 0x555555, 0x5555FF,
                0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};
        for (int c : team) {
            assertNull(ZoneCode.decode(c));
        }
    }

    @Test
    void floatsSurviveTheDataPixels() {
        Random rnd = new Random(7);
        for (int i = 0; i < 100000; i++) {
            float v = (float) ((rnd.nextDouble() * 2 - 1) * Math.pow(10, rnd.nextInt(9) - 4));
            int[] b = ZoneCode.float24(v);
            for (int x : b) {
                assertTrue(x >= 0 && x <= 255);
            }
            float back = ZoneCode.fromFloat24(b);
            assertTrue(Math.abs(back - v) <= Math.abs(v) * 3.1e-5, v + " -> " + back);
        }
        assertEquals(0.0f, ZoneCode.fromFloat24(ZoneCode.float24(0.0f)));
        assertEquals(-1.0f, ZoneCode.fromFloat24(ZoneCode.float24(-1.0f)));
        assertEquals(1200.0f, ZoneCode.fromFloat24(ZoneCode.float24(1200.0f)));
    }

    @Test
    void shaderLayoutMatchesJava() throws IOException {
        String glsl = Files.readString(root().resolve("pack/assets/worldshader/shaders/include/ws_data.glsl"), StandardCharsets.UTF_8);
        Map<String, Integer> want = Map.ofEntries(Map.entry("WS_MARKER_RED", ZoneCode.RED),
                Map.entry("WS_MAX_RADIUS", ZoneCode.MAX_RADIUS), Map.entry("WS_SLOTS", ZoneCode.SLOTS),
                Map.entry("WS_TAG_A", ZoneCode.TAG_A), Map.entry("WS_INFO", ZoneCode.INFO),
                Map.entry("WS_CENTER", ZoneCode.CENTER), Map.entry("WS_PROJ", ZoneCode.PROJ),
                Map.entry("WS_BOX", ZoneCode.BOX), Map.entry("WS_TAG_B", ZoneCode.TAG_B),
                Map.entry("WS_ROW_PIXELS", ZoneCode.ROW_PIXELS), Map.entry("WS_SIGNATURE_ALPHA", ZoneCode.SIGNATURE_ALPHA),
                Map.entry("WS_COPIES", ZoneCode.COPIES));
        for (Map.Entry<String, Integer> e : want.entrySet()) {
            Matcher m = Pattern.compile("#define " + e.getKey() + " (\\d+)\\n").matcher(glsl);
            assertTrue(m.find(), e.getKey());
            assertEquals(e.getValue(), Integer.parseInt(m.group(1)), e.getKey());
        }
        assertTrue(glsl.contains("return ivec3(" + ZoneCode.TAG[0] + ", " + ZoneCode.TAG[1] + ", " + ZoneCode.TAG[2] + " + slot);"));
        assertTrue(ZoneCode.PROJ == ZoneCode.CENTER + 3 && ZoneCode.BOX == ZoneCode.PROJ + 16
                && ZoneCode.TAG_B == ZoneCode.BOX + 4 && ZoneCode.ROW_PIXELS == ZoneCode.TAG_B + 1);
        int[] sig = ZoneCode.SIGNATURE_RGB;
        assertTrue(glsl.contains("#define WS_SIGNATURE_RGB ivec3(" + sig[0] + ", " + sig[1] + ", " + sig[2] + ")\n"));
        assertTrue(ZoneCode.TAG[2] + ZoneCode.SLOTS - 1 <= 255);
    }

    @Test
    void markerTextureCarriesSignature() throws IOException {
        BufferedImage img = ImageIO.read(root().resolve("pack/assets/worldshader/textures/item/marker.png").toFile());
        int[] sig = ZoneCode.SIGNATURE_RGB;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int copy = x * ZoneCode.COPIES / img.getWidth();
                int want = ((ZoneCode.SIGNATURE_ALPHA + copy) << 24) | (sig[0] << 16) | (sig[1] << 8) | sig[2];
                assertEquals(want, img.getRGB(x, y), x + "," + y);
            }
        }
        String model = Files.readString(root().resolve("pack/assets/worldshader/models/item/marker.json"), StandardCharsets.UTF_8).replaceAll("\\s+", "");
        assertTrue(model.contains("\"uv\":[2,4,6,12]") && model.contains("\"uv\":[10,4,14,12]"), model);
    }
}
