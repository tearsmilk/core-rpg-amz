package ink.hoka.odyssey.client.starmap;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;

/**
 * Слой блоков на экране радара: не сплошная заливка, а мягкие полупрозрачные «кляксы» того, что выделяется.
 * Ровная земля (самая частая высота верхних блоков, если она занимает заметную часть круга) почти не видна;
 * чем дальше столбец от неё по высоте, тем он заметнее. Цвет — по высоте относительно тебя: выше — зелёный,
 * ниже — бирюзово-голубой. В космосе ровной земли нет — астероиды и станции видны целиком.
 * <p>
 * Картинка — в половину разрешения экрана ({@value #TEXEL} пикселя на тексель, сглаживание при выводе), уже
 * обрезанная по кругу с мягким краем. Перестраивается не каждый кадр: при смене масштаба сразу, при движении
 * вида, новых блоках или смене твоей высоты — не чаще раза в {@value #REBUILD_MS} мс; между перестройками
 * картинка просто сдвигается вслед за видом.
 */
final class TerrainView {
    static final int TEXEL = 2;
    private static final int REBUILD_MS = 120;

    private final GlTex tex = new GlTex("terrain");
    private int n;
    private double bx, bz, bs = -1;
    private int brev = -1, bRefY, br;
    private long builtAt;
    private int[] hb = new int[0];
    private float[] pr = new float[0], pg = new float[0], pb = new float[0], pa = new float[0];
    private final int[] hist = new int[1024];

    void draw(GuiGraphics g, Terrain t, int cx, int cy, int r, double viewX, double viewZ, double s, int refY, int argb) {
        if (!t.ready()) return;
        long now = System.currentTimeMillis();
        int size = (int) Math.ceil(2.0 * (r + 4) / TEXEL);
        double shift = Math.max(Math.abs(bx - viewX), Math.abs(bz - viewZ)) * s;
        boolean must = size != n || s != bs || r != br || !tex.ready();
        boolean want = t.rev != brev || refY != bRefY || shift > 0.5;
        if (must || (want && now - builtAt >= (shift > r * 0.03 ? 40 : REBUILD_MS))) {
            build(t, size, r, viewX, viewZ, s, refY);
            builtAt = now;
        }
        int half = n * TEXEL / 2;
        int x = (int) Math.round(cx - half + (bx - viewX) * s), y = (int) Math.round(cy - half + (bz - viewZ) * s);
        g.blit(RenderPipelines.GUI_TEXTURED, tex.id, x, y, 0f, 0f, n * TEXEL, n * TEXEL, n, n, n, n, argb);
    }

