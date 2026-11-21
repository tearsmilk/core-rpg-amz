package dev.tearsmilk.aurahud.layout;

import java.util.ArrayList;
import java.util.List;

final class VanillaHud {

    enum Kind {
        SPRITE, GUI, TEXT
    }

    record Quad(String tag, Kind kind, double[][] v, boolean white, int tex, int family, int argb) {

        int r() {
            return argb >> 16 & 255;
        }

        int g() {
            return argb >> 8 & 255;
        }

        int b() {
            return argb & 255;
        }

        int a() {
            return argb >>> 24;
        }
    }

    record Scenario(int maxHearts, int absorbHearts, boolean lowHealth, int regenIndex, int armorRows, boolean offRight,
                    int selected, double pop, boolean foodJitter, int vehicleHearts, boolean air, boolean creative) {

        static Scenario basic() {
            return new Scenario(10, 0, false, -1, 1, false, 4, 0, false, 0, true, false);
        }

        static List<Scenario> all() {
            return List.of(basic(),
                    new Scenario(10, 4, true, 3, 2, true, 0, 1.6, true, 0, true, false),
                    new Scenario(15, 0, false, 12, 2, false, 8, 1.2, false, 0, false, false),
                    new Scenario(10, 10, false, -1, 2, false, 3, 0, true, 0, true, true),
                    new Scenario(10, 0, false, -1, 1, false, 2, 0, false, 23, true, false));
        }
    }

    final List<Quad> quads = new ArrayList<>();
    final int w;
    final int h;
    final int cx;

    VanillaHud(int w, int h) {
        this.w = w;
        this.h = h;
        this.cx = w / 2;
    }

    static VanillaHud build(int w, int h, Scenario s) {
        VanillaHud v = new VanillaHud(w, h);
        v.hud(s);
        return v;
    }

    static VanillaHud screens(int w, int h) {
        VanillaHud v = new VanillaHud(w, h);
        v.blit("screen:item", w / 2 - 80, h / 2 + 59, 16, 16, true, true, 0);
        v.blit("screen:item", w / 2 - 80, h / 2 + 87, 16, 16, true, true, 0);
        v.blit("screen:container", w / 2 - 88, h / 2 - 83, 176, 166, true, ShaderMirror.TEX_256, 0);
        v.blit("screen:bossbar", w / 2 - 91, 12, 182, 5, true, false, 0);
        v.blit("screen:crosshair", (w - 15) / 2, (h - 15) / 2, 15, 15, true, false, 0);
        v.blit("screen:vignette", 0, 0, w, h, false, false, 0);
        v.blit("screen:pumpkin", 0, 0, w, h, true, false, 0);
        v.blit("screen:effect", w - 25, 1, 24, 24, true, false, 0);
        v.fill("screen:gradient", 0, 0, w, h, 0xC0101010);
        v.fill("screen:chatbg", 2, h - 14, w - 2, h - 2, 0x80000000);
        v.fill("screen:chatline", 0, h - 49, 320, h - 40, 0x80000000);
        v.text("screen:chatinput", 4, h - 10, "/ah status", 0xFFE0E0E0, true);
        v.text("screen:chat", 4, h - 49, "hello there", 0xFFFFFFFF, true);
        v.text("screen:tab", w / 2 - 20, 10, "Players", 0xFFFFFFFF, true);
        v.text("screen:title", w / 2 - 30, h / 2 - 30, "Title", 0xFFFFFFFF, true);
        v.text("screen:label", w / 2 - 80, h / 2 - 75, "Inventory", 0xFF404040, false);
        v.text("screen:count", w / 2 - 80 + 5, h / 2 + 59 + 9, "64", 0xFFFFFFFF, true);
        return v;
    }

