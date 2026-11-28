package ink.hoka.odyssey.client.starmap;

import com.mojang.blaze3d.platform.NativeImage;

/**
 * Свой растеризатор со сглаживанием для статичных слоёв радара (кольца, сетка, риски, рамка окна, значки). GUI игры
 * умеет только прямоугольники и текстуры, поэтому тонкие ровные линии рисуются здесь — по расстоянию от пикселя
 * до фигуры (покрытие 0..1 на краю), один раз при смене размера окна, — и уходят в текстуру. java.awt не
 * используется: на macOS он может зависнуть вместе с GLFW.
 * <p>
 * Буфер — RGBA с предумноженной прозрачностью (float), смешивание «поверх». Координаты — в пикселях, центр
 * пикселя (x, y) — точка (x + 0.5, y + 0.5).
 */
final class Raster {
    final int w, h;
    private final float[] r, g, b, a;

    Raster(int w, int h) {
        this.w = Math.max(1, w);
        this.h = Math.max(1, h);
        int n = this.w * this.h;
        r = new float[n];
        g = new float[n];
        b = new float[n];
        a = new float[n];
    }

    /** Смешать цвет rgb с прозрачностью ca (0..1) поверх пикселя. */
    void over(int x, int y, int rgb, float ca) {
        if (x < 0 || y < 0 || x >= w || y >= h || ca <= 0.002f) return;
        if (ca > 1) ca = 1;
        int i = y * w + x;
        float ia = 1 - ca;
        r[i] = ((rgb >> 16) & 255) / 255f * ca + r[i] * ia;
        g[i] = ((rgb >> 8) & 255) / 255f * ca + g[i] * ia;
        b[i] = (rgb & 255) / 255f * ca + b[i] * ia;
        a[i] = ca + a[i] * ia;
    }

    /** Прозрачность пикселя (0..1). */
    float alphaAt(int x, int y) {
        return x < 0 || y < 0 || x >= w || y >= h ? 0 : a[y * w + x];
    }

    /** Покрытие линии толщины width на расстоянии d от её оси, плюс мягкое свечение glowA·exp(−(d/glowR)²). */
    private static float stroke(float d, float width, float glowA, float glowR) {
        float core = Math.max(0, Math.min(1, width / 2 + 0.5f - Math.abs(d)));
        if (glowA <= 0) return core;
        float t = d / glowR;
        return Math.max(core, glowA * (float) Math.exp(-t * t));
    }

    /** Окружность (кольцо) радиуса rad толщиной width. */
    void ring(float cx, float cy, float rad, float width, int rgb, float alpha, float glowA, float glowR) {
        float ext = width / 2 + 1 + (glowA > 0 ? glowR * 2.2f : 0);
        int y0 = (int) Math.floor(cy - rad - ext), y1 = (int) Math.ceil(cy + rad + ext);
        for (int y = Math.max(0, y0); y <= Math.min(h - 1, y1); y++) {
            float py = y + 0.5f - cy;
            // по строке — только участки у окружности, а не весь квадрат
            float outer = rad + ext, inner = rad - ext;
            if (Math.abs(py) > outer) continue;
            float xo = (float) Math.sqrt(outer * outer - py * py);
            float xi = Math.abs(py) < inner ? (float) Math.sqrt(inner * inner - py * py) : -1;
            for (int side = -1; side <= 1; side += 2) {
                int xa, xb;
                if (side < 0) {
                    xa = (int) Math.floor(cx - xo);
                    xb = xi < 0 ? (int) Math.ceil(cx + xo) : (int) Math.ceil(cx - xi);
                } else {
                    if (xi < 0) break;
                    xa = (int) Math.floor(cx + xi);
                    xb = (int) Math.ceil(cx + xo);
                }
                for (int x = Math.max(0, xa); x <= Math.min(w - 1, xb); x++) {
                    float px = x + 0.5f - cx;
                    float d = (float) Math.sqrt(px * px + py * py) - rad;
                    over(x, y, rgb, alpha * stroke(d, width, glowA, glowR));
                }
            }
        }
    }

    /** Отрезок (x0, y0)–(x1, y1) толщиной width с круглыми концами. */
    void seg(float x0, float y0, float x1, float y1, float width, int rgb, float alpha, float glowA, float glowR) {
        float ext = width / 2 + 1 + (glowA > 0 ? glowR * 2.2f : 0);
        int xa = (int) Math.floor(Math.min(x0, x1) - ext), xb = (int) Math.ceil(Math.max(x0, x1) + ext);
        int ya = (int) Math.floor(Math.min(y0, y1) - ext), yb = (int) Math.ceil(Math.max(y0, y1) + ext);
        float dx = x1 - x0, dy = y1 - y0, len2 = Math.max(1e-6f, dx * dx + dy * dy);
        for (int y = Math.max(0, ya); y <= Math.min(h - 1, yb); y++) {
            for (int x = Math.max(0, xa); x <= Math.min(w - 1, xb); x++) {
                float px = x + 0.5f - x0, py = y + 0.5f - y0;
                float t = Math.max(0, Math.min(1, (px * dx + py * dy) / len2));
                float ex = px - t * dx, ey = py - t * dy;
                over(x, y, rgb, alpha * stroke((float) Math.sqrt(ex * ex + ey * ey), width, glowA, glowR));
            }
        }
    }

    /** Знаковое расстояние до скруглённого прямоугольника (меньше нуля — внутри). */
    private static float roundBox(float px, float py, float x0, float y0, float x1, float y1, float rad) {
        float cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
        float hx = (x1 - x0) / 2 - rad, hy = (y1 - y0) / 2 - rad;
        float qx = Math.abs(px - cx) - hx, qy = Math.abs(py - cy) - hy;
        float ox = Math.max(qx, 0), oy = Math.max(qy, 0);
        return (float) Math.sqrt(ox * ox + oy * oy) + Math.min(Math.max(qx, qy), 0) - rad;
    }

