package ink.hoka.odyssey.client.guns;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Окно обвеса (Y, как Z у TACZ): с оружием в руке. Мир идёт дальше (не пауза), оружие в руке плавно поворачивается к
 * «камере» refit_view, а у выбранного слота — к refit_&lt;слот&gt;_view (их двигает модуль, см. {@code refitLook}).
 * Справа вверху — слоты (справа налево: прицел, дуло, приклад, рукоять, ЛЦУ, [второй ЛЦУ — только у оружия с ним],
 * магазин); под выбранным — «СНЯТЬ» и
 * список подходящих обвесов (встроенные и из инвентаря, по 8 на страницу); наведённый показывается на оружии и в
 * характеристиках (слева вверху: что изменится — зелёным/красным). У прицела — планки, у ЛЦУ — цвет луча. Ставит и
 * снимает сервер (он же всё проверяет), ответ — строкой внизу. Стиль — как радар starmap и HUD оружия: тёмное стекло,
 * зелёный люминофор, моноширинный шрифт. Характеристики по умолчанию скрыты (выбор помнится, пока игра запущена); у
 * оружия со своей окраской (attachments.paint) — кнопка «Покрасить» (стандарт / чёрный металл). Руки в окне не видны.
 */
final class RefitScreen extends Screen {
    private static final int[] ORDER = {Att.SCOPE, Att.MUZZLE, Att.STOCK, Att.GRIP, Att.LASER, Att.MAG};
    /** То же у оружия со вторым ЛЦУ (справа от первого). */
    private static final int[] ORDER2 = {Att.SCOPE, Att.MUZZLE, Att.STOCK, Att.GRIP, Att.LASER, Att.LASER2, Att.MAG};
    private static final String[] PAINTS = {"Стандарт", "Чёрный металл"};
    /** Показывать ли характеристики (по умолчанию нет; выбор помнится, пока игра запущена). */
    private static boolean statsShown;
    private static final int PAGE = 8;

    private final GunsClientModule mod;
    private final Minecraft mc = Minecraft.getInstance();
    private long openedAt;
    private int selected = -1;
    private int page;
    private boolean showStats = statsShown;
    private String msg = "";
    private int msgCode;
    private long msgNs;
    /** Ползунки цвета луча: 1 — тон, 2 — насыщенность (0 — не тянем); тон и насыщенность 0…1. */
    private int drag;
    private float hue = -1;
    private float sat = 1;
    private int laserSlot = -1;
    private final List<Btn> buttons = new ArrayList<>();
    private Entry hoverEntry;
    // раскладка (настоящие пиксели экрана)
    private int gs = 1;
    private int fbW;
    private int fbH;
    private float u = 1;
    private Slider hueBar;
    private Slider satBar;

