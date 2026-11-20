package dev.tearsmilk.coreshaders;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GifTest {
    private static final int RED = 0xFFFF0000;
    private static final int GREEN = 0xFF00FF00;
    private static final int BLUE = 0xFF0000FF;
    private static final int WHITE = 0xFFFFFFFF;

    record F(int x, int y, int w, int h, int color, boolean holeAtOrigin, String disposal, int delay) {
    }

    static byte[] gif(int sw, int sh, List<F> frames) throws Exception {
        ImageWriter wr = ImageIO.getImageWritersByFormatName("gif").next();
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bout)) {
            wr.setOutput(out);
            ImageTypeSpecifier spec = ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_BYTE_INDEXED);
            IIOMetadata sm = wr.getDefaultStreamMetadata(null);
            IIOMetadataNode sroot = new IIOMetadataNode("javax_imageio_gif_stream_1.0");
            IIOMetadataNode lsd = new IIOMetadataNode("LogicalScreenDescriptor");
            lsd.setAttribute("logicalScreenWidth", String.valueOf(sw));
            lsd.setAttribute("logicalScreenHeight", String.valueOf(sh));
            lsd.setAttribute("colorResolution", "8");
            lsd.setAttribute("pixelAspectRatio", "0");
            sroot.appendChild(lsd);
            sm.mergeTree("javax_imageio_gif_stream_1.0", sroot);
            wr.prepareWriteSequence(sm);
            for (F f : frames) {
                byte[] r = {0, (byte) (f.color() >> 16), 0, 0};
                byte[] g = {0, (byte) (f.color() >> 8), 0, 0};
                byte[] b = {0, (byte) f.color(), 0, 0};
                IndexColorModel cm = new IndexColorModel(8, 4, r, g, b, 0);
                BufferedImage img = new BufferedImage(f.w(), f.h(), BufferedImage.TYPE_BYTE_INDEXED, cm);
                for (int y = 0; y < f.h(); y++) {
                    for (int x = 0; x < f.w(); x++) {
                        img.getRaster().setSample(x, y, 0, (f.holeAtOrigin() && x == 0 && y == 0) ? 0 : 1);
                    }
                }
                IIOMetadata md = wr.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(img), null);
                IIOMetadataNode root = new IIOMetadataNode("javax_imageio_gif_image_1.0");
                IIOMetadataNode id = new IIOMetadataNode("ImageDescriptor");
                id.setAttribute("imageLeftPosition", String.valueOf(f.x()));
                id.setAttribute("imageTopPosition", String.valueOf(f.y()));
                id.setAttribute("imageWidth", String.valueOf(f.w()));
                id.setAttribute("imageHeight", String.valueOf(f.h()));
                id.setAttribute("interlaceFlag", "FALSE");
                root.appendChild(id);
                IIOMetadataNode lct = new IIOMetadataNode("LocalColorTable");
                lct.setAttribute("sizeOfLocalColorTable", "4");
                lct.setAttribute("sortFlag", "FALSE");
                for (int i = 0; i < 4; i++) {
                    IIOMetadataNode e = new IIOMetadataNode("ColorTableEntry");
                    e.setAttribute("index", String.valueOf(i));
                    e.setAttribute("red", String.valueOf(r[i] & 255));
                    e.setAttribute("green", String.valueOf(g[i] & 255));
                    e.setAttribute("blue", String.valueOf(b[i] & 255));
                    lct.appendChild(e);
                }
                root.appendChild(lct);
                IIOMetadataNode gce = new IIOMetadataNode("GraphicControlExtension");
                gce.setAttribute("disposalMethod", f.disposal());
                gce.setAttribute("userInputFlag", "FALSE");
                gce.setAttribute("transparentColorFlag", "TRUE");
                gce.setAttribute("delayTime", String.valueOf(f.delay()));
                gce.setAttribute("transparentColorIndex", "0");
                root.appendChild(gce);
                md.mergeTree("javax_imageio_gif_image_1.0", root);
                wr.writeToSequence(new IIOImage(img, null, md), null);
            }
            wr.endWriteSequence();
        } finally {
            wr.dispose();
        }
        return bout.toByteArray();
    }

    static List<F> tricky() {
        return List.of(
            new F(0, 0, 8, 6, RED, false, "none", 10),
            new F(1, 1, 2, 2, GREEN, true, "restoreToPrevious", 20),
            new F(4, 2, 2, 2, BLUE, false, "restoreToBackgroundColor", 30),
            new F(0, 0, 1, 1, WHITE, false, "none", 0));
    }

    static ImageReader reader(byte[] data) throws Exception {
        ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data));
        Iterator<ImageReader> it = ImageIO.getImageReaders(in);
        ImageReader r = it.next();
        r.setInput(in, false, false);
        return r;
    }

    @Test
    void headerReadsOffsetsDelaysAndDisposal() throws Exception {
        ImageReader r = reader(gif(8, 6, tricky()));
        Gif.Header h = Gif.header(r, 100);
        assertEquals(8, h.width());
        assertEquals(6, h.height());
        assertEquals(4, h.frames().size());
        assertArrayEquals(new int[] {10, 20, 30, 0}, h.delays());
        assertEquals(new Gif.FrameInfo(1, 1, 2, 2, 20, Gif.Disposal.PREVIOUS), h.frames().get(1));
        assertEquals(Gif.Disposal.BACKGROUND, h.frames().get(2).disposal());
        assertEquals(4, h.frames().get(2).left());
        assertThrows(Gif.FrameLimit.class, () -> Gif.header(reader(gif(8, 6, tricky())), 3));
    }

    @Test
    void framesAreCompositedWithDisposal() throws Exception {
        ImageReader r = reader(gif(8, 6, tricky()));
        Gif.Header h = Gif.header(r, 100);
        List<int[]> got = new ArrayList<>();
        Gif.decode(r, h, new boolean[] {true, true, true, true}, (i, c, w, hh) -> got.add(c.clone()));
        assertEquals(4, got.size());
        int[] f0 = got.get(0);
        for (int c : f0) {
            assertEquals(RED, c);
        }
        int[] f1 = got.get(1);
        assertEquals(RED, f1[8 + 1]);
        assertEquals(GREEN, f1[8 + 2]);
        assertEquals(GREEN, f1[2 * 8 + 1]);
        assertEquals(RED, f1[0]);
        int[] f2 = got.get(2);
        assertEquals(RED, f2[8 + 2]);
        assertEquals(BLUE, f2[2 * 8 + 4]);
        assertEquals(BLUE, f2[3 * 8 + 5]);
        int[] f3 = got.get(3);
        assertEquals(WHITE, f3[0]);
        assertEquals(0, f3[2 * 8 + 4]);
        assertEquals(0, f3[3 * 8 + 5]);
        assertEquals(RED, f3[8 + 2]);
        assertEquals(RED, f3[5 * 8 + 7]);
    }

    @Test
    void decodeStopsAfterLastWantedFrame() throws Exception {
        ImageReader r = reader(gif(8, 6, tricky()));
        Gif.Header h = Gif.header(r, 100);
        List<Integer> seen = new ArrayList<>();
        Gif.decode(r, h, new boolean[] {false, false, true, false}, (i, c, w, hh) -> seen.add(i));
        assertEquals(List.of(2), seen);
    }

    @Test
    void planKeepsTiming() {
        int[] d = new int[200];
        java.util.Arrays.fill(d, 5);
        Gif.Plan p = Gif.plan(d, 32);
        assertTrue(p.count() <= 32 && p.count() >= 25, "count " + p.count());
        assertEquals(200, p.totalTicks(), 7);
        for (int t : p.ticks()) {
            assertEquals(7, t);
        }
        for (int i = 1; i < p.count(); i++) {
            assertTrue(p.sources()[i] > p.sources()[i - 1]);
        }
        Gif.Plan few = Gif.plan(new int[] {10, 10, 10}, 32);
        assertArrayEquals(new int[] {0, 1, 2}, few.sources());
        assertArrayEquals(new int[] {2, 2, 2}, few.ticks());
        Gif.Plan uneven = Gif.plan(new int[] {10, 40, 10}, 32);
        assertArrayEquals(new int[] {0, 1, 2}, uneven.sources());
        assertArrayEquals(new int[] {2, 8, 2}, uneven.ticks());
        Gif.Plan zero = Gif.plan(new int[] {0, 1}, 32);
        assertArrayEquals(new int[] {2, 2}, zero.ticks());
        Gif.Plan fast = Gif.plan(new int[] {2, 2, 2, 2, 2, 2, 2, 2, 2, 2}, 32);
        assertEquals(4, fast.totalTicks());
        Gif.Plan cut = Gif.plan(new int[] {5, 5, 5, 5, 5, 5, 5, 5}, 4);
        assertEquals(4, cut.count());
        assertEquals(8, cut.totalTicks());
        assertArrayEquals(new int[] {0, 2, 4, 6}, cut.sources());
        Gif.Plan huge = Gif.plan(new int[] {6000, 6000, 6000}, 2);
        assertTrue(huge.count() <= 2);
    }

    @Test
    void mcmetaJson() {
        JsonObject a = PackFiles.animation(512, 256, new int[] {7, 7, 7}, false).getAsJsonObject("animation");
        assertEquals(512, a.get("width").getAsInt());
        assertEquals(256, a.get("height").getAsInt());
        assertEquals(7, a.get("frametime").getAsInt());
        assertFalse(a.has("frames"));
        assertFalse(a.has("interpolate"));
        JsonObject b = PackFiles.animation(512, 256, new int[] {2, 8, 2}, true).getAsJsonObject("animation");
        assertTrue(b.get("interpolate").getAsBoolean());
        assertEquals(2, b.get("frametime").getAsInt());
        assertEquals(0, b.getAsJsonArray("frames").get(0).getAsInt());
        assertEquals(8, b.getAsJsonArray("frames").get(1).getAsJsonObject().get("time").getAsInt());
        assertEquals(1, b.getAsJsonArray("frames").get(1).getAsJsonObject().get("index").getAsInt());
        String text = PackFiles.json(PackFiles.animation(256, 128, new int[] {3, 3}, false));
        assertEquals(3, JsonParser.parseString(text).getAsJsonObject().getAsJsonObject("animation")
            .get("frametime").getAsInt());
    }

    static ImageManager.Anim anim(int maxFrames, int width) {
        return new ImageManager.Anim(maxFrames, width, false, 64L << 20, 256L << 20, 64L << 20, 1000);
    }

    @Test
    void animatedGifBecomesStripWithSentinelPerFrame(@TempDir Path dir) throws Exception {
        byte[] data = gif(8, 6, tricky());
        ImageManager.Built b = ImageManager.build(data, 40_000_000, 41, anim(32, 256));
        assertTrue(b.animated());
        assertEquals(256, b.frameW());
        assertEquals(128, b.frameH());
        assertEquals(4, b.ticks().length);
        assertArrayEquals(new int[] {2, 4, 6, 2}, b.ticks());
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(b.png()));
        assertEquals(256, img.getWidth());
        assertEquals(128 * 4, img.getHeight());
        for (int k = 0; k < 4; k++) {
            int y = k * 128;
            assertEquals(0x033DCB93, img.getRGB(0, y));
            assertEquals(0x032993CB, img.getRGB(1, y));
            assertEquals(3, img.getRGB(2, y) >>> 24);
            assertEquals(0x03100859, img.getRGB(3, y));
            assertEquals(255, img.getRGB(100, y + 64) >>> 24);
        }
        assertTrue(((img.getRGB(64, 64) >> 16) & 255) > 200);
        int blueRow = 128 * 2 + 70;
        boolean sawBlue = false;
        for (int x = 0; x < 256; x++) {
            int c = img.getRGB(x, blueRow);
            sawBlue |= (c & 255) > 200 && ((c >> 16) & 255) < 60;
        }
        assertTrue(sawBlue);
        ImageManager.write(dir, "anim_pic", b);
        Path meta = dir.resolve(PackFiles.meta("anim_pic"));
        assertTrue(Files.isRegularFile(meta));
        JsonObject m = JsonParser.parseString(Files.readString(meta)).getAsJsonObject().getAsJsonObject("animation");
        assertEquals(256, m.get("width").getAsInt());
        assertEquals(128, m.get("height").getAsInt());
        for (String p : PackFiles.paths("anim_pic")) {
            assertTrue(Files.isRegularFile(dir.resolve(p)), p);
        }
        assertArrayEquals(new int[] {256, 512}, ImageManager.pngSize(dir.resolve(PackFiles.paths("anim_pic").get(0))));
        assertEquals(256L * 512 * 4, ImageManager.animatedBytes(dir, Set.of("anim_pic", "missing")));
        ImageManager.Built still = ImageManager.build(gif(8, 6, List.of(tricky().get(0))), 40_000_000, 41,
            anim(32, 256));
        assertFalse(still.animated());
        assertEquals(Panorama.W, still.frameW());
        ImageManager.write(dir, "anim_pic", still);
        assertFalse(Files.exists(meta));
        assertEquals(0L, ImageManager.animatedBytes(dir, Set.of("anim_pic")));
    }

    @Test
    void framesAreSubsampledToTheLimit() throws Exception {
        List<F> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            many.add(new F(0, 0, 4, 4, 0xFF000000 | (i * 6 << 16), false, "none", 5));
        }
        ImageManager.Built b = ImageManager.build(gif(4, 4, many), 40_000_000, 50, anim(8, 512));
        assertTrue(b.ticks().length <= 8);
        long total = 0;
        for (int t : b.ticks()) {
            total += t;
        }
        assertEquals(40, total, 5);
        assertEquals(256, b.frameH());
    }

    @Test
    void budgetsGiveClearErrors() throws Exception {
        byte[] data = gif(8, 6, tricky());
        ImageManager.Fail tex = assertThrows(ImageManager.Fail.class, () -> ImageManager.build(data, 40_000_000, 41,
            new ImageManager.Anim(32, 1024, false, 1L << 20, 256L << 20, 64L << 20, 1000)));
        assertEquals("image-anim-budget", tex.key);
        ImageManager.Fail file = assertThrows(ImageManager.Fail.class, () -> ImageManager.build(data, 40_000_000, 41,
            new ImageManager.Anim(32, 256, false, 64L << 20, 256L << 20, 100, 1000)));
        assertEquals("image-anim-file", file.key);
        ImageManager.Fail frames = assertThrows(ImageManager.Fail.class, () -> ImageManager.build(data, 40_000_000, 41,
            new ImageManager.Anim(32, 256, false, 64L << 20, 256L << 20, 64L << 20, 2)));
        assertEquals("image-anim-frames", frames.key);
        ImageManager.Fail px = assertThrows(ImageManager.Fail.class, () -> ImageManager.build(data, 10, 41,
            anim(32, 256)));
        assertEquals("image-too-large", px.key);
        assertNull(ImageManager.pngSize(Path.of("does-not-exist.png")));
    }

    @Test
    void framesAreCappedByTextureBudget() throws Exception {
        List<F> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            many.add(new F(0, 0, 4, 4, 0xFF000000 | (i * 12 << 8), false, "none", 10));
        }
        ImageManager.Built b = ImageManager.build(gif(4, 4, many), 40_000_000, 50,
            new ImageManager.Anim(32, 1024, false, 6L << 20, 256L << 20, 64L << 20, 1000));
        assertTrue(b.animated());
        assertTrue(b.ticks().length <= 3 && b.ticks().length >= 2, "frames " + b.ticks().length);
        assertTrue(b.textureBytes() <= 6L << 20);
    }

    @Test
    void oversizedFrameDescriptorIsRejected() throws Exception {
        byte[] wide = gif(8, 6, List.of(new F(0, 0, 8, 6, RED, false, "none", 10),
            new F(0, 0, 40, 40, GREEN, false, "none", 10)));
        ImageManager.Fail px = assertThrows(ImageManager.Fail.class, () -> ImageManager.build(wide, 1000, 41,
            anim(32, 256)));
        assertEquals("image-too-large", px.key);
        assertEquals("40x40", px.detail);
        byte[] huge = gif(4, 4, List.of(new F(0, 0, 4, 4, RED, false, "none", 10),
            new F(0, 0, 300, 300, GREEN, false, "none", 10)));
        ImageManager.Fail far = assertThrows(ImageManager.Fail.class, () -> ImageManager.build(huge, 40_000_000, 41,
            anim(32, 256)));
        assertEquals("image-too-large", far.key);
    }

    @Test
    void frameWidthIsSnapped() {
        assertEquals(512, Panorama.frameWidth(512));
        assertEquals(256, Panorama.frameWidth(100));
        assertEquals(1024, Panorama.frameWidth(5000));
        assertEquals(512, Panorama.frameWidth(600));
    }
}
