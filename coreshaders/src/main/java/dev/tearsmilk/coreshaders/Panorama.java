package dev.tearsmilk.coreshaders;

import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

public final class Panorama {
    public static final int W = 1024;
    public static final int H = 512;
    public static final int GLOW = 16;
    public static final int KIND_MIN = 33;
    public static final int KIND_MAX = 200;
    public static final int GLOW_KIND = 255;
    public static final double PANORAMA_ASPECT = 1.8;
    public static final int SIZE_TAG = 89;
    public static final List<Integer> WIDTHS = List.of(1024, 512, 256);
    private static final int[] SENT = {61, 203, 147, 3};
    private static final Pattern NAME = Pattern.compile("[a-z0-9_]{1,32}");

    public enum Mode {
        PANORAMA,
        MIRROR
    }

    private Panorama() {
    }

    public static boolean validName(String s) {
        return s != null && NAME.matcher(s).matches();
    }

    public static boolean reservedName(String s) {
        return s == null || s.endsWith("_glow");
    }

    public static int allocateKind(Collection<Integer> used) {
        for (int k = KIND_MIN; k <= KIND_MAX; k++) {
            if (!used.contains(k)) {
                return k;
            }
        }
        return -1;
    }

    public static Mode mode(int w, int h) {
        return (double) w / h >= PANORAMA_ASPECT ? Mode.PANORAMA : Mode.MIRROR;
    }

    public static int frameWidth(int requested) {
        int best = WIDTHS.get(0);
        for (int v : WIDTHS) {
            if (Math.abs(v - requested) < Math.abs(best - requested)) {
                best = v;
            }
        }
        return best;
    }

    public static int[] convert(int[] argb, int w, int h) {
        return convert(argb, w, h, W, H);
    }

    public static int[] convert(int[] argb, int w, int h, int pw, int ph) {
        if (w <= 0 || h <= 0 || argb.length < w * h || pw < 4 || ph < 2) {
            throw new IllegalArgumentException("bad image size");
        }
        int[] out = new int[pw * ph];
        if (mode(w, h) == Mode.PANORAMA) {
            double cw = Math.min(w, h * 2.0);
            double ch = cw / 2.0;
            int[] body = resample(argb, w, h, (w - cw) / 2.0, (h - ch) / 2.0, cw, ch, pw, ph);
            System.arraycopy(body, 0, out, 0, out.length);
        } else {
            double side = Math.min(w, h);
            int half = pw / 2;
            int[] tile = resample(argb, w, h, (w - side) / 2.0, (h - side) / 2.0, side, side, half, ph);
            for (int y = 0; y < ph; y++) {
                for (int x = 0; x < half; x++) {
                    int c = tile[y * half + x];
                    out[y * pw + x] = c;
                    out[y * pw + pw - 1 - x] = c;
                }
            }
        }
        for (int i = 0; i < out.length; i++) {
            out[i] = 0xFF000000 | (out[i] & 0xFFFFFF);
        }
        return out;
    }

    private record Weights(int[] start, float[][] w) {
    }

    private static Weights weights(int srcLen, double c0, double len, int dst) {
        double scale = len / dst;
        double r = Math.max(1.0, scale);
        int lo = Math.max(0, (int) Math.floor(c0));
        int hi = Math.min(srcLen - 1, (int) Math.ceil(c0 + len) - 1);
        int[] start = new int[dst];
        float[][] w = new float[dst][];
        for (int i = 0; i < dst; i++) {
            double c = c0 + (i + 0.5) * scale - 0.5;
            int a = (int) Math.ceil(c - r);
            int b = (int) Math.floor(c + r);
            if (b < a) {
                b = a;
            }
            int s = Math.max(lo, Math.min(hi, a));
            int e = Math.max(lo, Math.min(hi, b));
            float[] ws = new float[e - s + 1];
            double sum = 0.0;
            for (int j = a; j <= b; j++) {
                double wt = Math.max(0.0, 1.0 - Math.abs(j - c) / r);
                int jj = Math.max(lo, Math.min(hi, j));
                ws[jj - s] += (float) wt;
                sum += wt;
            }
            if (sum <= 0.0) {
                int jj = Math.max(lo, Math.min(hi, (int) Math.round(c)));
                ws = new float[] {1f};
                s = jj;
            } else {
                for (int k = 0; k < ws.length; k++) {
                    ws[k] /= (float) sum;
                }
            }
            start[i] = s;
            w[i] = ws;
        }
        return new Weights(start, w);
    }

