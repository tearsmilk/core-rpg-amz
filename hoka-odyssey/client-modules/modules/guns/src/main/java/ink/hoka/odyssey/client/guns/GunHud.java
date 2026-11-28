package ink.hoka.odyssey.client.guns;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * HUD оружия в стиле радара starmap: справа внизу — панель «как экран прибора» (тёмное стекло, зелёный люминофор,
 * уголки рамки, строки развёртки): силуэт оружия, крупно патроны в магазине, запас, режим огня, полоса перезарядки.
 * В центре — свой прицел (четыре штриха, расходятся по разбросу и отдаче; в прицеле — скрыт) и метка попадания.
 * Рисуется в настоящих пикселях экрана (масштаб GUI снят), шрифт — {@link MonoFont}.
 */
final class GunHud {
    static final int G = 0xFF5AFFAA;
    static final int G_DIM = 0xFF3C9C70;
    static final int AMBER = 0xFFFFBE46;
    static final int RED = 0xFFFF6A55;
    static final int VALUE = 0xFF7CFFBE;
    static final int LABEL = 0xFF72C49A;

    /** Что показать (заполняет модуль каждый кадр). */
    static final class View {
        String name = "";
        int ammo;
        int magazine;
        int reserve;
        boolean auto;
        boolean bothModes;
        /** 0..1 — идёт перезарядка (доля), −1 — нет. */
        float reload = -1;
        boolean drawing;
        boolean vacuum;
        /** 0..1 — прицел (0 — от бедра). */
        float ads;
        /** Разброс, градусов (для прицела). */
        float spreadDeg;
        /** Отдача «сейчас» (0..1+) — прицел расходится сильнее. */
        float bloom;
        /** Метка попадания: вид (1 тело, 2 голова, 3 убийство) и сколько ей ещё (0..1). */
        int hitKind;
        float hitLeft;
        /** Силуэт оружия (текстура 384×128) или null. */
        Identifier silhouette;
        boolean crosshair = true;
        /** Подсказка (пустой магазин и т.п.), пусто — нет. */
        String hint = "";
    }

    private final MonoFont font;

    GunHud(MonoFont font) {
        this.font = font;
    }

    static int a(int argb, float a) {
        int al = Math.round(((argb >>> 24) & 255) * Math.max(0f, Math.min(1f, a)));
        return (al << 24) | (argb & 0xFFFFFF);
    }

    void render(GuiGraphics g, View v, boolean panel) {
        Minecraft mc = Minecraft.getInstance();
        Window win = mc.getWindow();
        int gs = Math.max(1, win.getGuiScale());
        int fbW = win.getWidth(), fbH = win.getHeight();
        var pose = g.pose();
        pose.pushMatrix();
        pose.scale(1f / gs, 1f / gs);                            // дальше — настоящие пиксели
        float u = Math.max(0.6f, Math.min(fbW / 1920f, fbH / 1080f) * 1.15f);
        if (v.crosshair) crosshair(g, fbW / 2, fbH / 2, v, u, fovPxPerDeg(mc, fbH));
        hitMarker(g, fbW / 2, fbH / 2, v, u);
        if (panel) panel(g, fbW, fbH, v, u);
        pose.popMatrix();
        font.flush();
    }

    /** Пикселей экрана на градус (по вертикальному углу обзора). */
    private static float fovPxPerDeg(Minecraft mc, int fbH) {
        double fov = mc.options.fov().get();
        return (float) (fbH / 2.0 / Math.tan(Math.toRadians(fov / 2))) * (float) Math.toRadians(1);
    }

