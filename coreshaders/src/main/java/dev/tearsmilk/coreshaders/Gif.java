package dev.tearsmilk.coreshaders;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

public final class Gif {
    public static final int TICK_CS = 5;
    public static final int MIN_DELAY_CS = 2;
    public static final int DEFAULT_DELAY_CS = 10;
    public static final int MAX_DELAY_CS = 6000;

    public enum Disposal {
        NONE,
        KEEP,
        BACKGROUND,
        PREVIOUS
    }

    public record FrameInfo(int left, int top, int width, int height, int delayCs, Disposal disposal) {
    }

    public record Header(int width, int height, List<FrameInfo> frames) {
        public int[] delays() {
            int[] d = new int[frames.size()];
            for (int i = 0; i < d.length; i++) {
                d[i] = frames.get(i).delayCs();
            }
            return d;
        }
    }

    public record Plan(int[] sources, int[] ticks) {
        public int count() {
            return sources.length;
        }

        public long totalTicks() {
            long s = 0;
            for (int t : ticks) {
                s += t;
            }
            return s;
        }
    }

    public interface Sink {
        void frame(int index, int[] canvas, int width, int height) throws Exception;
    }

    private Gif() {
    }

    public static int delay(int cs) {
        return cs < MIN_DELAY_CS ? DEFAULT_DELAY_CS : cs;
    }

    public static Plan plan(int[] delaysCs, int maxFrames) {
        int n = delaysCs.length;
        if (n == 0 || maxFrames < 1) {
            throw new IllegalArgumentException("no frames");
        }
        long[] end = new long[n];
        long total = 0;
        for (int i = 0; i < n; i++) {
            total += Math.min(MAX_DELAY_CS, delay(delaysCs[i]));
            end[i] = total;
        }
        long maxTicks = Math.max(1L, (total + TICK_CS - 1) / TICK_CS);
        long t = 1;
        int step = 0;
        while (true) {
            long tt = Math.min(t, maxTicks);
            Plan p = sample(end, total, tt, maxFrames);
            if (p != null) {
                return p;
            }
            if (tt == maxTicks) {
                break;
            }
            step++;
            t = step < 1000 ? t + 1 : Math.max(t + 1, (long) (t * 1.05));
        }
        return new Plan(new int[] {0}, new int[] {(int) Math.min(Integer.MAX_VALUE, maxTicks)});
    }

    private static Plan sample(long[] end, long total, long t, int maxFrames) {
        long samples = Math.max(1L, Math.round((double) total / (TICK_CS * t)));
        int[] sources = new int[maxFrames];
        int[] ticks = new int[maxFrames];
        int k = 0;
        long prev = 0;
        for (int i = 0; i < end.length; i++) {
            long hi = Math.min(samples, Math.max(0L, (long) Math.ceil((double) end[i] * samples / total)));
            if (i == end.length - 1) {
                hi = samples;
            }
            long cnt = hi - prev;
            prev = Math.max(prev, hi);
            if (cnt <= 0) {
                continue;
            }
            if (k >= maxFrames) {
                return null;
            }
            sources[k] = i;
            ticks[k] = (int) Math.min(Integer.MAX_VALUE, cnt * t);
            k++;
        }
        return new Plan(Arrays.copyOf(sources, k), Arrays.copyOf(ticks, k));
    }

    private static Node child(Node n, String name) {
        for (Node c = n.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (name.equals(c.getNodeName())) {
                return c;
            }
        }
        return null;
    }