    static int[] resample(int[] src, int sw, int sh, double cx, double cy, double cw, double ch, int dw, int dh) {
        Weights wx = weights(sw, cx, cw, dw);
        Weights wy = weights(sh, cy, ch, dh);
        int rowLo = Math.max(0, (int) Math.floor(cy));
        int rowHi = Math.min(sh - 1, (int) Math.ceil(cy + ch) - 1);
        int rows = rowHi - rowLo + 1;
        float[] tmp = new float[rows * dw * 3];
        for (int y = 0; y < rows; y++) {
            int base = (rowLo + y) * sw;
            for (int x = 0; x < dw; x++) {
                float r = 0f;
                float g = 0f;
                float b = 0f;
                float[] ws = wx.w()[x];
                int s = wx.start()[x];
                for (int k = 0; k < ws.length; k++) {
                    int c = src[base + s + k];
                    float a = ((c >>> 24) & 255) / 255f;
                    r += ws[k] * ((c >> 16) & 255) * a;
                    g += ws[k] * ((c >> 8) & 255) * a;
                    b += ws[k] * (c & 255) * a;
                }
                int o = (y * dw + x) * 3;
                tmp[o] = r;
                tmp[o + 1] = g;
                tmp[o + 2] = b;
            }
        }
        int[] out = new int[dw * dh];
        for (int y = 0; y < dh; y++) {
            float[] ws = wy.w()[y];
            int s = wy.start()[y] - rowLo;
            for (int x = 0; x < dw; x++) {
                float r = 0f;
                float g = 0f;
                float b = 0f;
                for (int k = 0; k < ws.length; k++) {
                    int o = ((s + k) * dw + x) * 3;
                    r += ws[k] * tmp[o];
                    g += ws[k] * tmp[o + 1];
                    b += ws[k] * tmp[o + 2];
                }
                out[y * dw + x] = 0xFF000000 | (clamp8(r) << 16) | (clamp8(g) << 8) | clamp8(b);
            }
        }
        return out;
    }

    private static int clamp8(float v) {
        return Math.max(0, Math.min(255, Math.round(v)));
    }

    public static int glowColor(int[] pano) {
        double r = 0;
        double g = 0;
        double b = 0;
        double n = 0;
        for (int i = 0; i < pano.length; i += 7) {
            int c = pano[i];
            double cr = ((c >> 16) & 255) / 255.0;
            double cg = ((c >> 8) & 255) / 255.0;
            double cb = (c & 255) / 255.0;
            double wt = 0.2 + Math.max(cr, Math.max(cg, cb));
            r += cr * wt;
            g += cg * wt;
            b += cb * wt;
            n += wt;
        }
        if (n <= 0) {
            return 0xCCF2FF;
        }
        r /= n;
        g /= n;
        b /= n;
        double max = Math.max(r, Math.max(g, b));
        double min = Math.min(r, Math.min(g, b));
        if (max <= 1e-6 || (max - min) / max < 0.06) {
            return 0xCCF2FF;
        }
        double s = Math.max(0.35, Math.min(0.85, (max - min) / max * 1.5));
        double h;
        if (max == r) {
            h = ((g - b) / (max - min)) % 6.0;
        } else if (max == g) {
            h = (b - r) / (max - min) + 2.0;
        } else {
            h = (r - g) / (max - min) + 4.0;
        }
        if (h < 0) {
            h += 6.0;
        }
        return hsv(h, s, 1.0);
    }

    static int hsv(double h, double s, double v) {
        double c = v * s;
        double x = c * (1 - Math.abs(h % 2.0 - 1));
        double m = v - c;
        double r;
        double g;
        double b;
        int seg = (int) Math.floor(h) % 6;
        switch (seg) {
            case 0 -> { r = c; g = x; b = 0; }
            case 1 -> { r = x; g = c; b = 0; }
            case 2 -> { r = 0; g = c; b = x; }
            case 3 -> { r = 0; g = x; b = c; }
            case 4 -> { r = x; g = 0; b = c; }
            default -> { r = c; g = 0; b = x; }
        }
        int ir = (int) Math.round((r + m) * 255);
        int ig = (int) Math.round((g + m) * 255);
        int ib = (int) Math.round((b + m) * 255);
        return (ir << 16) | (ig << 8) | ib;
    }

    private static int argb(int a, int r, int g, int b) {
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public static int[] withSentinel(int[] pano, int kind, int glow) {
        return withSentinel(pano, W, H, kind, glow);
    }

    public static int[] withSentinel(int[] pano, int pw, int ph, int kind, int glow) {
        if (pano.length != pw * ph || pw % 16 != 0 || ph % 16 != 0 || pw > 4080 || ph > 4080) {
            throw new IllegalArgumentException("bad sprite size");
        }
        int[] out = pano.clone();
        System.arraycopy(out, pw, out, 0, pw);
        out[0] = argb(SENT[3], SENT[0], SENT[1], SENT[2]);
        out[1] = argb(3, kind, SENT[2], SENT[1]);
        out[2] = (3 << 24) | (glow & 0xFFFFFF);
        out[3] = argb(3, pw / 16, ph / 16, SIZE_TAG);
        return out;
    }

    public static int[] glowSprite(int glow) {
        int[] out = new int[GLOW * GLOW];
        for (int i = 0; i < out.length; i++) {
            out[i] = glow & 0xFFFFFF;
        }
        out[0] = argb(SENT[3], SENT[0], SENT[1], SENT[2]);
        out[1] = argb(3, GLOW_KIND, SENT[2], SENT[1]);
        out[2] = (3 << 24) | (glow & 0xFFFFFF);
        return out;
    }
}
