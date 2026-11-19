package dev.tearsmilk.coreshaders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PanoramaTest {
    private static int[] gradient(int w, int h) {
        int[] a = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                a[y * w + x] = 0xFF000000 | ((x * 255 / (w - 1)) << 16) | ((y * 255 / (h - 1)) << 8) | 40;
            }
        }
        return a;
    }

    private static int ch(int c, int shift) {
        return (c >> shift) & 255;
    }

    @Test
    void namesAndKinds() {
        assertTrue(Panorama.validName("my_pic2"));
        assertFalse(Panorama.validName("My"));
        assertFalse(Panorama.validName("a-b"));
        assertFalse(Panorama.validName("../x"));
        assertFalse(Panorama.validName(""));
        assertEquals(33, Panorama.allocateKind(Set.of()));
        assertEquals(35, Panorama.allocateKind(Set.of(33, 34, 36)));
        Set<Integer> full = new HashSet<>();
        for (int k = 33; k <= 200; k++) {
            full.add(k);
        }
        assertEquals(-1, Panorama.allocateKind(full));
        full.remove(120);
        assertEquals(120, Panorama.allocateKind(full));
    }

    @Test
    void modeByAspect() {
        assertEquals(Panorama.Mode.PANORAMA, Panorama.mode(2048, 1024));
        assertEquals(Panorama.Mode.PANORAMA, Panorama.mode(4000, 1000));
        assertEquals(Panorama.Mode.MIRROR, Panorama.mode(1920, 1080));
        assertEquals(Panorama.Mode.MIRROR, Panorama.mode(600, 900));
    }

    @Test
    void panoramaIsScaledToSpriteSize() {
        int[] out = Panorama.convert(gradient(2048, 1024), 2048, 1024);
        assertEquals(Panorama.W * Panorama.H, out.length);
        assertTrue(ch(out[0], 16) < 4);
        assertTrue(ch(out[Panorama.W - 1], 16) > 251);
        assertTrue(ch(out[(Panorama.H - 1) * Panorama.W], 8) > 251);
        assertEquals(40, ch(out[300 * Panorama.W + 500], 0), 1);
        for (int c : out) {
            assertEquals(255, c >>> 24);
        }
    }

    @Test
    void widePanoramaIsCroppedToTwoToOne() {
        int[] out = Panorama.convert(gradient(4000, 1000), 4000, 1000);
        int left = ch(out[200 * Panorama.W], 16);
        int right = ch(out[200 * Panorama.W + Panorama.W - 1], 16);
        assertEquals(255 * 1000 / 3999, left, 3);
        assertEquals(255 * 3000 / 3999, right, 3);
    }

    @Test
    void flatPhotoIsMirrorTiledWithoutSeams() {
        int[] out = Panorama.convert(gradient(1920, 1080), 1920, 1080);
        int half = Panorama.W / 2;
        for (int y = 0; y < Panorama.H; y += 37) {
            int row = y * Panorama.W;
            assertEquals(out[row], out[row + Panorama.W - 1]);
            assertEquals(out[row + half - 1], out[row + half]);
            for (int x = 0; x < half; x += 13) {
                assertEquals(out[row + x], out[row + Panorama.W - 1 - x]);
            }
        }
        int l = ch(out[100 * Panorama.W], 16);
        int r = ch(out[100 * Panorama.W + half - 1], 16);
        assertEquals(255 * 420 / 1919, l, 3);
        assertEquals(255 * 1500 / 1919, r, 3);
    }

    @Test
    void transparentPixelsBecomeOpaqueBlack() {
        int[] a = new int[64 * 32];
        int[] out = Panorama.convert(a, 64, 32);
        assertEquals(0xFF000000, out[1234]);
    }

    @Test
    void sentinelsAndGlow() {
        int[] pano = Panorama.convert(gradient(256, 128), 256, 128);
        int[] s = Panorama.withSentinel(pano, 40, 0x123456);
        assertEquals(0x033DCB93, s[0]);
        assertEquals(3, s[1] >>> 24);
        assertEquals(40, ch(s[1], 16));
        assertEquals(147, ch(s[1], 8));
        assertEquals(203, ch(s[1], 0));
        assertEquals(0x03123456, s[2]);
        assertEquals(pano[Panorama.W + 5], s[5]);
        int[] g = Panorama.glowSprite(0xFF8040);
        assertEquals(256, g.length);
        assertEquals(255, ch(g[1], 16));
        assertEquals(0x03FF8040, g[2]);
        assertEquals(0x00FF8040, g[100]);
    }

    @Test
    void glowFollowsImageHue() {
        int[] red = new int[1024];
        java.util.Arrays.fill(red, 0xFFC02020);
        int c = Panorama.glowColor(red);
        assertTrue(ch(c, 16) > 250 && ch(c, 8) < 200 && ch(c, 0) < 200);
        int[] gray = new int[1024];
        java.util.Arrays.fill(gray, 0xFF808080);
        assertEquals(0xCCF2FF, Panorama.glowColor(gray));
    }

    @Test
    void writesPackFiles(@TempDir Path dir) throws Exception {
        int[] pano = Panorama.withSentinel(Panorama.convert(gradient(300, 200), 300, 200), 33, 0x40C0FF);
        ImageManager.write(dir, "test_pic", pano, Panorama.glowSprite(0x40C0FF));
        List<String> paths = PackFiles.paths("test_pic");
        for (String p : paths) {
            assertTrue(Files.isRegularFile(dir.resolve(p)), p);
        }
        BufferedImage img = ImageIO.read(dir.resolve(paths.get(0)).toFile());
        assertEquals(Panorama.W, img.getWidth());
        assertEquals(Panorama.H, img.getHeight());
        assertEquals(0x033DCB93, img.getRGB(0, 0));
        assertEquals(0x032193CB, img.getRGB(1, 0));
        assertEquals(0x0340C0FF, img.getRGB(2, 0));
        BufferedImage glow = ImageIO.read(dir.resolve(paths.get(1)).toFile());
        assertEquals(0x03FF93CB, glow.getRGB(1, 0));
        assertTrue(Files.readString(dir.resolve(paths.get(4))).contains("coreshaders:item/cube_test_pic"));
    }
}