    static VanillaHud container(int w, int h, int rows, int hover) {
        VanillaHud v = new VanillaHud(w, h);
        int ih = rows < 0 ? 166 : 114 + rows * 18;
        int left = (w - 176) / 2;
        int top = (h - ih) / 2;
        v.fill("screen:dim", 0, 0, w, h, 0xC0101010);
        List<int[]> slots = new ArrayList<>();
        if (rows < 0) {
            v.blit("screen:bg", left, top, 176, 166, true, ShaderMirror.TEX_256, 0);
            for (int i = 0; i < 4; i++) {
                slots.add(new int[]{8, 8 + 18 * i});
            }
            slots.add(new int[]{77, 62});
            for (int i = 0; i < 4; i++) {
                slots.add(new int[]{98 + 18 * (i % 2), 18 + 18 * (i / 2)});
            }
            slots.add(new int[]{154, 28});
            for (int r = 0; r < 3; r++) {
                for (int c = 0; c < 9; c++) {
                    slots.add(new int[]{8 + 18 * c, 84 + 18 * r});
                }
            }
            for (int c = 0; c < 9; c++) {
                slots.add(new int[]{8 + 18 * c, 142});
            }
            v.blit("screen:recipe", left + 104, h / 2 - 22, 20, 18, true, ShaderMirror.TEX_GUI, 0);
            v.text("screen:label", left + 97, top + 6, "Crafting", 0xFF404040, false);
        } else {
            v.blit("screen:bg", left, top, 176, rows * 18 + 17, true, ShaderMirror.TEX_256, 0);
            v.blit("screen:bg", left, top + rows * 18 + 17, 176, 96, true, ShaderMirror.TEX_256, 0);
            int off = (rows - 4) * 18;
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < 9; c++) {
                    slots.add(new int[]{8 + 18 * c, 18 + 18 * r});
                }
            }
            for (int r = 0; r < 3; r++) {
                for (int c = 0; c < 9; c++) {
                    slots.add(new int[]{8 + 18 * c, 103 + off + 18 * r});
                }
            }
            for (int c = 0; c < 9; c++) {
                slots.add(new int[]{8 + 18 * c, 161 + off});
            }
            v.text("screen:label", left + 8, top + 6, "Chest", 0xFF404040, false);
            v.text("screen:label", left + 8, top + ih - 94, "Inventory", 0xFF404040, false);
        }
        for (int i = 0; i < slots.size(); i++) {
            int x = left + slots.get(i)[0];
            int y = top + slots.get(i)[1];
            if (i == hover) {
                v.blit("screen:highlight", x - 4, y - 4, 24, 24, true, ShaderMirror.TEX_GUI, 7);
            }
            v.blit("screen:item", x, y, 16, 16, true, ShaderMirror.TEX_ITEMS, 0);
            v.fill("screen:bar", x + 2, y + 13, x + 15, y + 15, 0xFF000000);
            v.fill("screen:bar", x + 2, y + 13, x + 2 + 7, y + 14, 0xFF33CC22);
            v.fill("screen:cooldown", x, y + 4, x + 16, y + 16, 0x7FFFFFFF);
            if (i == hover) {
                v.fill("screen:hover", x, y, x + 16, y + 16, 0x80FFFFFF);
            }
        }
        return v;
    }

    static VanillaHud counts(int w, int h, int rows) {
        VanillaHud v = new VanillaHud(w, h);
        int ih = rows < 0 ? 166 : 114 + rows * 18;
        int left = (w - 176) / 2;
        int top = (h - ih) / 2;
        int[] ys = rows < 0 ? new int[]{84, 102, 120, 142} : new int[]{18, 18 + 18 * (rows - 1), 103 + (rows - 4) * 18, 161 + (rows - 4) * 18};
        for (int sy : ys) {
            for (int c = 0; c < 9; c++) {
                int x = left + 8 + 18 * c;
                v.text("screen:count", x + 17 - 12, top + sy + 9, "64", 0xFFFFFFFF, true);
            }
        }
        return v;
    }

    void blit(String tag, double x, double y, double bw, double bh, boolean white, boolean atlas, int family) {
        blit(tag, x, y, bw, bh, white, atlas ? ShaderMirror.TEX_ITEMS : ShaderMirror.TEX_GUI, family);
    }

    void blit(String tag, double x, double y, double bw, double bh, boolean white, int tex, int family) {
        quads.add(new Quad(tag, Kind.SPRITE, new double[][]{{x, y}, {x, y + bh}, {x + bw, y + bh}, {x + bw, y}}, white, tex, family, white ? -1 : 0xFF8040C0));
    }

    void fill(String tag, double x0, double y0, double x1, double y1, int argb) {
        quads.add(new Quad(tag, Kind.GUI, new double[][]{{x1, y1}, {x1, y0}, {x0, y0}, {x0, y1}}, false, ShaderMirror.TEX_OTHER, 0, argb));
    }

    void glyph(String tag, double x, double y, int argb) {
        quads.add(new Quad(tag, Kind.TEXT, new double[][]{{x, y}, {x, y + 8}, {x + 8, y + 8}, {x + 8, y}}, false, ShaderMirror.TEX_OTHER, 0, argb));
    }

    void text(String tag, double x, double y, String s, int argb, boolean shadow) {
        for (int i = 0; i < s.length(); i++) {
            if (shadow) {
                int sc = argb & 0xFF000000 | ((argb >> 16 & 255) * 64 / 256) << 16 | ((argb >> 8 & 255) * 64 / 256) << 8 | (argb & 255) * 64 / 256;
                glyph(tag, x + 6 * i + 1, y + 1, argb == 0xFFFFFFFF ? 0xFF3F3F3F : sc);
            }
            glyph(tag, x + 6 * i, y, argb);
        }
    }

    private void item(String tag, double x, double y, double f) {
        if (f <= 0) {
            blit(tag, x, y, 16, 16, true, true, 0);
            return;
        }
        double[][] v = new double[4][];
        double[][] c = {{0, 0}, {0, 1}, {1, 1}, {1, 0}};
        for (int k = 0; k < 4; k++) {
            v[k] = new double[]{x + 8 + (16 * c[k][0] - 8) / f, y + 12 + (16 * c[k][1] - 12) * (f + 1) / 2};
        }
        quads.add(new Quad(tag, Kind.SPRITE, v, true, ShaderMirror.TEX_ITEMS, 0, -1));
    }

    private void decorations(String tag, int sx, boolean bar, double cool, String count) {
        if (bar) {
            fill(tag, sx + 2, h - 6, sx + 15, h - 4, 0xFF000000);
            fill(tag, sx + 2, h - 6, sx + 2 + 9, h - 5, 0xFF33CC22);
        }
        if (cool > 0) {
            int top = h - 19 + (int) Math.floor(16 * (1 - cool));
            fill(tag, sx, top, sx + 16, top + (int) Math.ceil(16 * cool), 0x7FFFFFFF);
        }
        if (count != null) {
            text(tag, sx + 17 - 6 * count.length(), h - 10, count, 0xFFFFFFFF, true);
        }
    }

    private void hud(Scenario s) {
        blit("hotbar:bg", cx - 91, h - 22, 182, 22, true, false, 0);
        blit("slot:" + s.selected() + ":select", cx - 92 + 20 * s.selected(), h - 23, 24, 23, true, false, 0);
        int offX = s.offRight() ? cx + 91 : cx - 120;
        blit("offhand:bg", offX, h - 23, 29, 24, true, false, 0);
        for (int i = 0; i < 9; i++) {
            int sx = cx - 88 + 20 * i;
            item("slot:" + i + ":item", sx, h - 19, i == 2 ? s.pop() : 0);
            decorations("slot:" + i + ":deco", sx, i % 3 == 0, i == 1 ? 0.4 : i == 7 ? 1.0 : 0, i % 2 == 0 ? (i == 4 ? "7" : "64") : null);
        }
        int osx = s.offRight() ? cx + 101 : cx - 117;
        item("offhand:item", osx, h - 19, 0);
        decorations("offhand:deco", osx, true, 0.5, "16");
        int ax = s.offRight() ? cx - 113 : cx + 97;
        blit("attack", ax, h - 20, 18, 18, true, false, 0);
        blit("attack", ax, h - 2 - 11, 18, 11, true, false, 0);
        int rows = (int) Math.ceil((s.maxHearts() + s.absorbHearts()) / 10.0);
        int step = Math.max(10 - (rows - 2), 3);
        int armorRows = s.armorRows();
        int armorY = h - 39 - (rows - 1) * step - 10;
        for (int j = 0; j < 10; j++) {
            blit("armor:" + j + ":" + (rows - 1) * step, cx - 91 + 8 * j, armorY, 9, 9, true, false, 2);
        }
        long seed = 7;
        for (int i = s.maxHearts() + s.absorbHearts() - 1; i >= 0; i--) {
            int x = cx - 91 + (i % 10) * 8;
            int y = h - 39 - (i / 10) * step;
            int d = 0;
            if (s.lowHealth()) {
                seed = seed * 6364136223846793005L + 1442695040888963407L;
                d += (int) (seed >>> 63);
            }
            if (i < s.maxHearts() && i == s.regenIndex()) {
                d -= 2;
            }
            blit("heart:" + i + ":" + d, x, y + d, 9, 9, true, false, 1);
            blit("heart:" + i + ":" + d, x, y + d, 9, 9, true, false, 1);
        }
        if (armorRows < 0) {
            return;
        }
        if (s.vehicleHearts() == 0) {
            for (int j = 0; j < 10; j++) {
                int d = s.foodJitter() ? (j % 3) - 1 : 0;
                blit("food:" + j + ":" + d, cx + 82 - 8 * j, h - 39 + d, 9, 9, true, false, 3);
            }
        } else {
            for (int i = 0; i < s.vehicleHearts(); i++) {
                int r = i / 10;
                int j = i % 10;
                blit("mount:" + i, cx + 82 - 8 * j, h - 39 - 10 * r, 9, 9, true, false, 5);
            }
        }
        if (s.air()) {
            int vRows = (int) Math.ceil(s.vehicleHearts() / 10.0);
            int ay = h - 49 - (s.vehicleHearts() > 0 ? (vRows - 1) * 10 : 0);
            for (int k = 1; k <= 10; k++) {
                int d = k % 4 == 0 ? 1 : 0;
                blit("air:" + (k - 1) + ":" + d + ":" + (s.vehicleHearts() > 0 ? vRows - 1 : 0), cx + 82 - 8 * (k - 1), ay + d, 9, 9, true, false, 4);
            }
        }
        blit("xp", cx - 91, h - 29, 182, 5, true, false, 0);
        blit("xp", cx - 91, h - 29, 77, 5, true, false, 0);
        blit("xp", cx - 4 + 31, h - 31, 9, 9, false, false, 6);
        blit("xp", cx - 4 + 31 + 1, h - 35, 7, 5, true, false, 0);
        blit("xp", cx - 4 - 50, h - 31, 9, 9, false, false, 6);
        blit("xp", cx - 4 - 50 + 1, h - 23, 7, 5, true, false, 0);
        int lw = 12;
        int lx = (w - lw) / 2;
        text("level", lx + 1, h - 35, "30", 0xFF000000, false);
        text("level", lx - 1, h - 35, "30", 0xFF000000, false);
        text("level", lx, h - 34, "30", 0xFF000000, false);
        text("level", lx, h - 36, "30", 0xFF000000, false);
        text("level", lx, h - 35, "30", 0xFF80FF20, false);
        int ny = s.creative() ? h - 45 : h - 59;
        int nw = 30;
        int nx = (w - nw) / 2;
        fill("name", nx - 2, ny - 2, nx + nw + 2, ny + 11, 0x40000000);
        text("name", nx, ny, "Sword", 0xFF55FFFF, true);
    }
}
