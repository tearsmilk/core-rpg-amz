package ink.hoka.odyssey.client.starmap;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Окно карты-радара. Своё окно (Screen), а не раскладка houi: живая развёртка, масштаб колёсиком, перетаскивание
 * и точки по щелчку требуют рисования каждый кадр по данным клиента — JSON-окно houi так не умеет. Внешний вид —
 * как у окон houi (затемнение без размытия, рамки box с пиксельными углами, пиксельный шрифт игры, появление
 * с «back»-упругостью), только в палитре радара.
 * <p>
 * Север — вверх (−Z), восток — вправо (+X). Масштаб — радиус обзора в блоках ({@link #MIN_RANGE}..{@link #MAX_RANGE}).
 */
final class RadarScreen extends Screen {
    static final double MIN_RANGE = 32;
    static final double MAX_RANGE = 1024;
    private static final int HEADER = 16;
    private static final int FOOTER = 13;
    private static final int SIDE = 124;
    private static final int PAD = 5;
    private static final int BEZEL = 13;
    private static final int WAYPOINT = 0xFFFF8FD0;
    private static final Style MONO = Style.EMPTY.withFont(new FontDescription.Resource(Minecraft.UNIFORM_FONT));
    private static final String[] BEARINGS = {"С", "В", "Ю", "З"};

    private final StarmapClientModule mod;
    private final Minecraft mc = Minecraft.getInstance();
    private long openedAt;

    // раскладка (init)
    private int px, py, pw, ph;            // окно
    private int sqX, sqY, sqD;             // квадрат экрана радара
    private int cx, cy, r;                 // центр и радиус круга
    private int sideX, sideY, sideW, sideH;

    // вид
    private double viewX, viewZ;
    private boolean following = true;
    private boolean pressing, dragging;
    private double pressX, pressY;
    private Waypoints.Wp selected;
    private EditBox nameBox;

    private record Hit(int x, int y, int rad, List<Component> tip, Waypoints.Wp wp) {}

    private record Btn(int x, int y, int w, int h, String label, Runnable action) {
        boolean in(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

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
        int avH = height - 2 * PAD - HEADER - FOOTER - 2 * PAD;
        int avW = width - 3 * PAD - SIDE - 2 * PAD;
        sqD = Math.max(90, Math.min(avH, avW));
        sqD -= sqD % 2;
        r = sqD / 2 - BEZEL;
        pw = sqD + SIDE + 3 * PAD;
        ph = sqD + HEADER + FOOTER + 2 * PAD;
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        sqX = px + PAD;
        sqY = py + HEADER + PAD;
        cx = sqX + sqD / 2;
        cy = sqY + sqD / 2;
        sideX = sqX + sqD + PAD;
        sideY = sqY;
        sideW = SIDE;
        sideH = sqD;

        nameBox = new EditBox(font, sideX + 4, sideY, sideW - 8, 12, Component.literal("Имя точки"));
        nameBox.setMaxLength(Waypoints.MAX_NAME);
        nameBox.setBordered(false);
        nameBox.setTextColor(Radar.PHOS);
        nameBox.setVisible(false);
        if (selected != null) nameBox.setValue(selected.name);
        nameBox.setResponder(s -> {
            if (selected != null && !s.isBlank()) mod.waypoints.rename(selected, s);
        });
        addRenderableWidget(nameBox);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        if (mc.player == null || mc.level == null) onClose();
    }

    @Override
    public void removed() {
        mod.sendOpen(false);
        mod.waypoints.save();
        super.removed();
    }

    private double scale() {
        return r / mod.range;
    }

    // ---------------------------------------------------------------- рисование

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partial) {
        float ap = Radar.easeOut((System.currentTimeMillis() - openedAt) / 250f);
        g.fill(0, 0, width, height, Radar.a(0x88000000, ap));     // затемнить мир, без размытия (как houi)
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        long now = System.currentTimeMillis();
        float t = (now - openedAt) / 1000f;
        float appear = Radar.easeOut(t / 0.3f);
        float pop = Radar.easeBack(t / 0.35f);
        float fl = mod.cfg.flicker ? 0.94f + 0.06f * (float) (Math.sin(now * 0.031) * Math.sin(now * 0.0173 + 1.3)) : 1f;
        if (mc.player == null) return;
        if (mod.cfg.terrain) mod.terrain.step(mc, (long) (mod.cfg.scanBudgetMs * 1_000_000));

        Vec3 me = mc.player.getPosition(partial);
        float myYaw = mc.player.getViewYRot(partial);
        if (following) {
            viewX = me.x;
            viewZ = me.z;
        }
        double s = scale();
        hits.clear();
        buttons.clear();

        var pose = g.pose();
        pose.pushMatrix();
        float sc = 0.94f + 0.06f * pop;
        pose.translate(px + pw / 2f, py + ph / 2f);
        pose.scale(sc, sc);
        pose.translate(-(px + pw / 2f), -(py + ph / 2f));

        // окно, шапка, подвал
        Radar.box(g, px, py, pw, ph, Radar.a(Radar.PANEL, appear), Radar.a(Radar.BORDER, appear), 3);
        g.fill(px + 1, py + HEADER, px + pw - 1, py + HEADER + 1, Radar.a(Radar.BORDER, appear));
        g.fill(px + 1, py + ph - FOOTER - 1, px + pw - 1, py + ph - FOOTER, Radar.a(Radar.BORDER, appear * 0.7f));
        boolean blink = (now / 500) % 2 == 0;
        g.fill(px + 6, py + 6, px + 10, py + 10, Radar.a(blink ? Radar.PHOS : Radar.PHOS_DIM, appear));
        g.drawString(font, "НАВИГАЦИОННЫЙ РАДАР", px + 14, py + 4, Radar.a(Radar.PHOS, appear * fl), false);
        String map = mod.contacts.map.isEmpty() ? mc.level.dimension().identifier().getPath() : mod.contacts.map;
        Component mapC = mono("СЕКТОР " + map.toUpperCase(Locale.ROOT));
        g.drawString(font, mapC, px + pw - 8 - font.width(mapC), py + 4, Radar.a(Radar.PHOS_DIM, appear), false);
        String hint = "ЛКМ точка · ПКМ удалить · колесо масштаб · тяни сдвиг · R к себе · M закрыть";
        if (font.width(hint) > pw - 12) hint = "ЛКМ точка · ПКМ удалить · R к себе · M закрыть";
        g.drawString(font, hint, px + 6, py + ph - FOOTER + 2, Radar.a(Radar.PHOS_DIM, appear), false);

        // экран радара
        Radar.box(g, sqX, sqY, sqD, sqD, Radar.a(Radar.PANEL2, appear), Radar.a(Radar.BORDER, appear), 2);
        Radar.disc(g, cx, cy, r, Radar.a(Radar.SCOPE, appear));
        g.nextStratum();
        if (mod.cfg.terrain && mod.terrain.ready()) drawTerrain(g, s, appear * fl);
        g.nextStratum();
        Radar.mask(g, cx, cy, r, Radar.a(Radar.PANEL2, appear));
        drawGrid(g, s, appear);
        drawRings(g, appear);
        drawChunkBlips(g, s, me, appear * fl, t);
        drawWaypoints(g, s, me, appear, mouseX, mouseY);
        drawContacts(g, s, me, appear * fl, t);
        drawSelf(g, s, me, myYaw, appear, t);
        drawSweep(g, t, appear);
        drawScanlines(g, t, appear);
        if (t < 0.55f) {                                         // «включение»: полоса развёртки сверху вниз
            int bar = sqY + 2 + Math.round((sqD - 4) * Radar.easeOut(t / 0.55f));
            g.fill(sqX + 2, bar, sqX + sqD - 2, sqY + sqD - 2, Radar.PANEL2);
            g.fill(sqX + 2, bar - 1, sqX + sqD - 2, bar + 1, Radar.a(Radar.PHOS, 0.9f));
        }
        Radar.brackets(g, sqX + 3, sqY + 3, sqD - 6, sqD - 6, 7, Radar.a(Radar.BORDER_HI, appear * 0.8f));

        // боковая панель
        drawSide(g, me, myYaw, mouseX, mouseY, appear, fl, now);
        pose.popMatrix();

        nameBox.setVisible(selected != null && t > 0.35f && nameBox.getY() >= sideY && nameBox.getY() + 12 < sideY + sideH);
        super.render(g, mouseX, mouseY, partial);                // поле имени точки

        // подсказка над контактом
        Hit h = hitAt(mouseX, mouseY);
        if (h != null && !dragging) g.setTooltipForNextFrame(font, h.tip(), Optional.empty(), mouseX, mouseY);
    }

    private void drawTerrain(GuiGraphics g, double s, float alpha) {
        Terrain tr = mod.terrain;
        double ox = tr.originCX * 16.0, oz = tr.originCZ * 16.0;
        float sx0 = (float) (cx + (ox - viewX) * s), sy0 = (float) (cy + (oz - viewZ) * s);
        g.enableScissor(cx - r, cy - r, cx + r, cy + r);
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(sx0, sy0);
        pose.scale((float) s, (float) s);
        g.blit(RenderPipelines.GUI_TEXTURED, tr.id, 0, 0, 0f, 0f, Terrain.TEX, Terrain.TEX, Terrain.TEX, Terrain.TEX,
                Radar.a(0xFFFFFFFF, alpha * 0.92f));
        pose.popMatrix();
        g.disableScissor();
    }

    /** Шаг сетки: «круглое» число блоков, ~3–4 линии на радиус. */
    private double gridStep() {
        double want = mod.range / 3.5;
        double step = 8;
        while (step < want) step *= 2;
        return step;
    }

    private void drawGrid(GuiGraphics g, double s, float appear) {
        double step = gridStep();
        int col = Radar.a(0x2A3FD48A, appear);
        double x0 = Math.floor((viewX - mod.range) / step) * step;
        for (double x = x0; x <= viewX + mod.range; x += step) {
            int sx = (int) Math.round(cx + (x - viewX) * s);
            int dx = sx - cx;
            if (Math.abs(dx) >= r) continue;
            int half = (int) Math.sqrt((double) r * r - (double) dx * dx);
            g.fill(sx, cy - half, sx + 1, cy + half, col);
        }
        double z0 = Math.floor((viewZ - mod.range) / step) * step;
        for (double z = z0; z <= viewZ + mod.range; z += step) {
            int sy = (int) Math.round(cy + (z - viewZ) * s);
            int dy = sy - cy;
            if (Math.abs(dy) >= r) continue;
            int half = (int) Math.sqrt((double) r * r - (double) dy * dy);
            g.fill(cx - half, sy, cx + half, sy + 1, col);
        }
    }

    private void drawRings(GuiGraphics g, float appear) {
        for (int k = 1; k <= 4; k++) {
            float rr = r * k / 4f;
            Radar.ring(g, cx, cy, rr, Radar.a(k == 4 ? Radar.PHOS_MID : 0x8837E08A, appear), k == 4 ? 0 : 2);
            if (k < 4) {
                Component lbl = mono(Radar.dist(mod.range * k / 4));
                int lx = cx + Math.round(rr * 0.7071f) + 2, ly = cy - Math.round(rr * 0.7071f) - 8;
                g.drawString(font, lbl, lx, ly, Radar.a(Radar.PHOS_DIM, appear), false);
            }
        }
        Radar.ring(g, cx, cy, r + 1, Radar.a(Radar.BORDER, appear), 0);
        // риски курса: каждые 10°, длинные — каждые 30°, буквы сторон света
        for (int b = 0; b < 360; b += 10) {
            float th = (float) Math.toRadians(b - 90);
            int len = b % 90 == 0 ? 7 : b % 30 == 0 ? 5 : 2;
            float x = cx + (float) Math.cos(th) * (r - len), y = cy + (float) Math.sin(th) * (r - len);
            Radar.line(g, x, y, th, len, 1, Radar.a(b % 30 == 0 ? Radar.PHOS_MID : Radar.PHOS_DIM, appear));
            if (b % 90 == 0) {
                String l = BEARINGS[b / 90];
                int tx = cx + Math.round((float) Math.cos(th) * (r + 7)) - font.width(l) / 2;
                int ty = cy + Math.round((float) Math.sin(th) * (r + 7)) - 4;
                g.drawString(font, l, tx, ty, Radar.a(b == 0 ? Radar.PHOS : Radar.PHOS_MID, appear), false);
            }
        }
        // крестик в центре вида
        g.fill(cx - 3, cy, cx + 4, cy + 1, Radar.a(0x553FD48A, appear));
        g.fill(cx, cy - 3, cx + 1, cy + 4, Radar.a(0x553FD48A, appear));
    }

    /** Угол точки от центра (радианы) и «свечение» после прохода развёртки (0.5..1). */
    private float glow(int x, int y, float t) {
        double th = Math.atan2(y - cy, x - cx);
        double sweep = sweepAngle(t);
        double since = ((sweep - th) % (2 * Math.PI) + 2 * Math.PI) % (2 * Math.PI) / (2 * Math.PI);
        double k = 1 - since;
        return (float) (0.5 + 0.5 * k * k);
    }

    private double sweepAngle(float t) {
        return t / mod.cfg.sweepSeconds * 2 * Math.PI - Math.PI / 2;
    }

    /** Засветки астероидов/станций: при большом обзоре блоки мельче пикселя — рисуем чанки с блоками точками. */
    private void drawChunkBlips(GuiGraphics g, double s, Vec3 me, float alpha, float t) {
        if (!mod.cfg.terrain || !mod.terrain.ready() || s >= 1.0) return;
        Terrain tr = mod.terrain;
        for (int i = 0; i < tr.chunkCount.length; i++) {
            int n = tr.chunkCount[i];
            if (n <= 0) continue;
            double wx = (tr.originCX + i % Terrain.CH) * 16 + 8, wz = (tr.originCZ + i / Terrain.CH) * 16 + 8;
            int x = (int) Math.round(cx + (wx - viewX) * s), y = (int) Math.round(cy + (wz - viewZ) * s);
            int dx = x - cx, dy = y - cy;
            if (dx * dx + dy * dy > (r - 2) * (r - 2)) continue;
            float k = 0.35f + 0.65f * Math.min(1f, n / 96f);
            int col = tr.chunkTop[i] > me.y + 8 ? Radar.CYAN : Radar.PHOS_MID;
            int a = Radar.a(col, alpha * k * glow(x, y, t));
            g.fill(x - 1, y - 1, x + 1, y + 1, a);
        }
    }

    /** Ножка высоты: значок поднят/опущен на столько пикселей (корень из разницы высот, не больше 12). */
    private static int stem(double dy) {
        int v = (int) Math.round(Math.signum(dy) * Math.min(12, Math.sqrt(Math.abs(dy)) * 1.3));
        return Math.abs(dy) < 2 ? 0 : v;
    }

    private void drawStem(GuiGraphics g, int x, int y, int st, int col) {
        if (st == 0) return;
        g.fill(x - 1, y, x + 2, y + 1, col);
        if (st > 0) g.fill(x, y - st, x + 1, y, col);
        else g.fill(x, y + 1, x + 1, y - st + 1, col);
    }

    private void drawWaypoints(GuiGraphics g, double s, Vec3 me, float appear, int mx, int my) {
        for (Waypoints.Wp w : mod.waypoints.all()) {
            double wx = w.x + 0.5, wz = w.z + 0.5;
            float fx = (float) (cx + (wx - viewX) * s), fy = (float) (cy + (wz - viewZ) * s);
            double ddx = fx - cx, ddy = fy - cy, d = Math.sqrt(ddx * ddx + ddy * ddy);
            double dist = Math.sqrt(sq(wx - me.x) + sq(w.y - me.y) + sq(wz - me.z));
            int col = Radar.a(WAYPOINT, appear);
            if (d > r - 4) {                                     // за краем — ромбик на ободе
                int x = cx + (int) Math.round(ddx / d * (r - 4)), y = cy + (int) Math.round(ddy / d * (r - 4));
                Radar.diamond(g, x, y, 2, Radar.a(WAYPOINT, appear * 0.6f));
                hits.add(new Hit(x, y, 4, tip(w.name, "точка за краем", dist, w.y - me.y, null), w));
                continue;
            }
            int x = Math.round(fx), y0 = Math.round(fy);
            int st = stem(w.y - me.y);
            drawStem(g, x, y0, st, Radar.a(WAYPOINT, appear * 0.7f));
            int y = y0 - st;
            Radar.diamond(g, x, y, 3, col);
            if (w == selected) {
                Radar.diamondOutline(g, x, y, 6, Radar.a(Radar.WHITE, appear));
            } else {
                Radar.diamondOutline(g, x, y, 4, Radar.a(0xFF2A0A20, appear));
            }
            g.drawString(font, w.name, x + 6, y - 4, Radar.a(WAYPOINT, appear * 0.9f), false);
            hits.add(new Hit(x, y, 5, tip(w.name, "своя точка · ПКМ — удалить", dist, w.y - me.y, null), w));
        }
    }

    private void drawContacts(GuiGraphics g, double s, Vec3 me, float alpha, float t) {
        Contacts c = mod.contacts;
        boolean fresh = c.fresh();
        if (fresh) {
            for (Contacts.Ship sh : c.ships) {
                int col = sh.mine() ? Radar.CYAN : Radar.AMBER;
                String kind = (sh.mine() ? "твой корабль" : "корабль") + (sh.flying() ? " · в полёте" : " · стоит");
                blip(g, s, me, sh.x(), sh.y(), sh.z(), sh.yaw(), col, 8, 3, sh.name(), kind, sh.owner(), alpha, t, true);
            }
            for (Contacts.Player p : c.players) {
                blip(g, s, me, p.x(), p.y(), p.z(), p.yaw(), Radar.PHOS, 6, 2, p.name(),
                        p.pilot() ? "игрок · пилотирует" : "игрок", null, alpha, t, false);
            }
        } else if (mod.cfg.clientPlayers && mc.level != null) {
            for (AbstractClientPlayer p : mc.level.players()) {
                if (p == mc.player) continue;
                blip(g, s, me, p.getX(), p.getY(), p.getZ(), p.getYRot(), Radar.PHOS, 6, 2,
                        p.getName().getString(), "игрок (видит клиент)", null, alpha, t, false);
            }
        }
    }

    private void blip(GuiGraphics g, double s, Vec3 me, double wx, double wy, double wz, float yaw, int col, int len,
                      int wide, String name, String kind, String owner, float alpha, float t, boolean rim) {
        float fx = (float) (cx + (wx - viewX) * s), fy = (float) (cy + (wz - viewZ) * s);
        double ddx = fx - cx, ddy = fy - cy, d = Math.sqrt(ddx * ddx + ddy * ddy);
        double dist = Math.sqrt(sq(wx - me.x) + sq(wy - me.y) + sq(wz - me.z));
        if (d > r - 4) {
            if (!rim) return;
            int x = cx + (int) Math.round(ddx / d * (r - 3)), y = cy + (int) Math.round(ddy / d * (r - 3));
            g.fill(x - 1, y - 1, x + 2, y + 2, Radar.a(col, alpha * 0.55f));
            hits.add(new Hit(x, y, 4, tip(name, kind + " · за краем", dist, wy - me.y, owner), null));
            return;
        }
        int x = Math.round(fx), y0 = Math.round(fy);
        float gl = glow(x, y0, t);
        int st = stem(wy - me.y);
        drawStem(g, x, y0, st, Radar.a(col, alpha * gl * 0.7f));
        int y = y0 - st;
        Radar.arrow(g, x, y, yaw, len, wide, Radar.a(col, alpha * gl));
        if (Math.abs(wy - me.y) >= 3) {
            g.drawString(font, mono(Radar.dy(wy - me.y)), x + 5, y + 1, Radar.a(col, alpha * gl * 0.75f), false);
        }
        hits.add(new Hit(x, y, 5, tip(name, kind, dist, wy - me.y, owner), null));
    }

    private void drawSelf(GuiGraphics g, double s, Vec3 me, float yaw, float appear, float t) {
        int x = (int) Math.round(cx + (me.x - viewX) * s), y = (int) Math.round(cy + (me.z - viewZ) * s);
        int dx = x - cx, dy = y - cy;
        if (dx * dx + dy * dy > (r - 4) * (r - 4)) {             // увёл вид далеко — стрелка «ты там» на ободе
            double d = Math.sqrt(dx * dx + dy * dy);
            int rx = cx + (int) Math.round(dx / d * (r - 5)), ry = cy + (int) Math.round(dy / d * (r - 5));
            float ang = (float) Math.toDegrees(Math.atan2(-dx, dy));
            Radar.arrow(g, rx, ry, ang, 6, 3, Radar.a(Radar.WHITE, appear));
            return;
        }
        float ping = (t % 1.6f) / 1.6f;                         // расходящееся кольцо «это ты»
        Radar.ring(g, x, y, 3 + ping * 10, Radar.a(Radar.WHITE, appear * (1 - ping) * 0.6f), 0);
        Radar.arrow(g, x, y, yaw, 10, 4, Radar.a(0xFF0A1A12, appear));
        Radar.arrow(g, x, y, yaw, 8, 3, Radar.a(Radar.WHITE, appear));
        hits.add(new Hit(x, y, 4, List.of(Component.literal("Ты").withStyle(s0 -> s0.withColor(Radar.WHITE & 0xFFFFFF))), null));
    }

    private void drawSweep(GuiGraphics g, float t, float appear) {
        float th = (float) sweepAngle(t);
        for (int i = 22; i >= 1; i--) {                          // послесвечение позади луча
            float k = 1 - i / 23f;
            Radar.line(g, cx, cy, th - i * 0.03f, r - 1, 2, Radar.a(0x553FD48A, appear * k * k));
        }
        Radar.line(g, cx, cy, th, r - 1, 1, Radar.a(Radar.PHOS, appear * 0.85f));
    }

    private void drawScanlines(GuiGraphics g, float t, float appear) {
        int col = Radar.a(0x22000000, appear);
        for (int y = sqY + 2; y < sqY + sqD - 2; y += 3) g.fill(sqX + 2, y, sqX + sqD - 2, y + 1, col);
        int band = sqY + 2 + (int) ((t * 37) % (sqD - 6));       // медленная полоса помехи
        g.fill(sqX + 2, band, sqX + sqD - 2, band + 2, Radar.a(0x0E7CFFB6, appear));
    }

    // ---------------------------------------------------------------- боковая панель

    private void drawSide(GuiGraphics g, Vec3 me, float yaw, int mx, int my, float appear, float fl, long now) {
        Radar.box(g, sideX, sideY, sideW, sideH, Radar.a(Radar.PANEL2, appear), Radar.a(Radar.BORDER, appear), 2);
        int x = sideX + 5, w = sideW - 10;
        int bottom = sideY + sideH - 4;
        int y = sideY + 5;

        y = title(g, "ПОЗИЦИЯ", x, y, w, appear);
        y = row(g, "X", String.valueOf(Math.round(me.x)), x, y, w, Radar.PHOS, appear * fl);
        y = row(g, "Y", String.valueOf(Math.round(me.y)), x, y, w, Radar.PHOS, appear * fl);
        y = row(g, "Z", String.valueOf(Math.round(me.z)), x, y, w, Radar.PHOS, appear * fl);
        int hdg = (int) Math.round(((yaw + 180) % 360 + 360) % 360);  // курс: 0 — север, 90 — восток
        y = row(g, "КУРС", String.format(Locale.ROOT, "%03d° %s", hdg, side(hdg)), x, y, w, Radar.PHOS, appear * fl);
        y += 3;
        y = title(g, "ОБЗОР", x, y, w, appear);
        y = row(g, "РАДИУС", Radar.dist(mod.range), x, y, w, Radar.PHOS, appear);
        y = row(g, "СЕТКА", Radar.dist(gridStep()), x, y, w, Radar.PHOS_MID, appear);
        y = row(g, "ВИД", following ? "ЗА ТОБОЙ" : "СВОБОДНЫЙ", x, y, w, following ? Radar.PHOS_MID : Radar.AMBER, appear);

        // кнопки
        y += 3;
        if (y + 13 < bottom) {
            int bw = (w - 6) / 4;
            button(g, x, y, bw * 2, 12, "К СЕБЕ", this::recenter, mx, my, appear);
            button(g, x + bw * 2 + 3, y, bw, 12, "+", () -> zoom(1, false, 0, 0), mx, my, appear);
            button(g, x + bw * 3 + 6, y, w - bw * 3 - 6, 12, "−", () -> zoom(-1, false, 0, 0), mx, my, appear);
            y += 16;
        }

        // выбранная точка
        if (selected != null && !mod.waypoints.all().contains(selected)) selected = null;
        if (selected != null && y + 60 < bottom) {
            y = title(g, "ТОЧКА", x, y, w, appear);
            Radar.box(g, x - 1, y - 2, w + 2, 13, Radar.a(0xFF020604, appear),
                    Radar.a(nameBox.isFocused() ? Radar.BORDER_HI : Radar.BORDER, appear), 1);
            nameBox.setX(x + 2);
            nameBox.setY(y);
            nameBox.setWidth(w - 4);
            y += 14;
            y = row(g, "X Y Z", selected.x + " " + selected.y + " " + selected.z, x, y, w, WAYPOINT, appear);
            double d = Math.sqrt(sq(selected.x + 0.5 - me.x) + sq(selected.y - me.y) + sq(selected.z + 0.5 - me.z));
            y = row(g, "ДО НЕЁ", Radar.dist(d), x, y, w, WAYPOINT, appear);
            Waypoints.Wp sel = selected;
            button(g, x, y, w, 12, "УДАЛИТЬ ТОЧКУ", () -> deleteWaypoint(sel), mx, my, appear);
            y += 16;
        } else {
            nameBox.setY(-100);
        }

        // курсор
        if (inScope(mx, my) && y + 36 < bottom) {
            double s = scale();
            double wx = viewX + (mx - cx) / s, wz = viewZ + (my - cy) / s;
            y = title(g, "КУРСОР", x, y, w, appear);
            int h = mod.terrain.heightAt((int) Math.floor(wx), (int) Math.floor(wz));
            y = row(g, "X Z", Math.round(wx) + " " + Math.round(wz), x, y, w, Radar.PHOS_MID, appear);
            String hs = h == Integer.MIN_VALUE ? "—" : h + " (" + Radar.dy(h - me.y) + ")";
            y = row(g, "БЛОК Y", hs, x, y, w, Radar.PHOS_MID, appear);
            y = row(g, "ДАЛЬН", Radar.dist(Math.sqrt(sq(wx - me.x) + sq(wz - me.z))), x, y, w, Radar.PHOS_MID, appear);
            y += 3;
        }

        // контакты
        if (y + 50 < bottom) {
            Contacts c = mod.contacts;
            boolean fresh = c.fresh();
            y = title(g, "КОНТАКТЫ", x, y, w, appear);
            y = row(g, "КОРАБЛИ", fresh ? String.valueOf(c.ships.size()) : "—", x, y, w, Radar.AMBER, appear);
            int players = fresh ? c.players.size() : (mod.cfg.clientPlayers && mc.level != null ? mc.level.players().size() - 1 : 0);
            y = row(g, "ИГРОКИ", String.valueOf(Math.max(0, players)), x, y, w, Radar.PHOS, appear);
            y = row(g, "ТОЧКИ", String.valueOf(mod.waypoints.all().size()), x, y, w, WAYPOINT, appear);
            String scan = !mod.cfg.terrain ? "ВЫКЛ" : Math.round(mod.terrain.progress() * 100) + "%";
            y = row(g, "СКАН", scan, x, y, w, Radar.PHOS_MID, appear);
            boolean blinkOff = !fresh && (now / 400) % 2 == 0;
            y = row(g, "СВЯЗЬ", fresh ? "ЕСТЬ" : "НЕТ", x, y, w, fresh ? Radar.PHOS : (blinkOff ? Radar.PHOS_DIM : Radar.RED), appear);
            y += 3;
        }

        // условные знаки
        if (y + 34 < bottom) {
            int ly = bottom - 32;
            legend(g, x, ly, Radar.WHITE, "ТЫ", appear);
            legend(g, x + w / 2, ly, Radar.CYAN, "ТВОЙ КОРАБЛЬ", appear);
            legend(g, x, ly + 11, Radar.AMBER, "КОРАБЛЬ", appear);
            legend(g, x + w / 2, ly + 11, Radar.PHOS, "ИГРОК", appear);
            Radar.diamond(g, x + 3, ly + 25, 2, Radar.a(WAYPOINT, appear));
            g.drawString(font, "ТОЧКА", x + 9, ly + 21, Radar.a(Radar.PHOS_DIM, appear), false);
            g.fill(x + w / 2 + 2, ly + 24, x + w / 2 + 4, ly + 26, Radar.a(Radar.PHOS_MID, appear));
            g.drawString(font, "БЛОКИ", x + w / 2 + 9, ly + 21, Radar.a(Radar.PHOS_DIM, appear), false);
        }
    }

    private static String side(int hdg) {
        String[] n = {"С", "СВ", "В", "ЮВ", "Ю", "ЮЗ", "З", "СЗ"};
        return n[Math.floorMod(Math.round(hdg / 45f), 8)];
    }

    private int title(GuiGraphics g, String s, int x, int y, int w, float appear) {
        g.drawString(font, s, x, y, Radar.a(Radar.PHOS_MID, appear), false);
        int tw = font.width(s);
        g.fill(x + tw + 3, y + 4, x + w, y + 5, Radar.a(Radar.BORDER, appear));
        return y + 11;
    }

    private int row(GuiGraphics g, String label, String value, int x, int y, int w, int col, float alpha) {
        g.drawString(font, label, x, y, Radar.a(Radar.PHOS_DIM, alpha), false);
        Component v = mono(value);
        g.drawString(font, v, x + w - font.width(v), y, Radar.a(col, alpha), false);
        return y + 10;
    }

    private void legend(GuiGraphics g, int x, int y, int col, String s, float appear) {
        Radar.arrow(g, x + 3, y + 4, 180, 6, 2, Radar.a(col, appear));
        g.drawString(font, s, x + 9, y, Radar.a(Radar.PHOS_DIM, appear), false);
    }

    private void button(GuiGraphics g, int x, int y, int w, int h, String label, Runnable action, int mx, int my, float appear) {
        Btn b = new Btn(x, y, w, h, label, action);
        boolean hover = b.in(mx, my);
        Radar.box(g, x, y, w, h, Radar.a(hover ? 0xFF0E3A26 : 0xFF07180F, appear),
                Radar.a(hover ? Radar.BORDER_HI : Radar.BORDER, appear), 1);
        g.drawString(font, label, x + (w - font.width(label)) / 2, y + 2, Radar.a(hover ? Radar.WHITE : Radar.PHOS, appear), false);
        buttons.add(b);
    }

    private Component mono(String s) {
        return mod.cfg.monoDigits ? Component.literal(s).withStyle(MONO) : Component.literal(s);
    }

    private List<Component> tip(String name, String kind, double dist, double dy, String owner) {
        List<Component> l = new ArrayList<>();
        l.add(Component.literal(name.isEmpty() ? "—" : name).withStyle(st -> st.withColor(Radar.PHOS & 0xFFFFFF)));
        l.add(Component.literal(kind).withStyle(st -> st.withColor(Radar.PHOS_MID & 0xFFFFFF)));
        if (owner != null && !owner.isEmpty()) {
            l.add(Component.literal("владелец: " + owner).withStyle(st -> st.withColor(Radar.PHOS_MID & 0xFFFFFF)));
        }
        l.add(Component.literal("дальность " + Radar.dist(dist) + " · высота " + Radar.dy(dy))
                .withStyle(st -> st.withColor(Radar.AMBER & 0xFFFFFF)));
        return l;
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
            if (d <= (h.rad() + 1) * (h.rad() + 1) && d < bd) {
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
        if (nameBox != null) {
            nameBox.setFocused(false);
            setFocused(null);
            nameBox.setValue(w == null ? "" : w.name);
        }
    }

    private static void click() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent ev, boolean doubleClick) {
        double mx = ev.x(), my = ev.y();
        if (nameBox.visible && nameBox.isMouseOver(mx, my)) return super.mouseClicked(ev, doubleClick);
        if (nameBox.isFocused()) {
            nameBox.setFocused(false);
            setFocused(null);
        }
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
            if (!dragging && sq(ev.x() - pressX) + sq(ev.y() - pressY) > 9) {
                dragging = true;
                following = false;
            }
            if (dragging) {
                double s = scale();
                viewX -= dx / s;
                viewZ -= dy / s;
            }
            return true;
        }
        return super.mouseDragged(ev, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent ev) {
        if (pressing && ev.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            pressing = false;
            if (!dragging && inScope(ev.x(), ev.y())) {
                Waypoints.Wp hit = waypointAt(ev.x(), ev.y());
                if (hit != null) {
                    select(hit == selected ? null : hit);
                } else {
                    double s = scale();
                    int wx = (int) Math.floor(viewX + (ev.x() - cx) / s), wz = (int) Math.floor(viewZ + (ev.y() - cy) / s);
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
            zoom(sy, inScope(mx, my), mx, my);
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
        if (nameBox.isFocused()) {                               // печатаем имя — клавиши карты не работают
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER || key == GLFW.GLFW_KEY_ESCAPE) {
                nameBox.setFocused(false);
                setFocused(null);
                if (selected != null) nameBox.setValue(selected.name);
                return true;
            }
            super.keyPressed(e);
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
        if (nameBox.isFocused()) return super.charTyped(e);
        return true;                                             // буква «м» и т.п. не уходят никуда
    }
}
