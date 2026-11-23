package ink.hoka.odyssey.client.starmap;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.StringUtil;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Окно карты-радара. Своё окно (Screen), а не раскладка houi: живая развёртка, масштаб колёсиком, перетаскивание
 * и точки по щелчку требуют рисования каждый кадр по данным клиента — JSON-окно houi так не умеет.
 * <p>
 * Рисуется в <b>настоящих пикселях</b> экрана (поза GUI уменьшена на масштаб интерфейса), чтобы линии были
 * тонкими и ровными при любом «Масштабе интерфейса». Всё неподвижное — окно, кольца, сетка, риски, клин
 * развёртки, значки — растеризуется со сглаживанием один раз ({@link Raster}) в текстуры при смене размера окна;
 * за кадр — несколько текстур (клин — с поворотом), значки, текст своим шрифтом ({@link MonoFont}) и строки
 * развёртки. Слой блоков — {@link TerrainView}.
 * <p>
 * Север — вверх (−Z), восток — вправо (+X). Масштаб — радиус обзора в блоках ({@link #MIN_RANGE}..{@link #MAX_RANGE}).
 */
final class RadarScreen extends Screen {
    static final double MIN_RANGE = 32;
    static final double MAX_RANGE = 1024;

    // палитра (как у макета): люминофор на почти чёрном
    private static final int G = 0xFF5AFFAA;
    private static final int CYAN = 0xFF50DCFF;
    private static final int AMBER = 0xFFFFBE46;
    private static final int LAV = 0xFFC8C8FF;
    private static final int WP = 0xFFFF78C8;
    private static final int WP_TXT = 0xFFFF8CD2;
    private static final int LABEL = 0xFF72C49A;
    private static final int HINT = 0xFF7CB89A;
    private static final int RING_TXT = 0xFF5AC896;
    private static final int TIP_TXT = 0xFFDCDCC8;
    private static final int VALUE = 0xFF7CFFBE;
    private static final int RED = 0xFFFF6A55;
    private static final int SELF = 0xFFB4FFDC;
    private static final String[] BEARINGS = {"С", "В", "Ю", "З"};

    private final StarmapClientModule mod;
    private final Minecraft mc = Minecraft.getInstance();
    private long openedAt;

    // раскладка, настоящие пиксели
    private int gs = 1;
    private int fbW, fbH;
    private int px, py, pw, ph, headH;
    private float u;
    private int fs, fm, fh, fl;                // размеры шрифта: мелкий, строки, заголовки разделов, заголовок окна
    private int cx, cy, r, tick;
    private int colX, colW, valR, distR;

    // текстуры
    private final GlTex panelTex = new GlTex("panel");
    private final GlTex scopeTex = new GlTex("scope");
    private final GlTex sweepTex = new GlTex("sweep");
    private final GlTex spriteTex = new GlTex("sprites");
    private final TerrainView terrainView = new TerrainView();
    private boolean built;
    private int scopeHalf, sweepPivot;

    private record Spr(int u, int v, int size) {}

    private Spr sDiaFill, sDiaLine, sWpLine, sWpFill, sDot, sDotS, sArrowFill, sArrowLine, sRing;

    // вид
    private double viewX, viewZ;
    private boolean following = true;
    private boolean pressing, dragging;
    private double pressX, pressY;
    private Waypoints.Wp selected;
    private boolean editing;
    private String editBuf = "";
    private int fieldX, fieldY, fieldW, fieldH;
    private Vec3 lastPos;
    private double speed;

    private record Tip(String title, String kind, double dist, double dy, String owner, int col) {}

    private record Hit(int x, int y, int rad, Tip tip, Waypoints.Wp wp) {}

    private record Btn(int x, int y, int w, int h, Runnable action) {
        boolean in(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private record Target(String name, int col, double dist) {}

    private final List<Hit> hits = new ArrayList<>();
    private final List<Btn> buttons = new ArrayList<>();

    RadarScreen(StarmapClientModule mod) {
        super(Component.literal("Карта"));
        this.mod = mod;
    }

    // ---------------------------------------------------------------- раскладка

    @Override
    protected void init() {
        if (openedAt == 0) {
            openedAt = System.currentTimeMillis();
            mod.sendOpen(true);
            mod.syncWaypoints(mc);
        }
        layout();
    }

    private void layout() {
        Window win = mc.getWindow();
        gs = Math.max(1, win.getGuiScale());
        fbW = Math.max(320, win.getWidth());
        fbH = Math.max(240, win.getHeight());
        // окно — по центру, на 2/3 экрана
        pw = fbW * 2 / 3;
        ph = fbH * 2 / 3;
        px = (fbW - pw) / 2;
        py = (fbH - ph) / 2;
        u = Math.min(pw / 1300f, ph / 860f);
        fs = Math.max(12, Math.round(15 * u));
        fm = Math.max(13, Math.round(18 * u));
        fh = Math.max(15, Math.round(23 * u));
        fl = Math.max(16, Math.round(25 * u));
        headH = Math.max(Math.round(40 * u), mod.font.lineHeight(fl) + 10);
        tick = Math.max(6, Math.round(14 * u));
        int letter = mod.font.lineHeight(fl);
        int marginV = tick + Math.round(8 * u) + letter + Math.round(6 * u);
        int marginH = marginV + Math.round(30 * u);
        int colGap = marginV + Math.round(50 * u);
        int right = Math.round(30 * u);
        int minCol = Math.round(mod.font.advance(fs) * 34);
        r = (ph - headH - 2 * marginV) / 2;
        r = Math.min(r, (pw - marginH - colGap - right - minCol) / 2);
        r = Math.max(60, r);
        cx = px + marginH + r;
        cy = py + headH + (ph - headH) / 2;
        colX = cx + r + colGap;
        colW = Math.max(60, px + pw - right - colX);
        valR = colX + Math.min(colW, Math.max(Math.round(290 * u), Math.round(mod.font.advance(fm) * 20)));
        distR = colX + Math.min(colW, Math.max(Math.round(340 * u), Math.round(mod.font.advance(fs) * 30)));
        built = false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        if (mc.player == null || mc.level == null) {
            onClose();
            return;
        }
        Vec3 p = mc.player.position();
        if (lastPos != null) {
            double v = p.distanceTo(lastPos) * 20;                  // блоков в секунду (20 тиков)
            speed = v > 200 ? speed : speed * 0.6 + v * 0.4;        // телепорт — не скорость
        }
        lastPos = p;
    }

    @Override
    public void removed() {
        mod.sendOpen(false);
        mod.waypoints.save();
        panelTex.close();
        scopeTex.close();
        sweepTex.close();
        spriteTex.close();
        terrainView.close();
        super.removed();
    }

    private double scale() {
        return r / mod.range;
    }

    // ---------------------------------------------------------------- статичные текстуры

    private void buildStatic() {
        // окно: заливка, рамка, шапка
        panelTex.ensure(pw, ph);
        Raster pr = new Raster(pw, ph);
        float rad = Math.max(4, 10 * u), bw = Math.max(1.2f, 2 * u);
        pr.roundRect(0.5f, 0.5f, pw - 0.5f, ph - 0.5f, rad, 0x040C0A, 240 / 255f, 0, 0, 0);
        pr.rectClipped(0, 0, pw, headH, 0x0A1E18, 1f, 0.5f, 0.5f, pw - 0.5f, ph - 0.5f, rad);
        pr.roundRect(0.5f, 0.5f, pw - 0.5f, ph - 0.5f, rad, 0, 0, 0x28785A, bw, 1f);
        pr.writeTo(panelTex.img(), 0, 0);
        panelTex.upload();

        // экран радара: сетка, оси, кольца, риски
        scopeHalf = r + tick + 6;
        int sz = scopeHalf * 2;
        scopeTex.ensure(sz, sz);
        Raster sr = new Raster(sz, sz);
        float c = scopeHalf;
        float step = r / 8f;
        for (int k = -8; k <= 8; k++) {
            if (k == 0) continue;
            float o = c + k * step;
            sr.seg(o, c - r, o, c + r, 1f, 0x28785A, 50 / 255f, 0, 0);
            sr.seg(c - r, o, c + r, o, 1f, 0x28785A, 50 / 255f, 0, 0);
        }
        sr.seg(c - r, c, c + r, c, 1f, 0x46C88C, 80 / 255f, 0, 0);
        sr.seg(c, c - r, c, c + r, 1f, 0x46C88C, 80 / 255f, 0, 0);
        float glowR = Math.max(1.5f, 3 * u);
        for (int k = 1; k <= 4; k++) {
            boolean outer = k == 4;
            sr.ring(c, c, r * k / 4f, outer ? Math.max(1.4f, 2 * u) : 1f, 0x46C88C, outer ? 0.75f : 0.45f,
                    outer ? 0.16f : 0.08f, glowR);
        }
        float tw = Math.max(1.2f, 2 * u);
        for (int deg = 0; deg < 360; deg += 10) {
            double a = Math.toRadians(deg - 90);
            float l = deg % 30 == 0 ? tick : tick / 2f;
            float r0 = r + Math.max(1, u * 2);
            sr.seg(c + (float) Math.cos(a) * r0, c + (float) Math.sin(a) * r0,
                    c + (float) Math.cos(a) * (r0 + l), c + (float) Math.sin(a) * (r0 + l), tw, 0x5ADCA0, 0.9f, 0.10f, glowR);
        }
        sr.writeTo(scopeTex.img(), 0, 0);
        scopeTex.upload();

        // клин развёртки: передний край — по +x от центра, послесвечение позади (выше оси)
        int L = r + 4;
        sweepPivot = 2;
        sweepTex.ensure(L, L);
        Raster wr = new Raster(L, L);
        float pxv = sweepPivot, pyv = r + 2;
        double trail = Math.toRadians(84);
        for (int y = 0; y < L; y++) {
            for (int x = 0; x < L; x++) {
                float lx = x + 0.5f - pxv, ly = y + 0.5f - pyv;
                float rho = (float) Math.sqrt(lx * lx + ly * ly);
                float edge = Math.max(0, Math.min(1, r - rho + 0.5f));
                if (edge <= 0 || ly > 0.5f) continue;
                double phi = -Math.atan2(ly, lx);
                if (phi < 0 || phi > trail) continue;
                float k = (float) (1 - phi / trail);
                wr.over(x, y, 0x5AFFAA, 0.42f * k * k * edge);
            }
        }
        wr.seg(pxv, pyv, pxv + r - 1, pyv, Math.max(1.5f, 2.6f * u), 0xAAFFD2, 1f, 0.22f, Math.max(2f, 4 * u));
        wr.writeTo(sweepTex.img(), 0, 0);
        sweepTex.upload();

        buildSprites();
        built = true;
    }

    private void buildSprites() {
        float d = Math.max(5, 8 * u);
        float bw = Math.max(1.3f, 1.8f * u);
        float wh = Math.max(5, 7 * u), wv = Math.max(8, 12 * u);
        float dot = Math.max(3.5f, 5 * u), dotS = Math.max(1.6f, 2.2f * u);
        float ar = Math.max(0.7f, u);
        float ring = Math.max(10, 16 * u);
        int[] sizes = {
                cell(d), cell(d), cell(wv), cell(wv), cell(dot), cell(dotS), cell(15 * ar), cell(15 * ar), cell(ring + 2)};
        int W = 0, H = 0;
        for (int s : sizes) {
            W += s + 2;
            H = Math.max(H, s);
        }
        spriteTex.ensure(W, H + 2);
        Raster ra = new Raster(W, H + 2);
        int x = 1;
        Spr[] out = new Spr[sizes.length];
        for (int i = 0; i < sizes.length; i++) {
            int s = sizes[i];
            float c0x = x + s / 2f, c0y = 1 + s / 2f;
            switch (i) {
                case 0 -> ra.poly(new float[]{c0x, c0x + d, c0x, c0x - d}, new float[]{c0y - d, c0y, c0y + d, c0y},
                        0xFFFFFF, 1f, 0, 0, 0);
                case 1 -> ra.poly(new float[]{c0x, c0x + d, c0x, c0x - d}, new float[]{c0y - d, c0y, c0y + d, c0y},
                        0, 0, 0xFFFFFF, bw, 1f);
                case 2 -> ra.poly(new float[]{c0x, c0x + wh, c0x, c0x - wh}, new float[]{c0y - wv, c0y, c0y + wv, c0y},
                        0, 0, 0xFFFFFF, bw, 1f);
                case 3 -> ra.poly(new float[]{c0x, c0x + wh, c0x, c0x - wh}, new float[]{c0y - wv, c0y, c0y + wv, c0y},
                        0xFFFFFF, 1f, 0, 0, 0);
                case 4 -> ra.disc(c0x, c0y, dot, 0xFFFFFF, 1f);
                case 5 -> ra.disc(c0x, c0y, dotS, 0xFFFFFF, 1f);
                case 6, 7 -> {
                    // стрелка «ты»: нос вверх; вогнутая — из двух треугольников
                    float[] lx = {c0x, c0x, c0x - 9 * ar}, ly = {c0y - 14 * ar, c0y + 4 * ar, c0y + 10 * ar};
                    float[] rx = {c0x, c0x + 9 * ar, c0x}, ry = {c0y - 14 * ar, c0y + 10 * ar, c0y + 4 * ar};
                    if (i == 6) {
                        ra.poly(lx, ly, 0xFFFFFF, 1f, 0, 0, 0);
                        ra.poly(rx, ry, 0xFFFFFF, 1f, 0, 0, 0);
                    } else {
                        float lw = Math.max(1f, 1.2f * u);
                        ra.seg(c0x, c0y - 14 * ar, c0x + 9 * ar, c0y + 10 * ar, lw, 0xFFFFFF, 1f, 0, 0);
                        ra.seg(c0x + 9 * ar, c0y + 10 * ar, c0x, c0y + 4 * ar, lw, 0xFFFFFF, 1f, 0, 0);
                        ra.seg(c0x, c0y + 4 * ar, c0x - 9 * ar, c0y + 10 * ar, lw, 0xFFFFFF, 1f, 0, 0);
                        ra.seg(c0x - 9 * ar, c0y + 10 * ar, c0x, c0y - 14 * ar, lw, 0xFFFFFF, 1f, 0, 0);
                    }
                }
                default -> ra.ring(c0x, c0y, ring, Math.max(1.2f, 1.5f * u), 0xFFFFFF, 1f, 0, 0);
            }
            out[i] = new Spr(x, 1, s);
            x += s + 2;
        }
        ra.writeTo(spriteTex.img(), 0, 0);
        spriteTex.upload();
        sDiaFill = out[0];
        sDiaLine = out[1];
        sWpLine = out[2];
        sWpFill = out[3];
        sDot = out[4];
        sDotS = out[5];
        sArrowFill = out[6];
        sArrowLine = out[7];
        sRing = out[8];
    }

    /** Чётный размер клетки значка «радиуса» ext (с запасом на сглаживание). */
    private static int cell(float ext) {
        int s = (int) Math.ceil(ext * 2 + 6);
        return s + (s & 1);
    }

    /** Значок с центром в (x, y). */
    private void sprite(GuiGraphics g, Spr s, int x, int y, int argb) {
        g.blit(RenderPipelines.GUI_TEXTURED, spriteTex.id, x - s.size / 2, y - s.size / 2, (float) s.u, (float) s.v,
                s.size, s.size, spriteTex.w, spriteTex.h, argb);
    }

    /** Значок, растянутый до размера size (кольцо «это ты»). */
    private void spriteScaled(GuiGraphics g, Spr s, int x, int y, int size, int argb) {
        g.blit(RenderPipelines.GUI_TEXTURED, spriteTex.id, x - size / 2, y - size / 2, (float) s.u, (float) s.v,
                size, size, s.size, s.size, spriteTex.w, spriteTex.h, argb);
    }

    // ---------------------------------------------------------------- рисование

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partial) {
        float ap = Radar.easeOut((System.currentTimeMillis() - openedAt) / 250f);
        g.fill(0, 0, width, height, Radar.a(0x99000000, ap));     // затемнить мир, без размытия (как houi)
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        if (mc.player == null) return;
        Window win = mc.getWindow();
        if (win.getWidth() != fbW || win.getHeight() != fbH || win.getGuiScale() != gs) layout();
        if (!built) buildStatic();
        long now = System.currentTimeMillis();
        float t = (now - openedAt) / 1000f;
        float ap = Radar.easeOut(t / 0.3f);
        float pop = Radar.easeBack(t / 0.35f);
        float fl0 = mod.cfg.flicker ? 0.95f + 0.05f * (float) (Math.sin(now * 0.031) * Math.sin(now * 0.0173 + 1.3)) : 1f;
        if (mod.cfg.terrain) mod.terrain.step(mc, (long) (mod.cfg.scanBudgetMs * 1_000_000));

        Vec3 me = mc.player.getPosition(partial);
        float myYaw = mc.player.getViewYRot(partial);
        if (following) {
            viewX = me.x;
            viewZ = me.z;
        }
        double s = scale();
        int mx = mouseX * gs, my = mouseY * gs;
        hits.clear();
        buttons.clear();

        var pose = g.pose();
        pose.pushMatrix();
        pose.scale(1f / gs, 1f / gs);                            // дальше — настоящие пиксели
        float sc = 0.95f + 0.05f * pop;
        pose.translate(px + pw / 2f, py + ph / 2f);
        pose.scale(sc, sc);
        pose.translate(-(px + pw / 2f), -(py + ph / 2f));

        int white = Radar.a(0xFFFFFFFF, ap);
        g.blit(RenderPipelines.GUI_TEXTURED, panelTex.id, px, py, 0f, 0f, pw, ph, pw, ph, white);
        drawHeader(g, mx, my, ap);

        // экран радара
        if (mod.cfg.terrain) {
            terrainView.draw(g, mod.terrain, cx, cy, r, viewX, viewZ, s, mc.player.getBlockY(), Radar.a(0xFFFFFFFF, ap * fl0));
        }
        int sz = scopeHalf * 2;
        g.blit(RenderPipelines.GUI_TEXTURED, scopeTex.id, cx - scopeHalf, cy - scopeHalf, 0f, 0f, sz, sz, sz, sz,
                Radar.a(0xFFFFFFFF, ap * fl0));
        drawSweep(g, t, ap);
        drawScopeText(g, ap);
        drawWaypoints(g, s, me, ap, t);
        drawContacts(g, s, me, ap * fl0, t);
        drawSelf(g, s, me, myYaw, ap, t);
        drawScopeStatus(g, me, mx, my, ap, now);

        // правая колонка
        drawColumn(g, me, myYaw, mx, my, ap, now);

        // строки развёртки — поверх всего окна
        int scan = Radar.a(0x1E000000, ap);
        for (int y = py + headH; y < py + ph - 2; y += 3) g.fill(px + 2, y, px + pw - 2, y + 1, scan);

        // подсказка над контактом — поверх всего
        Hit h = dragging ? null : hitAt(mx, my);
        if (h != null) {
            g.nextStratum();
            drawTip(g, h.tip(), mx, my, ap);
        }
        pose.popMatrix();
        mod.font.flush();
    }

    private void drawHeader(GuiGraphics g, int mx, int my, float ap) {
        String map = mod.contacts.map.isEmpty() ? mc.level.dimension().identifier().getPath() : mod.contacts.map;
        int ty = py + (headH - mod.font.lineHeight(fl)) / 2;
        int x = px + Math.round(18 * u);
        x += mod.font.draw(g, "НАВИГАЦИОННЫЙ РАДАР", x, ty, fl, Radar.a(G, ap));
        String rest = "  ·  " + map;
        int maxW = px + pw - Math.round(300 * u) - x;
        while (mod.font.width(rest, fl) > maxW && rest.length() > 6) rest = rest.substring(0, rest.length() - 2) + "…";
        mod.font.draw(g, rest, x, ty, fl, Radar.a(G, ap * 0.92f));
        // справа — клавиши (по ним можно щёлкнуть)
        int hy = py + (headH - mod.font.lineHeight(fs)) / 2;
        String rc = "[" + keyName(mod.recenterKey) + "] центр";
        String cl = "[" + keyName(mod.openKey) + "] закрыть";
        int rx = px + pw - Math.round(24 * u) - mod.font.width(rc, fs);
        textButton(g, rc, rx, hy, fs, HINT, this::recenter, mx, my, ap);
        int gap = Math.round(mod.font.advance(fs) * 3);
        textButton(g, cl, rx - gap - mod.font.width(cl, fs), hy, fs, HINT, this::onClose, mx, my, ap);
    }

    private static String keyName(InputConstants.Key k) {
        String s = k == null ? "?" : k.getDisplayName().getString();
        return s.length() > 8 ? s.substring(0, 8) : s.toUpperCase(Locale.ROOT);
    }

    private void textButton(GuiGraphics g, String s, int x, int y, int size, int col, Runnable action, int mx, int my, float ap) {
        int w = mod.font.width(s, size), h = mod.font.lineHeight(size);
        Btn b = new Btn(x - 3, y - 2, w + 6, h + 4, action);
        boolean hover = b.in(mx, my);
        if (hover) g.fill(b.x(), b.y(), b.x() + b.w(), b.y() + b.h(), Radar.a(0x3328785A, ap));
        mod.font.draw(g, s, x, y, size, Radar.a(hover ? VALUE : col, ap));
        buttons.add(b);
    }

    private void drawSweep(GuiGraphics g, float t, float ap) {
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(cx, cy);
        pose.rotate((float) sweepAngle(t));
        int L = r + 4;
        g.blit(RenderPipelines.GUI_TEXTURED, sweepTex.id, -sweepPivot, -(r + 2), 0f, 0f, L, L, L, L, Radar.a(0xFFFFFFFF, ap));
        pose.popMatrix();
    }

    /** Подписи колец (у оси «север») и буквы сторон света. */
    private void drawScopeText(GuiGraphics g, float ap) {
        int lx = cx + Math.round(6 * u);
        for (int k = 1; k <= 4; k++) {
            int ry = cy - Math.round(r * k / 4f) + Math.round(4 * u);
            mod.font.draw(g, ringLabel(mod.range * k / 4), lx, ry, fs, Radar.a(RING_TXT, ap));
        }
        int lh = mod.font.lineHeight(fl);
        float rr = r + tick + Math.round(8 * u) + lh / 2f;
        for (int i = 0; i < 4; i++) {
            double a = Math.toRadians(i * 90 - 90);
            String l = BEARINGS[i];
            int x = Math.round(cx + (float) Math.cos(a) * rr) - mod.font.width(l, fl) / 2;
            int y = Math.round(cy + (float) Math.sin(a) * rr) - lh / 2;
            mod.font.draw(g, l, x, y, fl, Radar.a(G, ap));
        }
    }

    private static String ringLabel(double v) {
        if (v < 1000) return String.valueOf(Math.round(v));
        return String.format(Locale.ROOT, "%.1fk", v / 1000);
    }

    /** «Свечение» точки после прохода развёртки (0.72..1). */
    private float glow(int x, int y, float t) {
        double th = Math.atan2(y - cy, x - cx);
        double sweep = sweepAngle(t);
        double since = ((sweep - th) % (2 * Math.PI) + 2 * Math.PI) % (2 * Math.PI) / (2 * Math.PI);
        double k = 1 - since;
        return (float) (0.72 + 0.28 * k * k);
    }

    private double sweepAngle(float t) {
        return t / mod.cfg.sweepSeconds * 2 * Math.PI - Math.PI / 2;
    }

    /** Высота «ножки» в пикселях: значок поднят над своим местом, если цель выше тебя, опущен — если ниже. */
    private int stem(double dy) {
        if (Math.abs(dy) < 2) return 0;
        double lim = 40 * Math.max(0.6f, u);
        return (int) Math.round(Math.max(-lim, Math.min(lim, dy * 1.2 * Math.max(0.6f, u))));
    }

    private void drawStem(GuiGraphics g, int x, int y, int st, int col, float alpha) {
        sprite(g, sDotS, x, y, Radar.a(col, alpha));
        if (st == 0) return;
        int lw = Math.max(1, Math.round(u));
        if (st > 0) g.fill(x - lw / 2, y - st, x - lw / 2 + lw, y, Radar.a(col, alpha * 0.6f));
        else g.fill(x - lw / 2, y, x - lw / 2 + lw, y - st, Radar.a(col, alpha * 0.6f));
    }

    /** Подпись у значка: имя и под ним разница высот со знаком. */
    private void label(GuiGraphics g, int x, int y, String name, double dy, int col, float alpha) {
        int lx = x + Math.round(12 * u);
        int lh = mod.font.lineHeight(fs);
        mod.font.draw(g, name, lx, y - lh + Math.round(3 * u), fs, Radar.a(col, alpha));
        if (Math.abs(dy) >= 1.5) {
            mod.font.draw(g, Radar.dy(dy), lx, y + Math.round(3 * u), fs, Radar.a(col, alpha * 0.8f));
        }
    }

    private void drawWaypoints(GuiGraphics g, double s, Vec3 me, float ap, float t) {
        for (Waypoints.Wp w : mod.waypoints.all()) {
            double wx = w.x + 0.5, wz = w.z + 0.5;
            float fx = (float) (cx + (wx - viewX) * s), fy = (float) (cy + (wz - viewZ) * s);
            double ddx = fx - cx, ddy = fy - cy, d = Math.sqrt(ddx * ddx + ddy * ddy);
            double dist = Math.sqrt(sq(wx - me.x) + sq(w.y - me.y) + sq(wz - me.z));
            Tip tip = new Tip(w.name, w == selected ? "точка · выбрана" : "точка", dist, w.y - me.y, null, WP);
            if (d > r - 6) {                                     // за краем — ромбик на ободе
                int x = cx + (int) Math.round(ddx / d * (r - 8)), y = cy + (int) Math.round(ddy / d * (r - 8));
                sprite(g, sDiaFill, x, y, Radar.a(WP, ap * 0.55f));
                hits.add(new Hit(x, y, Math.round(8 * Math.max(0.7f, u)), tip, w));
                continue;
            }
            int x = Math.round(fx), y0 = Math.round(fy);
            int st = stem(w.y - me.y);
            drawStem(g, x, y0, st, WP, ap * 0.8f);
            int y = y0 - st;
            boolean sel = w == selected;
            sprite(g, sWpFill, x, y, Radar.a(sel ? 0xFFFFFFFF : WP, ap * (sel ? 0.35f : 0.18f)));
            sprite(g, sWpLine, x, y, Radar.a(sel ? 0xFFFFD6EE : WP, ap));
            label(g, x, y, w.name, w.y - me.y, WP_TXT, ap);
            hits.add(new Hit(x, y, Math.round(12 * Math.max(0.7f, u)), tip, w));
        }
    }

    private void drawContacts(GuiGraphics g, double s, Vec3 me, float alpha, float t) {
        Contacts c = mod.contacts;
        if (c.fresh()) {
            for (Contacts.Ship sh : c.ships) {
                int col = sh.mine() ? CYAN : AMBER;
                String nm = sh.name().isEmpty() ? sh.owner() : sh.name();
                String lbl = sh.mine() ? "Твой корабль «" + nm + "»" : "Корабль · " + nm;
                String kind = (sh.mine() ? "твой корабль" : "корабль") + (sh.flying() ? " · в полёте" : " · стоит");
                Tip tip = new Tip(nm, kind, dist(me, sh.x(), sh.y(), sh.z()), sh.y() - me.y,
                        sh.mine() ? null : sh.owner(), col);
                blip(g, s, me, sh.x(), sh.y(), sh.z(), sh.yaw(), col, true, lbl, tip, alpha, t);
            }
            for (Contacts.Player p : c.players) {
                Tip tip = new Tip(p.name(), p.pilot() ? "игрок · пилотирует" : "игрок", dist(me, p.x(), p.y(), p.z()),
                        p.y() - me.y, null, LAV);
                blip(g, s, me, p.x(), p.y(), p.z(), p.yaw(), LAV, false, "Игрок · " + p.name(), tip, alpha, t);
            }
        } else if (mod.cfg.clientPlayers && mc.level != null) {
            for (AbstractClientPlayer p : mc.level.players()) {
                if (p == mc.player) continue;
                String n = p.getName().getString();
                Tip tip = new Tip(n, "игрок (видит клиент)", dist(me, p.getX(), p.getY(), p.getZ()), p.getY() - me.y, null, LAV);
                blip(g, s, me, p.getX(), p.getY(), p.getZ(), p.getYRot(), LAV, false, "Игрок · " + n, tip, alpha, t);
            }
        }
    }

    private static double dist(Vec3 me, double x, double y, double z) {
        return Math.sqrt(sq(x - me.x) + sq(y - me.y) + sq(z - me.z));
    }

    private void blip(GuiGraphics g, double s, Vec3 me, double wx, double wy, double wz, float yaw, int col, boolean ship,
                      String lbl, Tip tip, float alpha, float t) {
        float fx = (float) (cx + (wx - viewX) * s), fy = (float) (cy + (wz - viewZ) * s);
        double ddx = fx - cx, ddy = fy - cy, d = Math.sqrt(ddx * ddx + ddy * ddy);
        if (d > r - 6) {
            if (!ship) return;                                   // корабль за краем — ромбик на ободе
            int x = cx + (int) Math.round(ddx / d * (r - 8)), y = cy + (int) Math.round(ddy / d * (r - 8));
            sprite(g, sDiaFill, x, y, Radar.a(col, alpha * 0.5f));
            hits.add(new Hit(x, y, Math.round(8 * Math.max(0.7f, u)), new Tip(tip.title(), tip.kind() + " · за краем",
                    tip.dist(), tip.dy(), tip.owner(), col), null));
            return;
        }
        int x = Math.round(fx), y0 = Math.round(fy);
        float gl = alpha * glow(x, y0, t);
        int st = stem(wy - me.y);
        drawStem(g, x, y0, st, col, gl);
        int y = y0 - st;
        if (ship) {
            // курс корабля — короткая черта из ромба
            float len = Math.max(8, 14 * u);
            Radar.line(g, x, y, (float) Math.toRadians(yaw + 90), len, Math.max(1, Math.round(u)), Radar.a(col, gl * 0.7f));
            sprite(g, sDiaFill, x, y, Radar.a(col, gl * 0.32f));
            sprite(g, sDiaLine, x, y, Radar.a(col, gl));
        } else {
            sprite(g, sDot, x, y, Radar.a(col, gl));
        }
        label(g, x, y, lbl, wy - me.y, col, gl);
        hits.add(new Hit(x, y, Math.round(10 * Math.max(0.7f, u)), tip, null));
    }

    private void drawSelf(GuiGraphics g, double s, Vec3 me, float yaw, float ap, float t) {
        int x = (int) Math.round(cx + (me.x - viewX) * s), y = (int) Math.round(cy + (me.z - viewZ) * s);
        int dx = x - cx, dy = y - cy;
        var pose = g.pose();
        if (dx * dx + dy * dy > (r - 8) * (r - 8)) {             // увёл вид далеко — стрелка «ты там» на ободе
            double d = Math.sqrt(dx * dx + dy * dy);
            int rx = cx + (int) Math.round(dx / d * (r - 12)), ry = cy + (int) Math.round(dy / d * (r - 12));
            pose.pushMatrix();
            pose.translate(rx, ry);
            pose.rotate((float) Math.atan2(dy, dx) + (float) Math.PI / 2);
            pose.scale(0.7f, 0.7f);
            sprite(g, sArrowFill, 0, 0, Radar.a(SELF, ap * 0.8f));
            pose.popMatrix();
            return;
        }
        float ping = (t % 2.2f) / 2.2f;                          // расходящееся кольцо «это ты»
        int ps = Math.round(sRing.size() * (0.4f + ping * 1.2f));
        spriteScaled(g, sRing, x, y, ps, Radar.a(SELF, ap * (1 - ping) * 0.45f));
        pose.pushMatrix();
        pose.translate(x, y);
        pose.rotate((float) Math.toRadians(yaw + 180));         // нос стрелки (−y) → курс взгляда
        sprite(g, sArrowFill, 0, 0, Radar.a(SELF, ap));
        sprite(g, sArrowLine, 0, 0, Radar.a(0xFFFFFFFF, ap));
        pose.popMatrix();
        hits.add(new Hit(x, y, Math.round(10 * Math.max(0.7f, u)), new Tip("Ты", "твоё место", 0, 0, null, SELF), null));
    }

    /** Под экраном радара: слева — курсор (место и высота блока), справа — скан и связь. */
    private void drawScopeStatus(GuiGraphics g, Vec3 me, int mx, int my, float ap, long now) {
        int lh = mod.font.lineHeight(fs) + Math.round(3 * u);
        int bottom = py + ph - Math.round(16 * u);
        int lx = px + Math.round(18 * u);
        int maxW = cx - mod.font.width("Ю", fl) - lx - Math.round(10 * u);
        if (inScope(mx, my)) {
            double s = scale();
            double wx = viewX + (mx - cx) / s, wz = viewZ + (my - cy) / s;
            int hgt = mod.terrain.heightAt((int) Math.floor(wx), (int) Math.floor(wz));
            String l1 = "курсор " + Math.round(wx) + ", " + Math.round(wz);
            String l2 = (hgt == Integer.MIN_VALUE ? "блок —" : "блок y " + hgt + " (" + Radar.dy(hgt - me.y) + ")")
                    + " · " + blocks(Math.sqrt(sq(wx - me.x) + sq(wz - me.z)));
            mod.font.draw(g, fit(l1, maxW, fs), lx, bottom - 2 * lh, fs, Radar.a(HINT, ap));
            mod.font.draw(g, fit(l2, maxW, fs), lx, bottom - lh, fs, Radar.a(HINT, ap));
        }
        boolean fresh = mod.contacts.fresh();
        String scan = !mod.cfg.terrain ? "скан выкл" : "скан " + Math.round(mod.terrain.progress() * 100) + "%";
        String link = fresh ? "связь есть" : "связи нет";
        int rx = cx + mod.font.width("Ю", fl) + Math.round(14 * u);
        int rw = colX - Math.round(20 * u) - rx;
        int y2 = bottom - lh;
        if (rw > mod.font.width("связи нет", fs)) {
            mod.font.draw(g, scan, rx + rw - mod.font.width(scan, fs), y2 - lh, fs, Radar.a(HINT, ap));
            boolean blinkOff = !fresh && (now / 500) % 2 == 0;
            mod.font.draw(g, link, rx + rw - mod.font.width(link, fs), y2, fs,
                    Radar.a(fresh ? HINT : (blinkOff ? LABEL : RED), ap));
        }
    }

    private String fit(String s, int w, int size) {
        if (mod.font.width(s, size) <= w) return s;
        while (s.length() > 2 && mod.font.width(s + "…", size) > w) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    // ---------------------------------------------------------------- правая колонка

    private void drawColumn(GuiGraphics g, Vec3 me, float yaw, int mx, int my, float ap, long now) {
        int rowH = Math.max(mod.font.lineHeight(fm) + 5, Math.round(30 * u));
        int headStep = mod.font.lineHeight(fh) + Math.round(10 * u);
        int smallH = Math.max(mod.font.lineHeight(fs) + 5, Math.round(24 * u));
        int gap = Math.max(5, Math.round(14 * u));
        int x = colX;
        int y = py + headH + Math.round(26 * u);

        // подсказки и условные знаки — прижаты к низу (место не зависит от содержимого — ничего не прыгает)
        List<String> hints = wrap(List.of("ЛКМ — точка", "ПКМ по точке — удалить", "Колесо — масштаб",
                "Перетаскивание — сдвиг", "Del — удалить выбранную"), colW, fs);
        if (hints.size() > 2) {
            hints = wrap(List.of("ЛКМ — точка", "ПКМ по точке — удалить", "Колесо — масштаб", "Перетаскивание — сдвиг"),
                    colW, fs);
        }
        int hintH = mod.font.lineHeight(fs) + Math.round(6 * u);
        int bottom = py + ph - Math.round(20 * u);
        int hintsTop = bottom - hints.size() * hintH;
        // высота блоков над целями — постоянная: тесно (малое окно) — без условных знаков, цвета видны в списке целей
        int fixedTop = y + 3 * headStep + 11 * rowH + Math.round(4 * u) + 3 * gap;
        boolean legend = (hintsTop - hintH - Math.round(6 * u) - Math.round(8 * u) - fixedTop - headStep) / smallH >= 3;
        int legendY = legend ? hintsTop - hintH - Math.round(6 * u) : hintsTop;

        y = heading(g, "ТЕЛЕМЕТРИЯ", x, y, ap);
        y = row(g, "X", String.format(Locale.ROOT, "%.1f", me.x), x, y, rowH, VALUE, ap);
        y = row(g, "Y", String.format(Locale.ROOT, "%.1f", me.y), x, y, rowH, VALUE, ap);
        y = row(g, "Z", String.format(Locale.ROOT, "%.1f", me.z), x, y, rowH, VALUE, ap);
        int hdg = (int) Math.round(((yaw + 180) % 360 + 360) % 360);  // курс: 0 — север, 90 — восток
        y = row(g, "Курс", String.format(Locale.ROOT, "%03d° %s", hdg % 360, side(hdg)), x, y, rowH, VALUE, ap);
        y = row(g, "Скорость", String.format(Locale.ROOT, "%.1f б/с", speed), x, y, rowH, VALUE, ap);
        y += gap;

        int hy = y;
        y = heading(g, "ДИАПАЗОН", x, y, ap);
        int bx = x + mod.font.width("ДИАПАЗОН", fh) + Math.round(mod.font.advance(fs) * 2);
        int by = hy + (mod.font.lineHeight(fh) - mod.font.lineHeight(fs)) / 2;
        textButton(g, "[−]", bx, by, fs, HINT, () -> zoom(-1, false, 0, 0), mx, my, ap);
        textButton(g, "[+]", bx + mod.font.width("[−] ", fs) + 4, by, fs, HINT, () -> zoom(1, false, 0, 0), mx, my, ap);
        Contacts c = mod.contacts;
        List<Target> targets = targets(me);
        y = row(g, "Масштаб", blocks(mod.range), x, y, rowH, VALUE, ap);
        y = row(g, "Целей", String.valueOf(targets.size()), x, y, rowH, VALUE, ap);
        y = row(g, "Точек", String.valueOf(mod.waypoints.all().size()), x, y, rowH, VALUE, ap);
        y = row(g, "Вид", following ? "за тобой" : "свободный · " + keyName(mod.recenterKey), x, y, rowH,
                following ? VALUE : AMBER, ap);
        y += gap;

        // выбранная точка — блок постоянной высоты
        if (selected != null && !mod.waypoints.all().contains(selected)) select(null);
        y = heading(g, "ТОЧКА", x, y, ap);
        int boxW = Math.min(colW, distR - colX);
        if (selected != null) {
            fieldX = x;
            fieldY = y - Math.round(3 * u);
            fieldW = boxW;
            fieldH = mod.font.lineHeight(fm) + Math.round(6 * u);
            g.fill(fieldX, fieldY, fieldX + fieldW, fieldY + fieldH, Radar.a(0xCC020806, ap));
            int bc = Radar.a(editing ? WP : 0xFF28785A, ap);
            g.fill(fieldX, fieldY, fieldX + fieldW, fieldY + 1, bc);
            g.fill(fieldX, fieldY + fieldH - 1, fieldX + fieldW, fieldY + fieldH, bc);
            g.fill(fieldX, fieldY, fieldX + 1, fieldY + fieldH, bc);
            g.fill(fieldX + fieldW - 1, fieldY, fieldX + fieldW, fieldY + fieldH, bc);
            String nm = editing ? editBuf : selected.name;
            int tx = fieldX + Math.round(6 * u), ty = fieldY + Math.round(3 * u);
            int tw = mod.font.draw(g, fit(nm, fieldW - Math.round(30 * u), fm), tx, ty, fm, Radar.a(WP_TXT, ap));
            if (editing && (now / 450) % 2 == 0) {
                g.fill(tx + tw + 1, ty + 1, tx + tw + Math.max(2, Math.round(2 * u)), ty + mod.font.lineHeight(fm) - 1,
                        Radar.a(WP_TXT, ap));
            }
            if (!editing && fieldW > mod.font.width(nm, fm) + mod.font.width("изменить", fs) + 20) {
                String e = "изменить";
                mod.font.draw(g, e, fieldX + fieldW - Math.round(6 * u) - mod.font.width(e, fs),
                        ty + (mod.font.lineHeight(fm) - mod.font.lineHeight(fs)) / 2, fs, Radar.a(LABEL, ap * 0.8f));
            }
            y += rowH + Math.round(4 * u);
            double d = Math.sqrt(sq(selected.x + 0.5 - me.x) + sq(selected.y - me.y) + sq(selected.z + 0.5 - me.z));
            String info = selected.x + " " + selected.y + " " + selected.z + " · " + blocks(d);
            String del = "[удалить]";
            int dw = mod.font.width(del, fs);
            mod.font.draw(g, fit(info, boxW - dw - 10, fs), x, y + (mod.font.lineHeight(fm) - mod.font.lineHeight(fs)) / 2,
                    fs, Radar.a(WP_TXT, ap * 0.9f));
            Waypoints.Wp sel = selected;
            textButton(g, del, x + boxW - dw, y + (mod.font.lineHeight(fm) - mod.font.lineHeight(fs)) / 2, fs, WP_TXT,
                    () -> deleteWaypoint(sel), mx, my, ap);
            y += rowH;
        } else {
            fieldW = 0;
            mod.font.draw(g, fit("щелчок по карте — поставить", colW, fs), x, y, fs, Radar.a(LABEL, ap));
            y += rowH + Math.round(4 * u);
            mod.font.draw(g, fit("щелчок по точке — выбрать", colW, fs), x, y, fs, Radar.a(LABEL, ap));
            y += rowH;
        }
        y += gap;

        // цели — сколько поместится
        int listTop = y + headStep;
        int fitRows = (legendY - Math.round(8 * u) - listTop) / smallH;
        if (fitRows >= 1) {
            y = heading(g, "ЦЕЛИ", x, y, ap);
            if (targets.isEmpty()) {
                mod.font.draw(g, c.fresh() ? "никого рядом" : "нет данных с сервера", x, y, fs, Radar.a(LABEL, ap));
            }
            int shown = targets.size() > fitRows ? fitRows - 1 : targets.size();
            for (int i = 0; i < shown; i++) {
                Target tg = targets.get(i);
                String dv = blocks(tg.dist());
                int dw = mod.font.width(dv, fs);
                mod.font.draw(g, fit(tg.name(), distR - x - dw - 12, fs), x, y, fs, Radar.a(tg.col(), ap));
                mod.font.draw(g, dv, distR - dw, y, fs, Radar.a(TIP_TXT, ap * 0.9f));
                y += smallH;
            }
            if (shown < targets.size()) {
                mod.font.draw(g, "… ещё " + (targets.size() - shown), x, y, fs, Radar.a(LABEL, ap));
            }
        }

        // условные знаки (одной строкой, переносятся по словам) и подсказки
        if (legend && legendY > y) drawLegend(g, x, legendY, hintH, ap);
        for (int i = 0; i < hints.size(); i++) {
            mod.font.draw(g, hints.get(i), x, hintsTop + i * hintH, fs, Radar.a(HINT, ap));
        }
    }

    private void drawLegend(GuiGraphics g, int x, int y, int lineH, float ap) {
        int adv = Math.round(mod.font.advance(fs));
        int cyL = y + mod.font.lineHeight(fs) / 2;
        int end = colX + colW;
        String[] names = {"твой", "корабль", "игрок", "точка"};
        int[] cols = {CYAN, AMBER, LAV, WP};
        int xx = x;
        for (int i = 0; i < names.length; i++) {
            int w = adv * 2 + mod.font.width(names[i], fs);
            if (xx + w > end) break;
            Spr sp = i < 2 ? sDiaLine : i == 2 ? sDot : sWpLine;
            var pose = g.pose();
            pose.pushMatrix();
            pose.translate(xx + adv / 2f, cyL);
            pose.scale(0.7f, 0.7f);
            sprite(g, sp, 0, 0, Radar.a(cols[i], ap));
            pose.popMatrix();
            mod.font.draw(g, names[i], xx + Math.round(adv * 1.4f), y, fs, Radar.a(LABEL, ap));
            xx += w + adv * 2;
        }
    }

    /** Слова-подсказки по строкам ширины w (между ними — три пробела). */
    private List<String> wrap(List<String> parts, int w, int size) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String p : parts) {
            String cand = line.isEmpty() ? p : line + "   " + p;
            if (mod.font.width(cand, size) <= w || line.isEmpty()) {
                line.setLength(0);
                line.append(cand);
            } else {
                out.add(line.toString());
                line.setLength(0);
                line.append(p);
            }
        }
        if (!line.isEmpty()) out.add(line.toString());
        return out;
    }

    private List<Target> targets(Vec3 me) {
        List<Target> l = new ArrayList<>();
        Contacts c = mod.contacts;
        if (c.fresh()) {
            for (Contacts.Ship sh : c.ships) {
                String nm = sh.name().isEmpty() ? sh.owner() : sh.name();
                l.add(new Target(sh.mine() ? nm + " (твой)" : nm + " · корабль", sh.mine() ? CYAN : AMBER,
                        dist(me, sh.x(), sh.y(), sh.z())));
            }
            for (Contacts.Player p : c.players) {
                l.add(new Target(p.name() + " · игрок", LAV, dist(me, p.x(), p.y(), p.z())));
            }
        } else if (mod.cfg.clientPlayers && mc.level != null) {
            for (AbstractClientPlayer p : mc.level.players()) {
                if (p == mc.player) continue;
                l.add(new Target(p.getName().getString() + " · игрок", LAV, dist(me, p.getX(), p.getY(), p.getZ())));
            }
        }
        l.sort(Comparator.comparingDouble(Target::dist));
        return l;
    }

    private static String side(int hdg) {
        String[] n = {"С", "СВ", "В", "ЮВ", "Ю", "ЮЗ", "З", "СЗ"};
        return n[Math.floorMod(Math.round(hdg / 45f), 8)];
    }

    private int heading(GuiGraphics g, String s, int x, int y, float ap) {
        mod.font.draw(g, s, x, y, fh, Radar.a(G, ap));
        return y + mod.font.lineHeight(fh) + Math.round(10 * u);
    }

    private int row(GuiGraphics g, String label, String value, int x, int y, int rowH, int col, float ap) {
        mod.font.draw(g, label, x, y, fm, Radar.a(LABEL, ap));
        String v = fit(value, valR - x - mod.font.width(label, fm) - 10, fm);
        mod.font.draw(g, v, valR - mod.font.width(v, fm), y, fm, Radar.a(col, ap));
        return y + rowH;
    }

    private static String blocks(double d) {
        return Math.round(d) + " б";
    }

    // ---------------------------------------------------------------- подсказка

    private void drawTip(GuiGraphics g, Tip tip, int mx, int my, float ap) {
        List<String> lines = new ArrayList<>();
        lines.add((tip.title().isEmpty() ? "—" : tip.title()) + " · " + tip.kind());
        boolean self = tip.col() == SELF;
        if (!self) {
            lines.add("дистанция " + blocks(tip.dist()));
            lines.add("высота " + Radar.dy(tip.dy()) + " б");
            if (tip.owner() != null && !tip.owner().isEmpty()) lines.add("владелец " + tip.owner());
        }
        int pad = Math.max(6, Math.round(8 * u));
        int lh = mod.font.lineHeight(fs) + Math.round(4 * u);
        int w = 0;
        for (String l : lines) w = Math.max(w, mod.font.width(l, fs));
        int bw = w + 2 * pad, bh = lines.size() * lh + 2 * pad - Math.round(4 * u);
        int x = mx + Math.round(16 * u), y = my + Math.round(16 * u);
        if (x + bw > fbW - 4) x = mx - bw - Math.round(10 * u);
        if (y + bh > fbH - 4) y = my - bh - Math.round(10 * u);
        int border = Radar.a(tip.col(), ap);
        g.fill(x, y, x + bw, y + bh, Radar.a(0xEB001410, ap));
        g.fill(x, y, x + bw, y + 1, border);
        g.fill(x, y + bh - 1, x + bw, y + bh, border);
        g.fill(x, y, x + 1, y + bh, border);
        g.fill(x + bw - 1, y, x + bw, y + bh, border);
        for (int i = 0; i < lines.size(); i++) {
            mod.font.draw(g, lines.get(i), x + pad, y + pad + i * lh, fs, Radar.a(i == 0 ? tip.col() : TIP_TXT, ap));
        }
    }

    private static double sq(double v) {
        return v * v;
    }

    // ---------------------------------------------------------------- ввод

    private boolean inScope(double mx, double my) {
        double dx = mx - cx, dy = my - cy;
        return dx * dx + dy * dy <= (double) r * r;
    }

    private Hit hitAt(double mx, double my) {
        Hit best = null;
        double bd = Double.MAX_VALUE;
        for (Hit h : hits) {
            double d = sq(h.x() - mx) + sq(h.y() - my);
            if (d <= h.rad() * h.rad() && d < bd) {
                bd = d;
                best = h;
            }
        }
        return best;
    }

    private Waypoints.Wp waypointAt(double mx, double my) {
        Waypoints.Wp best = null;
        double bd = Double.MAX_VALUE;
        for (Hit h : hits) {
            if (h.wp() == null) continue;
            double d = sq(h.x() - mx) + sq(h.y() - my);
            if (d <= sq(h.rad() + 2) && d < bd) {
                bd = d;
                best = h.wp();
            }
        }
        return best;
    }

    private void recenter() {
        following = true;
    }

    /** Масштаб: dir > 0 — ближе. around — вокруг точки экрана (mx, my), иначе вокруг центра. */
    private void zoom(double dir, boolean around, double mx, double my) {
        double before = scale();
        double wx = viewX + (mx - cx) / before, wz = viewZ + (my - cy) / before;
        mod.range = Math.max(MIN_RANGE, Math.min(MAX_RANGE, mod.range * Math.pow(0.85, dir)));
        if (around && !following) {
            double after = scale();
            viewX = wx - (mx - cx) / after;
            viewZ = wz - (my - cy) / after;
        }
    }

    private void deleteWaypoint(Waypoints.Wp w) {
        if (w == null) return;
        mod.waypoints.remove(w);
        if (selected == w) select(null);
    }

    private void select(Waypoints.Wp w) {
        selected = w;
        editing = false;
    }

    /** Имя в поле поменялось: пустое не сохраняется (остаётся прежнее, пока не допечатают). */
    private void setName(String v) {
        if (v.length() > Waypoints.MAX_NAME) v = v.substring(0, Waypoints.MAX_NAME);
        editBuf = v;
        if (selected != null && !v.isBlank()) mod.waypoints.rename(selected, v);
    }

    private static void click() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent ev, boolean doubleClick) {
        double mx = ev.x() * gs, my = ev.y() * gs;
        if (selected != null && fieldW > 0 && mx >= fieldX && mx < fieldX + fieldW && my >= fieldY && my < fieldY + fieldH) {
            if (!editing) editBuf = selected.name;               // поле имени точки
            editing = true;
            return true;
        }
        editing = false;
        for (Btn b : buttons) {
            if (b.in(mx, my)) {
                click();
                b.action().run();
                return true;
            }
        }
        if (inScope(mx, my)) {
            if (ev.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                pressing = true;
                dragging = false;
                pressX = mx;
                pressY = my;
                return true;
            }
            if (ev.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                Waypoints.Wp w = waypointAt(mx, my);
                if (w != null) {
                    click();
                    deleteWaypoint(w);
                }
                return true;
            }
        }
        return super.mouseClicked(ev, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent ev, double dx, double dy) {
        if (pressing && ev.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            double mx = ev.x() * gs, my = ev.y() * gs;
            if (!dragging && sq(mx - pressX) + sq(my - pressY) > sq(3 * gs)) {
                dragging = true;
                following = false;
            }
            if (dragging) {
                double s = scale();
                viewX -= dx * gs / s;
                viewZ -= dy * gs / s;
            }
            return true;
        }
        return super.mouseDragged(ev, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent ev) {
        if (pressing && ev.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            pressing = false;
            double mx = ev.x() * gs, my = ev.y() * gs;
            if (!dragging && inScope(mx, my)) {
                Waypoints.Wp hit = waypointAt(mx, my);
                if (hit != null) {
                    select(hit == selected ? null : hit);
                } else {
                    double s = scale();
                    int wx = (int) Math.floor(viewX + (mx - cx) / s), wz = (int) Math.floor(viewZ + (my - cy) / s);
                    int h = mod.terrain.heightAt(wx, wz);
                    int wy = h != Integer.MIN_VALUE ? h + 1 : (mc.player != null ? mc.player.getBlockY() : 64);
                    Waypoints.Wp w = mod.waypoints.add(wx, wy, wz);
                    if (w != null) select(w);
                }
                click();
            }
            dragging = false;
            return true;
        }
        return super.mouseReleased(ev);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (sy != 0) {
            double pmx = mx * gs, pmy = my * gs;
            zoom(sy, inScope(pmx, pmy), pmx, pmy);
            return true;
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    private static boolean matches(InputConstants.Key k, KeyEvent e) {
        return k != null && k.getType() == InputConstants.Type.KEYSYM && k.getValue() == e.key();
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        int key = e.key();
        if (editing && selected != null) {                       // печатаем имя — клавиши карты не работают
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER || key == GLFW.GLFW_KEY_ESCAPE) {
                editing = false;
                return true;
            }
            if (key == GLFW.GLFW_KEY_BACKSPACE) {
                String n = editBuf;
                if (e.hasControlDown()) {
                    int cut = n.stripTrailing().lastIndexOf(' ');
                    n = cut <= 0 ? "" : n.substring(0, cut + 1);
                } else if (!n.isEmpty()) {
                    n = n.substring(0, n.offsetByCodePoints(n.length(), -1));
                }
                setName(n);
                return true;
            }
            if (e.hasControlDown() && key == GLFW.GLFW_KEY_V) {
                setName(editBuf + StringUtil.filterText(mc.keyboardHandler.getClipboard()));
                return true;
            }
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE || matches(mod.openKey, e)) {
            onClose();
            return true;
        }
        if (matches(mod.recenterKey, e)) {
            recenter();
            return true;
        }
        if (key == GLFW.GLFW_KEY_EQUAL || key == GLFW.GLFW_KEY_KP_ADD) {
            zoom(1, false, 0, 0);
            return true;
        }
        if (key == GLFW.GLFW_KEY_MINUS || key == GLFW.GLFW_KEY_KP_SUBTRACT) {
            zoom(-1, false, 0, 0);
            return true;
        }
        if ((key == GLFW.GLFW_KEY_DELETE || key == GLFW.GLFW_KEY_BACKSPACE) && selected != null) {
            deleteWaypoint(selected);
            return true;
        }
        return super.keyPressed(e);
    }

    @Override
    public boolean charTyped(CharacterEvent e) {
        if (editing && selected != null && e.isAllowedChatCharacter()) setName(editBuf + e.codepointAsString());
        return true;                                             // буква «м» и т.п. не уходят никуда
    }
}