    private record Btn(int x, int y, int w, int h, Runnable left, Runnable right, Entry entry) {
        boolean in(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private record Slider(int x, int y, int w, int h) {
        boolean in(double mx, double my) {
            return mx >= x - 4 && mx < x + w + 4 && my >= y - 4 && my < y + h + 4;
        }

        float at(double mx) {
            return Math.max(0, Math.min(1, (float) ((mx - x) / Math.max(1, w))));
        }
    }

    /**
     * Строка списка: обвес из инвентаря (invSlot — слот, count — сколько всего) или встроенный (invSlot −1); slot — в
     * какой слот оружия (второй ЛЦУ принимает те же ЛЦУ).
     */
    private record Entry(Att.Def def, int invSlot, int count, boolean builtin, boolean installed, int slot) {}

    RefitScreen(GunsClientModule mod) {
        super(Component.literal("Обвес"));
        this.mod = mod;
    }

    @Override
    protected void init() {
        if (openedAt == 0) {
            openedAt = System.currentTimeMillis();
            mod.send(Proto.refit(true));
            mod.refitLook(-1);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        if (mc.player == null || mod.heldBase() == null) onClose();
    }

    @Override
    public void removed() {
        mod.send(Proto.refit(false));
        mod.preview = null;
        mod.refitLook(-1);
    }

    /** Ответ сервера на действие с обвесом. */
    void result(int code, String text) {
        msgCode = code;
        msg = text == null ? "" : text;
        msgNs = System.nanoTime();
    }

    private void say(String text, boolean ok) {
        result(ok ? 0 : 2, text);
    }

    // ---------------------------------------------------------------- данные

    private String gunId() {
        Proto.Spec s = mod.heldBase();
        return s == null ? "" : s.id();
    }

    private Att.Gear gear() {
        Att.Gear g = mod.ownGear();
        return g == null ? new Att.Gear() : g;
    }

    /** Что можно поставить в слот: встроенные оружия, потом из инвентаря (одинаковые — одной строкой), по sort. */
    private List<Entry> candidates(int slot) {
        Att.Catalog cat = mod.cat;
        Att.GunAtt ga = cat.gun(gunId());
        Att.Gear gear = gear();
        List<Entry> out = new ArrayList<>();
        for (String id : ga.builtin().getOrDefault(slot, List.of())) {
            Att.Def d = cat.def(id);
            if (d != null) out.add(new Entry(d, -1, 1, true, id.equals(gear.ids[slot]), slot));
        }
        Map<String, int[]> inv = new LinkedHashMap<>();
        if (mc.player != null) {
            var inventory = mc.player.getInventory();
            for (int i = 0; i < 36; i++) {
                ItemStack st = inventory.getItem(i);
                String id = attId(st);
                if (id == null) continue;
                Att.Def d = cat.def(id);
                if (d == null || !Att.accepts(slot, d.slot()) || !cat.allowed(gunId(), d, gear.adapter, slot)) continue;
                int[] e = inv.computeIfAbsent(id, k -> new int[] {-1, 0});
                if (e[0] < 0) e[0] = i;
                e[1] += st.getCount();
            }
        }
        List<Entry> items = new ArrayList<>();
        for (var e : inv.entrySet()) {
            items.add(new Entry(cat.def(e.getKey()), e.getValue()[0], e.getValue()[1], false, false, slot));
        }
        items.sort(Comparator.comparingInt((Entry e) -> e.def().sort()).thenComparing(e -> e.def().name()));
        out.addAll(items);
        return out;
    }

    /** Метка hoka_att предмета-обвеса (custom_data) или null. */
    static String attId(ItemStack s) {
        if (s == null || s.isEmpty()) return null;
        CustomData cd = s.get(DataComponents.CUSTOM_DATA);
        if (cd == null || cd.isEmpty()) return null;
        var tag = cd.copyTag();
        if (tag.contains("hoka_gun")) return null;
        String id = tag.getStringOr("hoka_att", "");
        if (!id.isEmpty()) return id;
        String h = tag.getStringOr("hoka_item", "");                 // выдан через hoitems: att_<id>
        return h.startsWith("att_") && h.length() > 4 ? h.substring(4) : null;
    }

    // ---------------------------------------------------------------- действия

    private void select(int slot) {
        Att.GunAtt ga = mod.cat.gun(gunId());
        if (!ga.has(slot)) {
            say(Att.slotName(ga, slot) + ": у этого оружия такого слота нет", false);
            return;
        }
        if (ga.locked().contains(slot)) {
            say(lockedText(slot), false);
            return;
        }
        if (mod.refitMoving()) return;                           // как у TACZ: пока «камера» едет — ждать
        selected = selected == slot ? -1 : slot;
        page = 0;
        hue = -1;
        mod.refitLook(selected);
    }

    private String lockedText(int slot) {
        Proto.Spec s = mod.g();
        if (slot == Att.SCOPE && s != null) {
            return "Прицел " + GunsClientModule.fmt(s.zoom()) + "x — часть винтовки, не снимается";
        }
        return Att.slotName(mod.cat.gun(gunId()), slot) + ": слот заперт";
    }

    private void detach(int slot) {
        Att.Gear gear = gear();
        Att.Def d = mod.cat.def(gear.ids[slot]);
        if (d == null) return;
        if (mod.cat.gun(gunId()).locked().contains(slot)) {
            say(lockedText(slot), false);
            return;
        }
        mod.expectSound(d.soundUninstall());
        mod.send(Proto.detach(slot));
    }

    private void install(Entry e) {
        if (e.installed()) return;
        int slot = e.slot();
        mod.expectSound(e.def().soundInstall());
        if (e.builtin()) mod.send(Proto.builtin(slot, e.def().id()));
        else mod.send(Proto.attach(slot, e.invSlot()));
    }

    /** Окраска по кругу (стандарт → чёрный металл → стандарт); ставит сервер. */
    private void repaint() {
        Att.Gear gr = mod.ownGear();
        int cur = gr == null ? 0 : gr.paint;
        int next = (cur + 1) % PAINTS.length;
        mod.expectSound("gun_att_attachment_general_a");
        mod.send(Proto.paint(next));
    }

    private int[] order() {
        return mod.cat.gun(gunId()).has(Att.LASER2) ? ORDER2 : ORDER;
    }

    private void riser(String id) {
        mod.expectSound("gun_att_attachment_general_a");
        mod.send(Proto.adapter(Att.SCOPE, id));
    }

    // ---------------------------------------------------------------- рисование

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partial) {
        float ap = AdsBlend.easeOutCubic(Math.min(1, (System.currentTimeMillis() - openedAt) / 250f));
        // мягкая виньетка: края темнее, оружие по центру — как есть (без размытия игры)
        int w = width, h = height;
        int steps = 10;
        for (int i = 0; i < steps; i++) {
            float k = (steps - i) / (float) steps;
            int al = Math.round(70 * k * k * ap);
            int t = Math.max(1, Math.round(h * 0.035f));
            int col = al << 24;
            g.fill(0, i * t, w, i * t + t, col);
            g.fill(0, h - i * t - t, w, h - i * t, col);
            int tx = Math.max(1, Math.round(w * 0.025f));
            g.fill(i * tx, 0, i * tx + tx, h, col);
            g.fill(w - i * tx - tx, 0, w - i * tx, h, col);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        // фон (виньетку) игра рисует сама — renderBackground перед render
        if (mc.player == null || mod.heldBase() == null) return;
        Window win = mc.getWindow();
        gs = Math.max(1, win.getGuiScale());
        fbW = win.getWidth();
        fbH = win.getHeight();
        u = Math.max(0.6f, Math.min(fbW / 1920f, fbH / 1080f) * 1.1f);
        double mx = mouseX * (double) gs, my = mouseY * (double) gs;
        MonoFont font = mod.font();
        buttons.clear();
        hoverEntry = null;
        var pose = g.pose();
        pose.pushMatrix();
        pose.scale(1f / gs, 1f / gs);
        try {
            float ap = AdsBlend.easeOutCubic(Math.min(1, (System.currentTimeMillis() - openedAt) / 250f));
            slots(g, font, mx, my, ap);
            if (showStats) stats(g, font, ap);
            statsToggle(g, font, mx, my);
            paintButton(g, font, mx, my);
            laser(g, font, ap);
            footer(g, font);
            // наведённый обвес — на оружии (только у себя) и в характеристиках
            for (Btn b : buttons) {
                if (b.entry() != null && b.in(mx, my)) hoverEntry = b.entry();
            }
            Att.Gear pv = null;
            if (hoverEntry != null && !hoverEntry.installed()) {
                pv = gear().copy();
                int s = hoverEntry.slot();
                pv.ids[s] = hoverEntry.def().id();
                pv.builtin[s] = hoverEntry.builtin();
                pv.laser[s] = -1;
                if (s == Att.MAG) pv.xmag = -1;
                if (s == Att.SCOPE) pv.zoom = 0;
            }
            mod.preview = pv;
        } finally {
            pose.popMatrix();
        }
        font.flush();
    }

    private void panel(GuiGraphics g, int x, int y, int w, int h, float ap) {
        g.fill(x, y, x + w, y + h, GunHud.a(0xA0041410, ap));
        int c = GunHud.a(GunHud.G_DIM, 0.8f * ap);
        g.fill(x, y, x + w, y + 1, c);
        g.fill(x, y + h - 1, x + w, y + h, c);
        g.fill(x, y, x + 1, y + h, c);
        g.fill(x + w - 1, y, x + w, y + h, c);
        int k = Math.max(4, Math.round(10 * u)), t = Math.max(2, Math.round(2 * u));
        int gc = GunHud.a(GunHud.G, ap);
        g.fill(x, y, x + k, y + t, gc);
        g.fill(x, y, x + t, y + k, gc);
        g.fill(x + w - k, y, x + w, y + t, gc);
        g.fill(x + w - t, y, x + w, y + k, gc);
        g.fill(x, y + h - t, x + k, y + h, gc);
        g.fill(x, y + h - k, x + t, y + h, gc);
        g.fill(x + w - k, y + h - t, x + w, y + h, gc);
        g.fill(x + w - t, y + h - k, x + w, y + h, gc);
        for (int yy = y + 2; yy < y + h - 2; yy += 3) g.fill(x + 2, yy, x + w - 2, yy + 1, 0x1E000000);
    }

    private static String fit(MonoFont f, String s, int px, int maxW) {
        if (f.width(s, px) <= maxW) return s;
        String t = s;
        while (t.length() > 1 && f.width(t + "…", px) > maxW) t = t.substring(0, t.length() - 1);
        return t + "…";
    }

    private void icon(GuiGraphics g, Att.Def d, int x, int y, int size, int argb) {
        Identifier id = mod.icon(d);
        if (id != null) g.blit(RenderPipelines.GUI_TEXTURED, id, x, y, 0f, 0f, size, size, size, size, argb);
    }

    /** Слоты справа вверху, под выбранным — «СНЯТЬ», список, планки. */
    private void slots(GuiGraphics g, MonoFont font, double mx, double my, float ap) {
        Att.Catalog cat = mod.cat;
        Att.GunAtt ga = cat.gun(gunId());
        Att.Gear gear = gear();
        int cell = Math.round(64 * u), gap = Math.round(8 * u), margin = Math.round(28 * u);
        int fs = Math.max(11, Math.round(14 * u));
        int top = margin + font.lineHeight(fs) + Math.round(8 * u);
        int right = fbW - margin;
        // заголовок: оружие
        String title = "ОБВЕС · " + mod.heldBase().name().toUpperCase();
        font.draw(g, title, right - font.width(title, fs), margin, fs, GunHud.a(GunHud.LABEL, ap));
        int selX = -1;
        int[] order = order();
        for (int i = 0; i < order.length; i++) {
            int s = order[i];
            int x = right - (i + 1) * cell - i * gap, y = top;
            boolean has = ga.has(s), locked = ga.locked().contains(s);
            boolean hover = mx >= x && mx < x + cell && my >= y && my < y + cell;
            panel(g, x, y, cell, cell, has ? ap : 0.45f * ap);
            if (s == selected) {
                g.fill(x + 2, y + 2, x + cell - 2, y + cell - 2, GunHud.a(0xFF0E3A28, 0.85f * ap));
                selX = x;
            } else if (hover && has) {
                g.fill(x + 2, y + 2, x + cell - 2, y + cell - 2, GunHud.a(0xFF0B2A1E, 0.8f * ap));
            }
            Att.Def d = cat.def(gear.ids[s]);
            int pad = Math.round(8 * u);
            if (d != null) {
                icon(g, d, x + pad, y + pad, cell - 2 * pad, GunHud.a(0xFFFFFFFF, ap));
            } else {
                String lbl = Att.slotName(ga, s).toUpperCase();
                int sfs = fs;
                while (sfs > 8 && font.width(lbl, sfs) > cell - 6) sfs--;
                font.draw(g, lbl, x + (cell - font.width(lbl, sfs)) / 2, y + (cell - font.lineHeight(sfs)) / 2, sfs,
                        GunHud.a(has ? GunHud.G_DIM : 0xFF404844, ap));
            }
            if (!has) {                                          // слота у оружия нет — перечёркнут
                int c = GunHud.a(GunHud.RED, 0.55f * ap);
                for (int k = 4; k < cell - 4; k++) g.fill(x + k, y + k, x + k + 2, y + k + 2, c);
            }
            if (locked) {
                String l = "ЗАМОК";
                int lfs = Math.max(8, Math.round(11 * u));
                font.draw(g, l, x + (cell - font.width(l, lfs)) / 2, y + cell - font.lineHeight(lfs) - 3, lfs,
                        GunHud.a(GunHud.AMBER, ap));
            }
            final int slot = s;
            buttons.add(new Btn(x, y, cell, cell, () -> select(slot), () -> detach(slot), null));
            if (hover) {
                String tip = locked ? lockedText(s) : has ? Att.slotName(ga, s) + (d != null ? ": " + d.name() : ": пусто")
                        : Att.slotName(ga, s) + ": нет слота";
                int tw = Math.min(font.width(tip, fs), fbW / 2);
                tip = fit(font, tip, fs, tw);
                int ty = y + cell + Math.round(4 * u);
                if (selected >= 0) ty = y - font.lineHeight(fs) - Math.round(4 * u);
                g.fill(right - tw - 8, ty - 2, right + 2, ty + font.lineHeight(fs) + 2, 0xD0041410);
                font.draw(g, tip, right - tw - 3, ty, fs, GunHud.a(GunHud.VALUE, ap));
            }
        }
        if (selected < 0 || selX < 0) {
            String h = "Выбери слот";
            font.draw(g, h, right - font.width(h, fs), top + cell + Math.round(12 * u), fs, GunHud.a(GunHud.G_DIM, ap));
            return;
        }
        // под выбранным: «СНЯТЬ» и список
        int listW = Math.round(380 * u);
        int lx = Math.max(margin, Math.min(right - listW, selX + cell - listW));
        int ly = top + cell + gap;
        int rowH = Math.round(40 * u);
        Att.Def cur = cat.def(gear.ids[selected]);
        boolean unloadHover = mx >= lx && mx < lx + listW && my >= ly && my < ly + rowH;
        panel(g, lx, ly, listW, rowH, ap);
        if (unloadHover && cur != null) g.fill(lx + 2, ly + 2, lx + listW - 2, ly + rowH - 2, GunHud.a(0xFF3A1410, 0.8f * ap));
        String un = cur == null ? "СЛОТ ПУСТ" : "СНЯТЬ: " + cur.name();
        font.draw(g, fit(font, un, fs, listW - 16), lx + 8, ly + (rowH - font.lineHeight(fs)) / 2, fs,
                GunHud.a(cur == null ? GunHud.G_DIM : GunHud.RED, ap));
        final int sel = selected;
        buttons.add(new Btn(lx, ly, listW, rowH, () -> detach(sel), () -> detach(sel), null));
        ly += rowH + gap / 2;
        List<Entry> list = candidates(selected);
        int pages = Math.max(1, (list.size() + PAGE - 1) / PAGE);
        page = Math.max(0, Math.min(page, pages - 1));
        int icon = rowH - Math.round(8 * u);
        if (list.isEmpty()) {
            panel(g, lx, ly, listW, rowH, ap);
            font.draw(g, "Нет подходящих в инвентаре", lx + 8, ly + (rowH - font.lineHeight(fs)) / 2, fs,
                    GunHud.a(GunHud.G_DIM, ap));
            ly += rowH;
        }
        for (int i = page * PAGE; i < Math.min(list.size(), (page + 1) * PAGE); i++) {
            Entry e = list.get(i);
            boolean hover = mx >= lx && mx < lx + listW && my >= ly && my < ly + rowH;
            panel(g, lx, ly, listW, rowH, ap);
            if (e.installed()) g.fill(lx + 2, ly + 2, lx + listW - 2, ly + rowH - 2, GunHud.a(0xFF0E3A28, 0.85f * ap));
            else if (hover) g.fill(lx + 2, ly + 2, lx + listW - 2, ly + rowH - 2, GunHud.a(0xFF0B2A1E, 0.8f * ap));
            icon(g, e.def(), lx + 4, ly + 4, icon, GunHud.a(0xFFFFFFFF, ap));
            String name = e.def().name() + (e.builtin() ? " (встроенный)" : "");
            String cnt = e.builtin() ? "" : "×" + e.count();
            int cw = cnt.isEmpty() ? 0 : font.width(cnt, fs) + 8;
            font.draw(g, fit(font, name, fs, listW - icon - 20 - cw), lx + icon + 10, ly + (rowH - font.lineHeight(fs)) / 2, fs,
                    GunHud.a(e.installed() ? GunHud.G : GunHud.VALUE, ap));
            if (!cnt.isEmpty()) {
                font.draw(g, cnt, lx + listW - 8 - font.width(cnt, fs), ly + (rowH - font.lineHeight(fs)) / 2, fs,
                        GunHud.a(GunHud.LABEL, ap));
            }
            final Entry fe = e;
            buttons.add(new Btn(lx, ly, listW, rowH, () -> install(fe), () -> {
                if (fe.installed()) detach(fe.slot());
            }, e));
            ly += rowH;
        }
        if (pages > 1) {                                          // ▲ стр ▼
            int bw = Math.round(48 * u);
            String pg = (page + 1) + "/" + pages;
            ly += gap / 2;
            panel(g, lx, ly, bw, rowH, ap);
            font.draw(g, "▲", lx + (bw - font.width("▲", fs)) / 2, ly + (rowH - font.lineHeight(fs)) / 2, fs, GunHud.a(GunHud.G, ap));
            buttons.add(new Btn(lx, ly, bw, rowH, () -> page = Math.max(0, page - 1), null, null));
            panel(g, lx + listW - bw, ly, bw, rowH, ap);
            font.draw(g, "▼", lx + listW - bw + (bw - font.width("▼", fs)) / 2, ly + (rowH - font.lineHeight(fs)) / 2, fs,
                    GunHud.a(GunHud.G, ap));
            final int pgs = pages;
            buttons.add(new Btn(lx + listW - bw, ly, bw, rowH, () -> page = Math.min(pgs - 1, page + 1), null, null));
            font.draw(g, pg, lx + (listW - font.width(pg, fs)) / 2, ly + (rowH - font.lineHeight(fs)) / 2, fs,
                    GunHud.a(GunHud.LABEL, ap));
        }
        // планки прицела — колонкой слева от списка
        if (selected == Att.SCOPE && !ga.adapters().isEmpty()) {
            int rw = Math.round(260 * u);
            int rx = lx - rw - gap, ry = top + cell + gap;
            font.draw(g, "ПЛАНКА", rx, ry, fs, GunHud.a(GunHud.LABEL, ap));
            ry += font.lineHeight(fs) + Math.round(4 * u);
            List<String> ids = new ArrayList<>();
            ids.add("");
            List<Att.Adapter> ad = new ArrayList<>();
            for (String id : ga.adapters()) {
                Att.Adapter a = cat.adapters.get(id);
                if (a != null) ad.add(a);
            }
            ad.sort(Comparator.comparingInt(Att.Adapter::sort).thenComparing(Att.Adapter::id));
            for (Att.Adapter a : ad) ids.add(a.id());
            for (String id : ids) {
                Att.Adapter a = id.isEmpty() ? null : cat.adapters.get(id);
                boolean on = id.equals(gear.adapter);
                boolean hover = mx >= rx && mx < rx + rw && my >= ry && my < ry + rowH;
                panel(g, rx, ry, rw, rowH, ap);
                if (on) g.fill(rx + 2, ry + 2, rx + rw - 2, ry + rowH - 2, GunHud.a(0xFF0E3A28, 0.85f * ap));
                else if (hover) g.fill(rx + 2, ry + 2, rx + rw - 2, ry + rowH - 2, GunHud.a(0xFF0B2A1E, 0.8f * ap));
                String nm = a == null ? "Без планки" : a.name();
                font.draw(g, fit(font, nm, fs, rw - 16), rx + 8, ry + (rowH - font.lineHeight(fs)) / 2, fs,
                        GunHud.a(on ? GunHud.G : GunHud.VALUE, ap));
                final String fid = id;
                buttons.add(new Btn(rx, ry, rw, rowH, () -> {
                    if (!fid.equals(gear().adapter)) riser(fid);
                }, null, null));
                ry += rowH;
            }
        }
    }

    // ---------------------------------------------------------------- характеристики

    /** Строка характеристики: подпись, ключ модификатора, лучше ли больше, опора полосы. */
    private record Row(String label, String key, boolean higherBetter, float ref) {}

    private void statsToggle(GuiGraphics g, MonoFont font, double mx, double my) {
        int fs = Math.max(11, Math.round(14 * u));
        int margin = Math.round(28 * u);
        String t = showStats ? "Скрыть характеристики" : "Показать характеристики";
        int w = font.width(t, fs) + 16, h = font.lineHeight(fs) + 10;
        int x = margin, y = margin;
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
        panel(g, x, y, w, h, 1);
        if (hover) g.fill(x + 2, y + 2, x + w - 2, y + h - 2, 0xC00B2A1E);
        font.draw(g, t, x + 8, y + 5, fs, GunHud.LABEL);
        buttons.add(new Btn(x, y, w, h, () -> {
            showStats = !showStats;
            statsShown = showStats;
        }, null, null));
    }

    /** «Покрасить: …» справа от кнопки характеристик — только у оружия со своей окраской (attachments.paint). */
    private void paintButton(GuiGraphics g, MonoFont font, double mx, double my) {
        if (!mod.cat.gun(gunId()).paint()) return;
        int fs = Math.max(11, Math.round(14 * u));
        int margin = Math.round(28 * u);
        String st = showStats ? "Скрыть характеристики" : "Показать характеристики";
        int x = margin + font.width(st, fs) + 16 + Math.round(10 * u), y = margin;
        Att.Gear gr = mod.ownGear();
        int cur = gr == null ? 0 : Math.max(0, Math.min(PAINTS.length - 1, gr.paint));
        String t = "Покрасить: " + PAINTS[cur];
        int h = font.lineHeight(fs) + 10, sw = h - 10;
        int w = font.width(t, fs) + 24 + sw;
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
        panel(g, x, y, w, h, 1);
        if (hover) g.fill(x + 2, y + 2, x + w - 2, y + h - 2, 0xC00B2A1E);
        // образец: стандарт — синий корпус, чёрный металл — графит
        g.fill(x + w - 8 - sw, y + 5, x + w - 8, y + 5 + sw, cur == 1 ? 0xFF1A1C22 : 0xFF22305C);
        font.draw(g, t, x + 8, y + 5, fs, GunHud.LABEL);
        buttons.add(new Btn(x, y, w, h, this::repaint, this::repaint, null));
    }

    private void stats(GuiGraphics g, MonoFont font, float ap) {
        Proto.Stats st = mod.ownStats();
        Att.Catalog cat = mod.cat;
        Att.Gear cur = gear();
        Att.Gear pv = mod.preview != null ? mod.preview : cur;
        int fs = Math.max(11, Math.round(14 * u));
        int margin = Math.round(28 * u);
        int x = margin, y = margin + font.lineHeight(fs) + Math.round(22 * u);
        int w = Math.round(470 * u);
        int lh = font.lineHeight(fs) + Math.round(7 * u);
        Row[] rows = {
                new Row("Урон", "damage", true, 40), new Row("Скорострельность", "rpm", true, 1200),
                new Row("Дальность стрельбы", "effective-range", true, 120), new Row("Разброс в прицеле", "aim-inaccuracy", false, 5),
                new Row("Стрельба от бедра", "inaccuracy", false, 8), new Row("Разброс присев", "sneak-inaccuracy", false, 8),
                new Row("Вертикальная отдача", "recoil-pitch", false, 1.5f), new Row("Горизонтальная отдача", "recoil-yaw", false, 1.5f),
                new Row("Время прицеливания", "ads", false, 0.6f), new Row("Пробитие брони", "armor-ignore", true, 1),
                new Row("Урон в голову", "head-shot", true, 3), new Row("Отбрасывание", "knockback", true, 1),
                new Row("Пронзание", "pierce", true, 5), new Row("Скорость пули", "ammo-speed", true, 400),
                new Row("Вес обвеса", "weight", false, 5)};
        int h = Math.round(16 * u) + lh * (rows.length + 3);
        panel(g, x, y, w, h, ap);
        int px = x + Math.round(12 * u), py = y + Math.round(10 * u);
        // режим огня и кнопка S
        Proto.Spec spec = mod.g();
        String modeTxt = "Режим огня: " + (spec != null && spec.auto() && mod.fireMode() == 0 ? "очередь" : "одиночный");
        font.draw(g, modeTxt, px, py, fs, GunHud.a(GunHud.LABEL, ap));
        if (spec != null && spec.auto() && spec.semi()) {
            int bs = font.lineHeight(fs) + 4;
            int bx = x + w - Math.round(12 * u) - bs;
            panel(g, bx, py - 2, bs, bs, ap);
            font.draw(g, "S", bx + (bs - font.width("S", fs)) / 2, py, fs, GunHud.a(GunHud.AMBER, ap));
            buttons.add(new Btn(bx, py - 2, bs, bs, mod::switchMode, null, null));
        }
        py += lh;
        if (st == null) {
            font.draw(g, "Нет данных с сервера", px, py, fs, GunHud.a(GunHud.G_DIM, ap));
            return;
        }
        // магазин: штатный + от удлинения
        Att.GunAtt ga = cat.gun(gunId());
        int magCur = st.magazine();
        int magPv = pv == cur ? magCur : ga.magazine(pv.xmagLevel(cat));
        if (ga.baseMagazine() <= 0) magPv = magCur;
        int baseMag = ga.baseMagazine() > 0 ? ga.baseMagazine() : magCur;
        String mag = "Патроны: " + magPv + (magPv > baseMag ? "  +" + (magPv - baseMag) : "");
        font.draw(g, mag, px, py, fs, GunHud.a(magPv != magCur ? (magPv > magCur ? GunHud.G : GunHud.RED) : GunHud.VALUE, ap));
        py += lh;
        int labelW = Math.round(220 * u), barW = w - labelW - Math.round(110 * u);
        for (Row r : rows) {
            double vCur = value(st, r.key());
            double vPv;
            if (r.key().equals("weight")) {
                vCur = Att.weight(cat, cur);
                vPv = Att.weight(cat, pv);
            } else if (r.key().startsWith("recoil")) {
                vPv = pv == cur ? vCur : Att.apply(1, Att.mods(cat, pv, r.key()));
            } else if (pv == cur) {
                vPv = vCur;
            } else {
                double base = Att.invert(vCur, Att.mods(cat, cur, r.key()));
                if (!Double.isFinite(base)) base = vCur;
                vPv = Att.apply(base, Att.mods(cat, pv, r.key()));
            }
            font.draw(g, r.label(), px, py, fs, GunHud.a(GunHud.LABEL, ap));
            int bx = px + labelW, by = py + font.lineHeight(fs) / 2 - Math.round(3 * u), bh = Math.max(3, Math.round(6 * u));
            g.fill(bx, by, bx + barW, by + bh, GunHud.a(GunHud.G_DIM, 0.35f * ap));
            float f0 = (float) Math.max(0, Math.min(1, vCur / r.ref())), f1 = (float) Math.max(0, Math.min(1, vPv / r.ref()));
            g.fill(bx, by, bx + Math.round(barW * Math.min(f0, f1)), by + bh, GunHud.a(GunHud.VALUE, 0.9f * ap));
            double delta = vPv - vCur;
            boolean better = r.higherBetter() ? delta > 0 : delta < 0;
            if (Math.abs(delta) > 1e-4) {
                int dc = better ? GunHud.G : GunHud.RED;
                g.fill(bx + Math.round(barW * Math.min(f0, f1)), by, bx + Math.round(barW * Math.max(f0, f1)), by + bh,
                        GunHud.a(dc, 0.95f * ap));
            }
            String val = GunsClientModule.fmt(round(vPv));
            if (Math.abs(delta) > 1e-4) val += " (" + (delta > 0 ? "+" : "") + GunsClientModule.fmt(round(delta)) + ")";
            int vc = Math.abs(delta) > 1e-4 ? (better ? GunHud.G : GunHud.RED) : GunHud.VALUE;
            int vfs = fs;
            int room = x + w - Math.round(8 * u) - (bx + barW + Math.round(8 * u));
            while (vfs > 8 && font.width(val, vfs) > room) vfs--;
            font.draw(g, val, bx + barW + Math.round(8 * u), py + (fs - vfs), vfs, GunHud.a(vc, ap));
            py += lh;
        }
    }

    private static double round(double v) {
        return Math.abs(v) >= 100 ? Math.rint(v) : Math.rint(v * 100) / 100;
    }

    /** Значение характеристики с обвесом (как прислал сервер) по ключу модификатора. */
    private static double value(Proto.Stats s, String key) {
        return switch (key) {
            case "damage" -> s.damage();
            case "rpm" -> s.rpm();
            case "effective-range" -> s.damageRange();
            case "aim-inaccuracy" -> s.spreadAim();
            case "inaccuracy" -> s.spreadStand();
            case "sneak-inaccuracy" -> s.spreadSneak();
            case "recoil-pitch" -> s.recoilPitch();
            case "recoil-yaw" -> s.recoilYaw();
            case "ads" -> s.aimTime();
            case "armor-ignore" -> s.armorIgnore();
            case "head-shot" -> s.headshot();
            case "knockback" -> s.knockback();
            case "pierce" -> s.pierce();
            case "ammo-speed" -> s.speed();
            case "weight" -> s.weight();
            default -> 0;
        };
    }

    // ---------------------------------------------------------------- цвет луча ЛЦУ

    private void laser(GuiGraphics g, MonoFont font, float ap) {
        hueBar = null;
        satBar = null;
        if (selected != Att.LASER && selected != Att.LASER2 && selected != Att.GRIP) return;
        Att.Gear gear = gear();
        Att.Def d = mod.cat.def(gear.ids[selected]);
        if (d == null || d.laser() == null || !d.laser().editable()) return;
        int color = gear.laserColor(mod.cat, selected);
        if (hue < 0 || laserSlot != selected) {
            float[] hs = hsv(color);
            hue = hs[0];
            sat = hs[1];
            laserSlot = selected;
        }
        int fs = Math.max(11, Math.round(14 * u));
        int margin = Math.round(28 * u);
        int w = Math.round(360 * u), barH = Math.round(14 * u);
        int lh = font.lineHeight(fs);
        int h = Math.round(20 * u) + 2 * (lh + barH + Math.round(14 * u));
        int x = fbW - margin - w, y = fbH - margin - h - Math.round(40 * u);
        panel(g, x, y, w, h, ap);
        int px = x + Math.round(12 * u), py = y + Math.round(10 * u);
        int bw = w - Math.round(24 * u) - barH * 2 - Math.round(10 * u);
        font.draw(g, "Цвет луча — тон", px, py, fs, GunHud.a(GunHud.LABEL, ap));
        py += lh + Math.round(4 * u);
        hueBar = new Slider(px, py, bw, barH);
        int n = 48;
        for (int i = 0; i < n; i++) {
            int c = 0xFF000000 | rgb(i / (float) n, 1);
            g.fill(px + bw * i / n, py, px + bw * (i + 1) / n, py + barH, GunHud.a(c, ap));
        }
        knob(g, px + Math.round(bw * hue), py, barH, ap);
        py += barH + Math.round(14 * u);
        font.draw(g, "Насыщенность", px, py, fs, GunHud.a(GunHud.LABEL, ap));
        py += lh + Math.round(4 * u);
        satBar = new Slider(px, py, bw, barH);
        for (int i = 0; i < n; i++) {
            int c = 0xFF000000 | rgb(hue, i / (float) n);
            g.fill(px + bw * i / n, py, px + bw * (i + 1) / n, py + barH, GunHud.a(c, ap));
        }
        knob(g, px + Math.round(bw * sat), py, barH, ap);
        int sw = barH * 2;                                           // образец цвета
        int sx = x + w - Math.round(12 * u) - sw, sy = y + h / 2 - sw / 2;
        g.fill(sx, sy, sx + sw, sy + sw, GunHud.a(0xFF000000 | rgb(hue, sat), ap));
    }

    private static void knob(GuiGraphics g, int x, int y, int h, float ap) {
        g.fill(x - 2, y - 3, x + 2, y + h + 3, GunHud.a(0xFFFFFFFF, ap));
        g.fill(x - 1, y - 2, x + 1, y + h + 2, GunHud.a(0xFF000000, ap));
    }

    /** Тон h и насыщенность s (яркость 1) → RGB. */
    static int rgb(float h, float s) {
        float hh = (h - (float) Math.floor(h)) * 6, c = s, xx = c * (1 - Math.abs(hh % 2 - 1)), m = 1 - c;
        float r, g, b;
        if (hh < 1) { r = c; g = xx; b = 0; }
        else if (hh < 2) { r = xx; g = c; b = 0; }
        else if (hh < 3) { r = 0; g = c; b = xx; }
        else if (hh < 4) { r = 0; g = xx; b = c; }
        else if (hh < 5) { r = xx; g = 0; b = c; }
        else { r = c; g = 0; b = xx; }
        return (Math.round((r + m) * 255) << 16) | (Math.round((g + m) * 255) << 8) | Math.round((b + m) * 255);
    }

    /** RGB → {тон, насыщенность}. */
    static float[] hsv(int rgb) {
        float r = ((rgb >> 16) & 255) / 255f, g = ((rgb >> 8) & 255) / 255f, b = (rgb & 255) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min;
        float h = 0;
        if (d > 1e-6f) {
            if (max == r) h = ((g - b) / d) % 6;
            else if (max == g) h = (b - r) / d + 2;
            else h = (r - g) / d + 4;
            h /= 6;
            if (h < 0) h += 1;
        }
        return new float[] {h, max <= 0 ? 0 : d / max};
    }

    private void sendLaser() {
        if (laserSlot < 0) return;
        int c = rgb(hue, sat);
        Att.Gear gear = mod.ownGear();
        if (gear != null) gear.laser[laserSlot] = c;                  // сразу у себя; сервер пришлёт LOADOUT
        mod.send(Proto.laser(laserSlot, c));
    }

    // ---------------------------------------------------------------- низ: подсказка и ответ сервера

    private void footer(GuiGraphics g, MonoFont font) {
        int fs = Math.max(11, Math.round(14 * u));
        int margin = Math.round(28 * u);
        String hint = "ЛКМ — поставить, ПКМ — снять, " + keyName(mod.refitKey()) + " — закрыть";
        int y = fbH - margin - font.lineHeight(fs);
        font.draw(g, hint, (fbW - font.width(hint, fs)) / 2, y, fs, GunHud.LABEL);
        if (!msg.isEmpty() && System.nanoTime() - msgNs < 3_000_000_000L) {
            int fm = Math.max(12, Math.round(17 * u));
            int w = font.width(msg, fm);
            int my = y - font.lineHeight(fm) - Math.round(14 * u);
            g.fill((fbW - w) / 2 - 10, my - 5, (fbW + w) / 2 + 10, my + font.lineHeight(fm) + 5, 0xC0041410);
            font.draw(g, msg, (fbW - w) / 2, my, fm, msgCode == 0 ? GunHud.G : GunHud.RED);
        }
    }

    private static String keyName(InputConstants.Key k) {
        if (k == null) return "Y";
        String s = k.getDisplayName().getString();
        return s.length() > 8 ? s.substring(0, 8) : s.toUpperCase();
    }

    // ---------------------------------------------------------------- ввод

    @Override
    public boolean mouseClicked(MouseButtonEvent ev, boolean doubleClick) {
        double mx = ev.x() * gs, my = ev.y() * gs;
        if (ev.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            if (hueBar != null && hueBar.in(mx, my)) {
                drag = 1;
                hue = hueBar.at(mx);
                return true;
            }
            if (satBar != null && satBar.in(mx, my)) {
                drag = 2;
                sat = satBar.at(mx);
                return true;
            }
        }
        for (int i = buttons.size() - 1; i >= 0; i--) {
            Btn b = buttons.get(i);
            if (!b.in(mx, my)) continue;
            Runnable r = ev.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT ? b.right() : ev.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT ? b.left() : null;
            if (r != null) {
                r.run();
                return true;
            }
        }
        if (ev.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT && selected >= 0) {
            select(selected);                                        // ПКМ мимо — к общему виду
            return true;
        }
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent ev, double dx, double dy) {
        double mx = ev.x() * gs;
        if (drag == 1 && hueBar != null) hue = hueBar.at(mx);
        else if (drag == 2 && satBar != null) sat = satBar.at(mx);
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent ev) {
        if (drag != 0) {
            drag = 0;
            sendLaser();
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (sy > 0) page = Math.max(0, page - 1);
        else if (sy < 0) page++;
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        int key = e.key();
        InputConstants.Key k = mod.refitKey();
        if (key == GLFW.GLFW_KEY_ESCAPE || k != null && k.getType() == InputConstants.Type.KEYSYM && k.getValue() == key) {
            onClose();
            return true;
        }
        int[] order = order();
        if (key >= GLFW.GLFW_KEY_1 && key < GLFW.GLFW_KEY_1 + order.length) {   // 1…6 (7) — слоты по порядку справа налево
            select(order[key - GLFW.GLFW_KEY_1]);
            return true;
        }
        return super.keyPressed(e);
    }
}