    private static int intAttr(Node n, String name, int def) {
        if (n == null) {
            return def;
        }
        NamedNodeMap a = n.getAttributes();
        Node v = a == null ? null : a.getNamedItem(name);
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(v.getNodeValue());
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    private static String strAttr(Node n, String name) {
        if (n == null || n.getAttributes() == null) {
            return null;
        }
        Node v = n.getAttributes().getNamedItem(name);
        return v == null ? null : v.getNodeValue();
    }

    static Disposal disposal(String s) {
        if (s == null) {
            return Disposal.NONE;
        }
        return switch (s) {
            case "doNotDispose" -> Disposal.KEEP;
            case "restoreToBackgroundColor" -> Disposal.BACKGROUND;
            case "restoreToPrevious" -> Disposal.PREVIOUS;
            default -> Disposal.NONE;
        };
    }

    public static Header header(ImageReader r, int maxFrames) throws IOException {
        int count = r.getNumImages(true);
        if (count < 1) {
            throw new IOException("no frames");
        }
        if (count > maxFrames) {
            throw new FrameLimit(count);
        }
        int sw = -1;
        int sh = -1;
        IIOMetadata sm = r.getStreamMetadata();
        if (sm != null) {
            Node root = sm.getAsTree("javax_imageio_gif_stream_1.0");
            Node lsd = child(root, "LogicalScreenDescriptor");
            sw = intAttr(lsd, "logicalScreenWidth", -1);
            sh = intAttr(lsd, "logicalScreenHeight", -1);
        }
        List<FrameInfo> frames = new ArrayList<>(count);
        int maxW = 0;
        int maxH = 0;
        for (int i = 0; i < count; i++) {
            Node root = r.getImageMetadata(i).getAsTree("javax_imageio_gif_image_1.0");
            Node id = child(root, "ImageDescriptor");
            Node gce = child(root, "GraphicControlExtension");
            int left = intAttr(id, "imageLeftPosition", 0);
            int top = intAttr(id, "imageTopPosition", 0);
            int w = intAttr(id, "imageWidth", r.getWidth(i));
            int h = intAttr(id, "imageHeight", r.getHeight(i));
            maxW = Math.max(maxW, left + w);
            maxH = Math.max(maxH, top + h);
            frames.add(new FrameInfo(left, top, w, h, intAttr(gce, "delayTime", 0), disposal(strAttr(gce, "disposalMethod"))));
        }
        if (sw <= 0 || sh <= 0) {
            sw = maxW;
            sh = maxH;
        }
        return new Header(sw, sh, List.copyOf(frames));
    }

    static final class FrameLimit extends IOException {
        private static final long serialVersionUID = 1L;
        final int count;

        FrameLimit(int count) {
            super("frames " + count);
            this.count = count;
        }
    }

    static void draw(int[] canvas, int cw, int ch, BufferedImage img, FrameInfo f) {
        int w = Math.min(img.getWidth(), f.width());
        int h = Math.min(img.getHeight(), f.height());
        int[] row = new int[Math.max(1, w)];
        for (int y = 0; y < h; y++) {
            int cy = f.top() + y;
            if (cy < 0 || cy >= ch) {
                continue;
            }
            img.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; x++) {
                int cx = f.left() + x;
                if (cx < 0 || cx >= cw) {
                    continue;
                }
                int c = row[x];
                if ((c >>> 24) != 0) {
                    canvas[cy * cw + cx] = 0xFF000000 | (c & 0xFFFFFF);
                }
            }
        }
    }

    static void clear(int[] canvas, int cw, int ch, FrameInfo f) {
        int x0 = Math.max(0, f.left());
        int y0 = Math.max(0, f.top());
        int x1 = Math.min(cw, f.left() + f.width());
        int y1 = Math.min(ch, f.top() + f.height());
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                canvas[y * cw + x] = 0;
            }
        }
    }

    public static void decode(ImageReader r, Header hd, boolean[] wanted, Sink sink) throws Exception {
        int cw = hd.width();
        int ch = hd.height();
        int[] canvas = new int[cw * ch];
        int last = -1;
        for (int i = 0; i < wanted.length; i++) {
            if (wanted[i]) {
                last = i;
            }
        }
        for (int i = 0; i <= last; i++) {
            FrameInfo f = hd.frames().get(i);
            int[] saved = f.disposal() == Disposal.PREVIOUS ? canvas.clone() : null;
            BufferedImage img = r.read(i);
            draw(canvas, cw, ch, img, f);
            if (wanted[i]) {
                sink.frame(i, canvas, cw, ch);
            }
            if (f.disposal() == Disposal.BACKGROUND) {
                clear(canvas, cw, ch, f);
            } else if (saved != null) {
                canvas = saved;
            }
        }
    }
}