    private void crosshair(GuiGraphics g, int cx, int cy, View v, float u, float pxPerDeg) {
        float vis = 1 - Math.min(1, v.ads * 1.6f);
        if (vis <= 0.02f) return;
        int gap = Math.round(Math.min(120 * u, Math.max(5 * u, v.spreadDeg * pxPerDeg * 0.9f + v.bloom * 14 * u)));
        int len = Math.max(4, Math.round(9 * u));
        int th = Math.max(1, Math.round(2 * u));
        int col = a(0xFFFFFFFF, 0.9f * vis);
        int sh = a(0xFF000000, 0.45f * vis);
        int h = th / 2;
        // тень, затем штрихи
        for (int pass = 0; pass < 2; pass++) {
            int c = pass == 0 ? sh : col;
            int o = pass == 0 ? 1 : 0;
            g.fill(cx - gap - len + o, cy - h + o, cx - gap + o, cy - h + th + o, c);
            g.fill(cx + gap + o, cy - h + o, cx + gap + len + o, cy - h + th + o, c);
            g.fill(cx - h + o, cy - gap - len + o, cx - h + th + o, cy - gap + o, c);
            g.fill(cx - h + o, cy + gap + o, cx - h + th + o, cy + gap + len + o, c);
        }
        int d = Math.max(1, Math.round(1.5f * u));
        g.fill(cx - d / 2 - (d & 1) + 1, cy - d / 2 - (d & 1) + 1, cx + d / 2 + 1, cy + d / 2 + 1, a(G, vis));
    }