    /** Скруглённый прямоугольник: заливка fill (alpha fillA) и рамка border толщины bw (0 — без рамки). */
    void roundRect(float x0, float y0, float x1, float y1, float rad, int fill, float fillA, int border, float bw, float borderA) {
        for (int y = Math.max(0, (int) Math.floor(y0) - 1); y <= Math.min(h - 1, (int) Math.ceil(y1)); y++) {
            for (int x = Math.max(0, (int) Math.floor(x0) - 1); x <= Math.min(w - 1, (int) Math.ceil(x1)); x++) {
                float sd = roundBox(x + 0.5f, y + 0.5f, x0, y0, x1, y1, rad);
                if (fillA > 0) over(x, y, fill, fillA * Math.max(0, Math.min(1, 0.5f - sd)));
                if (bw > 0) over(x, y, border, borderA * Math.max(0, Math.min(1, bw / 2 + 0.5f - Math.abs(sd + bw / 2))));
            }
        }
    }

    /** Прямоугольник, обрезанный скруглённым прямоугольником (шапка окна). */
    void rectClipped(int rx0, int ry0, int rx1, int ry1, int rgb, float alpha, float x0, float y0, float x1, float y1, float rad) {
        for (int y = Math.max(0, ry0); y < Math.min(h, ry1); y++) {
            for (int x = Math.max(0, rx0); x < Math.min(w, rx1); x++) {
                float sd = roundBox(x + 0.5f, y + 0.5f, x0, y0, x1, y1, rad);
                over(x, y, rgb, alpha * Math.max(0, Math.min(1, 0.5f - sd)));
            }
        }
    }

    /** Знаковое расстояние до выпуклого многоугольника (вершины по часовой стрелке на экране). */
    private static float convex(float px, float py, float[] xs, float[] ys) {
        float best = -Float.MAX_VALUE;
        int n = xs.length;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            float ex = xs[j] - xs[i], ey = ys[j] - ys[i];
            float len = (float) Math.sqrt(ex * ex + ey * ey);
            if (len < 1e-6f) continue;
            // нормаль наружу для обхода по часовой (y вниз): (ey, −ex)
            float d = ((px - xs[i]) * ey - (py - ys[i]) * ex) / len;
            best = Math.max(best, d);
        }
        return best;
    }

    /**
     * Выпуклый многоугольник: заливка (fillA) и/или контур толщины bw. Вершины — по часовой стрелке на экране.
     * Покрытие — по 3×3 точкам в пикселе (острые углы ромбов и стрелки остаются острыми).
     */
    void poly(float[] xs, float[] ys, int fill, float fillA, int line, float bw, float lineA) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < xs.length; i++) {
            minX = Math.min(minX, xs[i]);
            maxX = Math.max(maxX, xs[i]);
            minY = Math.min(minY, ys[i]);
            maxY = Math.max(maxY, ys[i]);
        }
        int pad = (int) Math.ceil(bw) + 2;
        for (int y = Math.max(0, (int) minY - pad); y <= Math.min(h - 1, (int) maxY + pad); y++) {
            for (int x = Math.max(0, (int) minX - pad); x <= Math.min(w - 1, (int) maxX + pad); x++) {
                float cf = 0, cl = 0;
                for (int sy = 0; sy < 3; sy++) {
                    for (int sx = 0; sx < 3; sx++) {
                        float d = convex(x + (sx + 0.5f) / 3f, y + (sy + 0.5f) / 3f, xs, ys);
                        if (d <= 0) cf++;
                        if (bw > 0 && d <= 0 && d >= -bw) cl++;
                    }
                }
                if (fillA > 0) over(x, y, fill, fillA * cf / 9f);
                if (bw > 0) over(x, y, line, lineA * cl / 9f);
            }
        }
    }

    /** Закрашенный круг. */
    void disc(float cx, float cy, float rad, int rgb, float alpha) {
        for (int y = Math.max(0, (int) (cy - rad) - 1); y <= Math.min(h - 1, (int) (cy + rad) + 1); y++) {
            for (int x = Math.max(0, (int) (cx - rad) - 1); x <= Math.min(w - 1, (int) (cx + rad) + 1); x++) {
                float dx = x + 0.5f - cx, dy = y + 0.5f - cy;
                float d = (float) Math.sqrt(dx * dx + dy * dy) - rad;
                over(x, y, rgb, alpha * Math.max(0, Math.min(1, 0.5f - d)));
            }
        }
    }

    /** Записать в картинку (ARGB, без предумножения) со смещением (ox, oy). */
    void writeTo(NativeImage img, int ox, int oy) {
        int iw = img.getWidth(), ih = img.getHeight();
        for (int y = 0; y < h; y++) {
            int ty = y + oy;
            if (ty < 0 || ty >= ih) continue;
            for (int x = 0; x < w; x++) {
                int tx = x + ox;
                if (tx < 0 || tx >= iw) continue;
                int i = y * w + x;
                float al = a[i];
                if (al <= 0.002f) {
                    img.setPixel(tx, ty, 0);
                    continue;
                }
                int ai = Math.min(255, Math.round(al * 255));
                int ri = Math.min(255, Math.round(r[i] / al * 255));
                int gi = Math.min(255, Math.round(g[i] / al * 255));
                int bi = Math.min(255, Math.round(b[i] / al * 255));
                img.setPixel(tx, ty, ai << 24 | ri << 16 | gi << 8 | bi);
            }
        }
    }
}
