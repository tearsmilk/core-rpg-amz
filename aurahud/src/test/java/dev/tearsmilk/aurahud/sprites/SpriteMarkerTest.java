package dev.tearsmilk.aurahud.sprites;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpriteMarkerTest {

    private static Path vanillaDir() {
        return Path.of(System.getProperty("aurahud.root", ".")).resolve("pack/sprites");
    }

    private static byte[] vanilla(String name) {
        try {
            return Files.readAllBytes(vanillaDir().resolve(SpriteMarker.ROOT + name + ".png"));
        } catch (IOException e) {
            return null;
        }
    }

    private static BufferedImage image(byte[] png) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    private static void write(Path assets, Map<String, byte[]> files) throws IOException {
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            Path p = assets.resolve(e.getKey());
            Files.createDirectories(p.getParent());
            Files.write(p, e.getValue());
        }
    }

    @Test
    void everyListedSpriteShipsWithTheJar() {
        for (String name : SpriteMarker.sprites().keySet()) {
            assertNotNull(vanilla(name), name);
        }
        assertEquals(47 + 3 + 6 + 3 + 5 + 2, SpriteMarker.sprites().size());
    }

    @Test
    void marksCornersInvisiblyAndIsIdempotent(@TempDir Path assets) throws IOException {
        SpriteMarker.Result r = SpriteMarker.prepare(assets, null, SpriteMarkerTest::vanilla, List.of());
        assertEquals(java.util.List.of(), r.unmarkable());
        assertEquals(r.sprites(), r.changed());
        assertEquals(2 * r.sprites(), r.files().size());
        for (Map.Entry<String, SpriteMarker.Family> e : SpriteMarker.sprites().entrySet()) {
            String rel = SpriteMarker.ROOT + e.getKey() + ".png";
            BufferedImage marked = SpriteMarker.argb(image(r.files().get(rel)));
            BufferedImage orig = SpriteMarker.argb(image(vanilla(e.getKey())));
            int w = orig.getWidth();
            int h = orig.getHeight();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    boolean corner = (x == 0 || x == w - 1) && (y == 0 || y == h - 1);
                    int m = marked.getRGB(x, y);
                    if (corner) {
                        assertEquals(e.getValue().marker(), m, rel);
                    } else if ((orig.getRGB(x, y) >>> 24) != 0) {
                        assertEquals(orig.getRGB(x, y), m, rel);
                    } else {
                        assertEquals(0, m >>> 24, rel);
                    }
                }
            }
            if (SpriteMarker.vanillaMeta(e.getKey()) == null) {
                assertEquals(SpriteMarker.MCMETA, new String(r.files().get(rel + ".mcmeta"), StandardCharsets.UTF_8));
            }
        }
        write(assets, r.files());
        SpriteMarker.Result again = SpriteMarker.prepare(assets, null, SpriteMarkerTest::vanilla, List.of());
        assertEquals(0, again.changed());
        assertTrue(again.files().isEmpty());
    }

    @Test
    void customTexturesAreMarkedWithBackupAndAnimationKept(@TempDir Path tmp) throws IOException {
        Path assets = tmp.resolve("assets");
        Path backup = tmp.resolve("backup");
        String rel = SpriteMarker.DIR + "heart/full.png";
        BufferedImage strip = new BufferedImage(18, 36, BufferedImage.TYPE_INT_ARGB);
        for (int y = 2; y < 34; y++) {
            for (int x = 2; x < 16; x++) {
                strip.setRGB(x, y, 0xFFFF0000);
            }
        }
        Files.createDirectories(assets.resolve(rel).getParent());
        Files.write(assets.resolve(rel), SpriteMarker.png(strip));
        Files.writeString(assets.resolve(rel + ".mcmeta"), "{\"animation\":{\"frametime\":4}}");
        String opaque = SpriteMarker.DIR + "armor_full.png";
        BufferedImage full = new BufferedImage(9, 9, BufferedImage.TYPE_INT_ARGB);
        full.setRGB(0, 0, 0xFF00FF00);
        Files.write(assets.resolve(opaque), SpriteMarker.png(full));
        SpriteMarker.Result r = SpriteMarker.prepare(assets, backup, SpriteMarkerTest::vanilla, List.of());
        assertEquals(1, r.unmarkable().size());
        assertTrue(r.unmarkable().get(0).startsWith(opaque));
        BufferedImage marked = image(r.files().get(rel));
        int mark = SpriteMarker.Family.HEART.marker();
        for (int[] c : new int[][]{{0, 0}, {0, 17}, {17, 17}, {17, 0}, {0, 18}, {0, 35}, {17, 35}, {17, 18}}) {
            assertEquals(mark, marked.getRGB(c[0], c[1]));
        }
        assertEquals(0xFFFF0000, marked.getRGB(5, 5));
        String meta = new String(r.files().get(rel + ".mcmeta"), StandardCharsets.UTF_8);
        assertTrue(meta.contains("\"frametime\": 4"), meta);
        assertTrue(meta.contains("\"mipmap_strategy\": \"mean\""), meta);
        assertArrayEquals(Files.readAllBytes(assets.resolve(rel)), Files.readAllBytes(backup.resolve(rel)));
        assertTrue(Files.isRegularFile(backup.resolve(rel + ".mcmeta")));
    }

    @Test
    void externalPackSkinsAreMarkedInsteadOfVanilla(@TempDir Path tmp) throws IOException {
        Path assets = tmp.resolve("pack/assets");
        Path zip = tmp.resolve("pack/external_packs/skin.zip");
        Files.createDirectories(zip.getParent());
        BufferedImage skin = new BufferedImage(9, 9, BufferedImage.TYPE_INT_ARGB);
        skin.setRGB(4, 4, 0xFF123456);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("assets/" + SpriteMarker.DIR + "food_full.png"));
            out.write(SpriteMarker.png(skin));
            out.closeEntry();
        }
        SpriteMarker.Result first = SpriteMarker.prepare(assets, null, SpriteMarkerTest::vanilla, List.of(zip));
        String rel = SpriteMarker.DIR + "food_full.png";
        BufferedImage marked = image(first.files().get(rel));
        assertEquals(0xFF123456, marked.getRGB(4, 4));
        assertEquals(SpriteMarker.Family.FOOD.marker(), marked.getRGB(0, 0));
        Files.createDirectories(assets);
        write(assets, SpriteMarker.prepare(assets, null, SpriteMarkerTest::vanilla, List.of()).files());
        SpriteMarker.Result upgrade = SpriteMarker.prepare(assets, null, SpriteMarkerTest::vanilla, List.of(zip));
        assertEquals(1, upgrade.changed());
        assertEquals(0xFF123456, image(upgrade.files().get(rel)).getRGB(4, 4));
        write(assets, upgrade.files());
        assertEquals(0, SpriteMarker.prepare(assets, null, SpriteMarkerTest::vanilla, List.of(zip)).changed());
    }

    @Test
    void updatedExternalSkinReplacesTheOldMarkedCopy(@TempDir Path tmp) throws IOException {
        Path assets = tmp.resolve("pack/assets");
        Path dir = tmp.resolve("pack/external_packs/skin");
        Path ledger = tmp.resolve("work/" + SpriteMarker.LEDGER);
        String rel = SpriteMarker.DIR + "food_full.png";
        Files.createDirectories(dir.resolve("assets").resolve(rel).getParent());
        Files.createDirectories(assets);
        int[] colors = {0xFF123456, 0xFF654321, 0xFF00AA00};
        for (int color : colors) {
            BufferedImage skin = new BufferedImage(9, 9, BufferedImage.TYPE_INT_ARGB);
            skin.setRGB(4, 4, color);
            Files.write(dir.resolve("assets").resolve(rel), SpriteMarker.png(skin));
            SpriteMarker.Result r = SpriteMarker.prepare(assets, null, List.of(dir), ledger);
            write(assets, r.files());
            assertEquals(color, image(Files.readAllBytes(assets.resolve(rel))).getRGB(4, 4));
        }
        BufferedImage own = new BufferedImage(9, 9, BufferedImage.TYPE_INT_ARGB);
        own.setRGB(4, 4, 0xFFABCDEF);
        Files.write(assets.resolve(rel), SpriteMarker.png(own));
        write(assets, SpriteMarker.prepare(assets, null, List.of(dir), ledger).files());
        assertEquals(0xFFABCDEF, image(Files.readAllBytes(assets.resolve(rel))).getRGB(4, 4));
    }

    @Test
    void containerHighlightKeepsItsNineSliceMeta() {
        SpriteMarker.Result r = SpriteMarker.prepare(null, null, SpriteMarkerTest::vanilla, List.of());
        String meta = new String(r.files().get(SpriteMarker.ROOT + "container/slot_highlight_back.png.mcmeta"), StandardCharsets.UTF_8);
        assertTrue(meta.contains("nine_slice"), meta);
        assertTrue(meta.contains("\"mipmap_strategy\": \"mean\""), meta);
    }

    @Test
    void grayscalePngsKeepTheirExactValues() throws IOException {
        Map<String, int[]> known = Map.of(
                "hud/armor_empty", new int[]{1, 1, 0xFF3D3D3D},
                "hud/heart/container", new int[]{2, 1, 0xFF282828},
                "hud/food_empty", new int[]{2, 1, 0xFF282828});
        SpriteMarker.Result r = SpriteMarker.prepare(null, null, SpriteMarkerTest::vanilla, List.of());
        for (Map.Entry<String, int[]> e : known.entrySet()) {
            int[] k = e.getValue();
            BufferedImage marked = image(r.files().get(SpriteMarker.ROOT + e.getKey() + ".png"));
            assertEquals(k[2], marked.getRGB(k[0], k[1]), e.getKey());
            assertEquals(k[2], SpriteMarker.argb(image(vanilla(e.getKey()))).getRGB(k[0], k[1]), e.getKey());
        }
    }
}