    private void build(Terrain t, int size, int r, double viewX, double viewZ, double s, int refY) {
        n = size;
        br = r;
        bx = viewX;
        bz = viewZ;
        bs = s;
        brev = t.rev;
        bRefY = refY;
        tex.ensure(n, n);
        int cnt = n * n;
        if (hb.length != cnt) {
            hb = new int[cnt];
            pr = new float[cnt];
            pg = new float[cnt];
            pb = new float[cnt];
            pa = new float[cnt];
        }
        java.util.Arrays.fill(hist, 0);
        double foot = TEXEL / s;                                  // блоков на тексель
        int sub = foot > 1.5 ? 2 : 1;
        double half = n * TEXEL / 2.0;
        int ox = t.originCX * 16, oz = t.originCZ * 16;
        int inCircle = 0;
        // 1) высоты по текселям (при мелком масштабе — самый высокий из 2×2 замеров) и гистограмма
        for (int j = 0; j < n; j++) {
            double dyPx = (j + 0.5) * TEXEL - half;
            for (int i = 0; i < n; i++) {
                double dxPx = (i + 0.5) * TEXEL - half;
                int k = j * n + i;
                if (dxPx * dxPx + dyPx * dyPx > (double) (r + 1) * (r + 1)) {
                    hb[k] = Integer.MIN_VALUE;
                    continue;
                }
                inCircle++;
                double wx = viewX + dxPx / s, wz = viewZ + dyPx / s;
                int best = Integer.MIN_VALUE;
                for (int sy = 0; sy < sub; sy++) {
                    for (int sx = 0; sx < sub; sx++) {
                        double ax = sub == 1 ? wx : wx + (sx - 0.5) * foot / 2, az = sub == 1 ? wz : wz + (sy - 0.5) * foot / 2;
                        int px = (int) Math.floor(ax) - ox, pz = (int) Math.floor(az) - oz;
                        if (px < 0 || pz < 0 || px >= Terrain.TEX || pz >= Terrain.TEX) continue;
                        short hv = t.height[pz * Terrain.TEX + px];
                        if (hv != Terrain.NONE && hv > best) best = hv;
                    }
                }
                hb[k] = best;
                if (best != Integer.MIN_VALUE) {
                    int bin = best - refY + 512;
                    if (bin >= 0 && bin < hist.length) hist[bin]++;
                }
            }
        }
        // 2) «земля» — самая частая высота (±1), если она занимает больше трети круга
        int mode = -1, modeCount = 0;
        for (int i = 1; i < hist.length - 1; i++) {
            int c = hist[i - 1] + hist[i] + hist[i + 1];
            if (c > modeCount) {
                modeCount = c;
                mode = i;
            }
        }
        boolean flat = mode >= 0 && modeCount >= inCircle * 0.33;
        int ground = mode - 512 + refY;
        // 3) цвет и прозрачность (предумноженные)
        for (int j = 0; j < n; j++) {
            double dyPx = (j + 0.5) * TEXEL - half;
            for (int i = 0; i < n; i++) {
                int k = j * n + i;
                int hv = hb[k];
                if (hv == Integer.MIN_VALUE) {
                    pr[k] = pg[k] = pb[k] = pa[k] = 0;
                    continue;
                }
                double dxPx = (i + 0.5) * TEXEL - half;
                float rho = (float) Math.sqrt(dxPx * dxPx + dyPx * dyPx);
                float al;
                if (flat) {
                    int d = Math.abs(hv - ground);
                    al = d <= 1 ? 0.035f : Math.min(0.82f, 0.30f + 0.065f * d);
                } else {
                    al = 0.80f;
                }
                al *= Math.max(0f, Math.min(1f, (r - rho) / 8f));    // мягкий край круга
                // выше тебя — от бирюзово-зелёного к ярко-зелёному, ниже — от бирюзового к голубому
                int dh = hv - refY;
                float kk = Math.min(1f, Math.abs(dh) / 40f);
                float cr, cg, cb;
                if (dh >= 0) {
                    cr = 70 + 40 * kk;
                    cg = 210 + 45 * kk;
                    cb = 150;
                } else {
                    cr = 60 - 10 * kk;
                    cg = 190 - 50 * kk;
                    cb = 180 + 50 * kk;
                }
                float shade = ((hv >> 2) & 1) == 0 ? 0.90f : 1f;         // едва заметные горизонтали
                pr[k] = cr / 255f * shade * al;
                pg[k] = cg / 255f * shade * al;
                pb[k] = cb / 255f * shade * al;
                pa[k] = al;
            }
        }
        // 4) мягкость: размытие 3×3 и запись в картинку
        NativeImage img = tex.img();
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                float sr = 0, sg = 0, sb = 0, sa = 0, wsum = 0;
                for (int dj = -1; dj <= 1; dj++) {
                    int jj = j + dj;
                    if (jj < 0 || jj >= n) continue;
                    for (int di = -1; di <= 1; di++) {
                        int ii = i + di;
                        if (ii < 0 || ii >= n) continue;
                        float wgt = (di == 0 ? 2 : 1) * (dj == 0 ? 2 : 1);
                        int k = jj * n + ii;
                        sr += pr[k] * wgt;
                        sg += pg[k] * wgt;
                        sb += pb[k] * wgt;
                        sa += pa[k] * wgt;
                        wsum += wgt;
                    }
                }
                sa /= wsum;
                if (sa < 0.004f) {
                    img.setPixel(i, j, 0);
                    continue;
                }
                int ai = Math.min(255, Math.round(sa * 255));
                int ri = Math.min(255, Math.round(sr / wsum / sa * 255));
                int gi = Math.min(255, Math.round(sg / wsum / sa * 255));
                int bi = Math.min(255, Math.round(sb / wsum / sa * 255));
                img.setPixel(i, j, ai << 24 | ri << 16 | gi << 8 | bi);
            }
        }
        tex.upload();
    }

    void close() {
        tex.close();
        n = 0;
        bs = -1;
        brev = -1;
    }
}