    private void hitMarker(GuiGraphics g, int cx, int cy, View v, float u) {
        if (v.hitKind <= 0 || v.hitLeft <= 0) return;
        int col = v.hitKind == 1 ? 0xFFFFFFFF : RED;
        float al = Math.min(1, v.hitLeft * 2.5f);
        int r0 = Math.round((v.hitKind == 3 ? 9 : 7) * u), r1 = Math.round((v.hitKind == 3 ? 19 : 14) * u);
        int th = Math.max(1, Math.round((v.hitKind == 3 ? 3 : 2) * u));
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                for (int i = r0; i <= r1; i++) {
                    int x = cx + sx * i, y = cy + sy * i;
                    g.fill(x - th / 2, y - th / 2, x - th / 2 + th, y - th / 2 + th, a(col, al));
                }
            }
        }
    }

    private void panel(GuiGraphics g, int fbW, int fbH, View v, float u) {
        int pw = Math.round(330 * u), ph = Math.round(124 * u);
        int margin = Math.round(22 * u);
        int px = fbW - pw - margin, py = fbH - ph - margin;
        float flick = 0.96f + 0.04f * (float) Math.sin(System.currentTimeMillis() * 0.023);
        // стекло, рамка
        g.fill(px, py, px + pw, py + ph, 0xA0041410);
        g.fill(px, py, px + pw, py + 1, a(G_DIM, 0.8f));
        g.fill(px, py + ph - 1, px + pw, py + ph, a(G_DIM, 0.8f));
        g.fill(px, py, px + 1, py + ph, a(G_DIM, 0.8f));
        g.fill(px + pw - 1, py, px + pw, py + ph, a(G_DIM, 0.8f));
        int c = Math.round(14 * u), t = Math.max(2, Math.round(2 * u));
        for (int[] k : new int[][] {{px, py, 1, 1}, {px + pw, py, -1, 1}, {px, py + ph, 1, -1}, {px + pw, py + ph, -1, -1}}) {
            int x0 = k[2] > 0 ? k[0] : k[0] - c, y0 = k[3] > 0 ? k[1] : k[1] - t;
            g.fill(x0, y0, x0 + c, y0 + t, G);
            int x1 = k[2] > 0 ? k[0] : k[0] - t, y1 = k[3] > 0 ? k[1] : k[1] - c;
            g.fill(x1, y1, x1 + t, y1 + c, G);
        }
        int pad = Math.round(14 * u);
        int fs = Math.max(11, Math.round(15 * u));
        int fBig = Math.max(24, Math.round(54 * u));
        int fMid = Math.max(14, Math.round(22 * u));
        // строка заголовка: название и режим
        int x = px + pad, y = py + Math.round(9 * u);
        font.draw(g, v.name.toUpperCase(), x, y, fs, a(LABEL, flick));
        String mode = v.auto ? "АВТО" : "ОДИН";
        int mw = font.width(mode, fs);
        int mx = px + pw - pad - mw;
        g.fill(mx - Math.round(6 * u), y - Math.round(2 * u), mx + mw + Math.round(6 * u), y + font.lineHeight(fs) + Math.round(1 * u),
                a(0xFF0E3A28, 0.9f));
        font.draw(g, mode, mx, y, fs, a(v.auto ? AMBER : G, flick));
        if (v.vacuum) {
            String vac = "ВАКУУМ";
            font.draw(g, vac, mx - Math.round(14 * u) - font.width(vac, fs), y, fs, a(0xFF50DCFF, flick * 0.9f));
        }
        // силуэт оружия
        int lineY = y + font.lineHeight(fs) + Math.round(5 * u);
        g.fill(px + pad, lineY, px + pw - pad, lineY + 1, a(G_DIM, 0.6f));
        int sh = Math.round(52 * u), sw = sh * 3;
        int sy = lineY + Math.round(8 * u);
        if (v.silhouette != null) {
            g.blit(RenderPipelines.GUI_TEXTURED, v.silhouette, px + pad, sy, 0f, 0f, sw, sh, sw, sh, a(G, 0.55f * flick));
        }
        // патроны
        float frac = v.magazine <= 0 ? 1 : v.ammo / (float) v.magazine;
        int ammoCol = v.ammo <= 0 ? RED : frac <= 0.25f ? AMBER : VALUE;
        String ammo = String.valueOf(Math.max(0, v.ammo));
        String res = "/" + (v.reserve < 0 ? "--" : String.valueOf(v.reserve));
        int rw = font.width(res, fMid);
        int aw = font.width(ammo, fBig);
        int right = px + pw - pad;
        int baseY = py + ph - pad;
        font.draw(g, res, right - rw, baseY - font.lineHeight(fMid), fMid, a(LABEL, flick));
        font.draw(g, ammo, right - rw - Math.round(4 * u) - aw, baseY - font.lineHeight(fBig) + Math.round(4 * u), fBig, a(ammoCol, flick));
        // столбики магазина (по одному на патрон, до 40)
        int n = Math.min(40, Math.max(1, v.magazine));
        int bx = px + pad, by = py + ph - pad - Math.round(6 * u);
        int bw = Math.max(2, Math.round(3 * u)), bg = Math.max(1, Math.round(2 * u));
        int filled = Math.round(Math.min(1, Math.max(0, frac)) * n);
        int maxW = right - rw - aw - Math.round(18 * u) - bx;
        int step = Math.max(2, Math.min(bw + bg, maxW / n));
        for (int i = 0; i < n; i++) {
            g.fill(bx + i * step, by, bx + i * step + Math.max(1, step - bg), by + Math.round(6 * u),
                    i < filled ? a(ammoCol, 0.85f) : a(G_DIM, 0.35f));
        }
        // перезарядка / подсказка
        String status = v.reload >= 0 ? "ПЕРЕЗАРЯДКА" : v.drawing ? "ДОСТАЮ" : v.hint;
        if (!status.isEmpty()) {
            int stY = by - font.lineHeight(fs) - Math.round(5 * u);
            int room = right - rw - aw - Math.round(14 * u) - bx;          // слева от крупных цифр
            int sfs = fs;
            while (sfs > 8 && font.width(status, sfs) > room) sfs--;
            font.draw(g, status, bx, stY + (fs - sfs), sfs, a(v.reload >= 0 ? AMBER : v.ammo <= 0 ? RED : LABEL, flick));
            if (v.reload >= 0) {
                int bxx = bx + font.width(status, sfs) + Math.round(8 * u);
                int bwid = Math.max(0, right - rw - aw - Math.round(14 * u) - bxx);
                int byy = stY + font.lineHeight(fs) / 2 - Math.round(2 * u);
                g.fill(bxx, byy, bxx + bwid, byy + Math.round(4 * u), a(G_DIM, 0.4f));
                g.fill(bxx, byy, bxx + Math.round(bwid * Math.min(1, v.reload)), byy + Math.round(4 * u), a(AMBER, 0.95f));
            }
        }
        // строки развёртки
        int scan = 0x1E000000;
        for (int yy = py + 2; yy < py + ph - 2; yy += 3) g.fill(px + 2, yy, px + pw - 2, yy + 1, scan);
    }
}
