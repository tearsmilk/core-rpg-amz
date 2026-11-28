package ink.hoka.odyssey.guns;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.entity.Player;
import net.minestom.server.inventory.PlayerInventory;
import net.minestom.server.item.ItemStack;

/**
 * Установка и снятие обвеса (экран обвеса клиентского модуля на Y, как экран переделки TACZ; и команды /hogun
 * attach). Всё проверяет сервер: оружие в основной руке (тот же предмет, что у сервера), не перезаряжается, слот есть
 * у оружия и не заперт (AWP: прицел — часть винтовки), обвес этого слота и подходит оружию (список allow оружия, его
 * встроенный или разрешён планкой), предмет-обвес правда лежит в указанном слоте инвентаря; не чаще 10 действий в
 * секунду. Старый обвес — в тот же слот инвентаря (как TACZ), снятый — в свободный (нет места — не снимается).
 */
final class Refit {
    private static final int INVENTORY = 36;
    private static final double RATE = 10;

    private final GunsModule mod;

    Refit(GunsModule mod) {
        this.mod = mod;
    }

    /** Итог: код REFIT_* и подсказка. */
    record Outcome(int code, String message) {
        static final Outcome OK = new Outcome(Proto.REFIT_OK, "");

        boolean ok() {
            return code == Proto.REFIT_OK;
        }
    }

    /** Сообщение клиентского модуля (в тике сервера). */
    void handle(Player p, Proto.Msg m) {
        ShooterState st = mod.state(p);
        if (m.op() == Proto.C_REFIT) {
            mod.syncHeld(p);
            boolean open = m.mode() != 0 && st.gun != null;
            st.refit = open;
            if (open) {
                mod.endAim(p);
            }
            return;
        }
        long now = System.currentTimeMillis();
        Outcome r;
        if (!st.refitBucket.take(now, RATE, RATE)) {
            r = new Outcome(Proto.REFIT_RATE, "Не так быстро");
        } else {
            Attachments.Slot slot = Attachments.Slot.of(m.slot());
            r = switch (m.op()) {
                case Proto.C_ATTACH -> attach(p, slot, m.arg());
                case Proto.C_DETACH -> detach(p, slot, false);
                case Proto.C_BUILTIN -> builtin(p, slot, m.str());
                case Proto.C_ADAPTER -> adapter(p, slot, m.str());
                case Proto.C_LASER -> laser(p, slot, m.arg());
                case Proto.C_ZOOM -> zoom(p, m.mode());
                case Proto.C_PAINT -> paint(p, m.mode());
                default -> new Outcome(Proto.REFIT_NOT_ALLOWED, "");
            };
        }
        mod.links().send(p, Proto.refitResult(r.code(), r.message()));
    }

    /** Общие проверки: оружие в руке (тот же предмет), не перезаряжается. null — можно. */
    private Outcome held(Player p, ShooterState st) {
        mod.syncHeld(p);
        if (st.base == null || st.slot != p.getHeldSlot()) {
            return new Outcome(Proto.REFIT_NOT_HELD, "Возьмите оружие в руку");
        }
        if (st.serial == null || !st.serial.equals(p.getItemInMainHand().getTag(GunItems.SERIAL))) {
            return new Outcome(Proto.REFIT_NOT_HELD, "Возьмите оружие в руку");
        }
        if (st.reloading()) {
            return new Outcome(Proto.REFIT_BUSY, "Нельзя во время перезарядки");
        }
        return null;
    }

    private Outcome slotOk(ShooterState st, Attachments.GunAtt ga, Attachments.Slot slot) {
        if (slot == null || !ga.has(slot)) {
            return new Outcome(Proto.REFIT_NOT_ALLOWED, "У " + st.base.name() + " нет такого слота");
        }
        if (ga.locked(slot)) {
            return new Outcome(Proto.REFIT_LOCKED, lockedText(st, ga, slot));
        }
        return null;
    }

    /**
     * Почему слот заперт: прицел оружия с оптикой (AWP) — «Прицел 8x — часть винтовки…», встроенный обвес по умолчанию
     * (ВССК: глушитель) — «Глушитель — часть винтовки, не снимается», иначе — слот не меняется.
     */
    private String lockedText(ShooterState st, Attachments.GunAtt ga, Attachments.Slot slot) {
        if (slot == Attachments.Slot.SCOPE && st.base.scope()) {
            return "Прицел " + fmt(st.base.zoom()) + "x — часть винтовки, не снимается";
        }
        Loadout.Entry e = st.loadout == null ? null : st.loadout.get(slot);
        Attachments.Def d = mod.catalog().get(e != null ? e.id() : ga.defaults().get(slot));
        if (d != null && d.builtin()) {
            return d.name() + " — часть винтовки, не снимается";
        }
        return "Этот слот у " + st.base.name() + " не меняется";
    }

    /** Поставить обвес из слота инвентаря invSlot. */
    Outcome attach(Player p, Attachments.Slot slot, int invSlot) {
        ShooterState st = mod.state(p);
        Outcome bad = held(p, st);
        if (bad != null) {
            return bad;
        }
        Attachments.GunAtt ga = mod.gunAtt(st.base.id());
        bad = slotOk(st, ga, slot);
        if (bad != null) {
            return bad;
        }
        PlayerInventory inv = p.getInventory();
        if (invSlot < 0 || invSlot >= INVENTORY || invSlot == st.slot) {
            return new Outcome(Proto.REFIT_NO_ITEM, "Нет такого обвеса в инвентаре");
        }
        ItemStack item = inv.getItemStack(invSlot);
        Attachments cat = mod.catalog();
        String id = mod.items().attachmentId(item, cat);
        Attachments.Def d = cat.get(id);
        if (d == null) {
            return new Outcome(Proto.REFIT_NO_ITEM, "Нет такого обвеса в инвентаре");
        }
        if (!slot.accepts(d.slot()) || d.builtin()) {
            return new Outcome(Proto.REFIT_NOT_ALLOWED, d.name() + " — не " + Attachments.slotRu(slot));
        }
        Loadout lo = st.loadout;
        if (!cat.allowed(ga, d, lo.adapter, slot)) {
            return new Outcome(Proto.REFIT_NOT_ALLOWED, d.name() + " не подходит к " + st.base.name());
        }
        Loadout.Entry old = lo.get(slot);
        Integer lc = item.getTag(Loadout.ITEM_LASER);
        ItemStack back = old == null || old.virtual() ? ItemStack.AIR : oldItem(old);
        if (item.amount() > 1) {                              // из стопки — один, старый обвес — в другой слот
            if (!back.isAir() && free(inv) < 0) {
                return new Outcome(Proto.REFIT_NO_SPACE, "В инвентаре нет места для снятого обвеса");
            }
            inv.setItemStack(invSlot, item.withAmount(item.amount() - 1));
            if (!back.isAir()) {
                inv.setItemStack(free(inv), back);
            }
        } else {
            inv.setItemStack(invSlot, back);
        }
        mod.setLoadout(p, st, lo.with(slot, new Loadout.Entry(d.id(), false, lc == null ? -1 : lc & 0xFFFFFF)));
        sound(p, d.soundInstall());
        return Outcome.OK;
    }

    /** Снять обвес слота (admin — без проверки «в руке ли тот же предмет» нет: всё так же; предмет — в инвентарь). */
    Outcome detach(Player p, Attachments.Slot slot, boolean admin) {
        ShooterState st = mod.state(p);
        Outcome bad = held(p, st);
        if (bad != null) {
            return bad;
        }
        Attachments.GunAtt ga = mod.gunAtt(st.base.id());
        if (slot == null) {
            return new Outcome(Proto.REFIT_NOT_ALLOWED, "Нет такого слота");
        }
        if (!admin && ga.locked(slot)) {
            return slotOk(st, ga, slot);
        }
        Loadout lo = st.loadout;
        Loadout.Entry old = lo.get(slot);
        if (old == null) {
            return new Outcome(Proto.REFIT_OK, "Слот пуст");
        }
        Attachments.Def d = mod.catalog().get(old.id());
        if (!old.virtual()) {
            PlayerInventory inv = p.getInventory();
            int f = free(inv);
            if (f < 0) {
                return new Outcome(Proto.REFIT_NO_SPACE, "В инвентаре нет места для снятия обвеса");
            }
            inv.setItemStack(f, oldItem(old));
        }
        mod.setLoadout(p, st, lo.with(slot, null));
        sound(p, d == null ? "" : d.soundUninstall());
        return Outcome.OK;
    }

    /** Поставить встроенный обвес оружия (без предмета). */
    Outcome builtin(Player p, Attachments.Slot slot, String id) {
        ShooterState st = mod.state(p);
        Outcome bad = held(p, st);
        if (bad != null) {
            return bad;
        }
        Attachments.GunAtt ga = mod.gunAtt(st.base.id());
        bad = slotOk(st, ga, slot);
        if (bad != null) {
            return bad;
        }
        Attachments.Def d = mod.catalog().get(id);
        if (d == null || !d.builtin() || !slot.accepts(d.slot()) || !ga.builtinIn(slot, id)) {
            return new Outcome(Proto.REFIT_NOT_ALLOWED, "Такого встроенного обвеса у " + st.base.name() + " нет");
        }
        Loadout lo = st.loadout;
        Loadout.Entry old = lo.get(slot);
        if (old != null && !old.virtual()) {
            PlayerInventory inv = p.getInventory();
            int f = free(inv);
            if (f < 0) {
                return new Outcome(Proto.REFIT_NO_SPACE, "В инвентаре нет места для снятия обвеса");
            }
            inv.setItemStack(f, oldItem(old));
        }
        mod.setLoadout(p, st, lo.with(slot, new Loadout.Entry(d.id(), true, -1)));
        sound(p, d.soundInstall());
        return Outcome.OK;
    }

    /** Повышающая планка в слоте (id «» — убрать). Нельзя, если стоящий прицел с новой планкой не подходит. */
    Outcome adapter(Player p, Attachments.Slot slot, String id) {
        ShooterState st = mod.state(p);
        Outcome bad = held(p, st);
        if (bad != null) {
            return bad;
        }
        Attachments.GunAtt ga = mod.gunAtt(st.base.id());
        bad = slotOk(st, ga, slot);
        if (bad != null) {
            return bad;
        }
        Attachments cat = mod.catalog();
        String to = id == null ? "" : id;
        if (!to.isEmpty()) {
            Attachments.Adapter a = cat.adapter(to);
            if (a == null || a.slot() != slot || !ga.adapters().contains(to)) {
                return new Outcome(Proto.REFIT_NOT_ALLOWED, "Эта планка не подходит к " + st.base.name());
            }
        }
        Loadout lo = st.loadout;
        Loadout.Entry cur = lo.get(slot);
        if (cur != null && !cat.allowed(ga, cat.get(cur.id()), to, slot)) {
            return new Outcome(Proto.REFIT_ADAPTER, "Снимите прицел, чтобы сменить планку");
        }
        mod.setLoadout(p, st, lo.withAdapter(to));
        sound(p, "gun_att_attachment_general_a");
        return Outcome.OK;
    }

    /** Цвет луча ЛЦУ в слоте (если у него цвет меняется). */
    Outcome laser(Player p, Attachments.Slot slot, int rgb) {
        ShooterState st = mod.state(p);
        Outcome bad = held(p, st);
        if (bad != null) {
            return bad;
        }
        Loadout.Entry e = slot == null ? null : st.loadout.get(slot);
        Attachments.Def d = e == null ? null : mod.catalog().get(e.id());
        if (d == null || !d.laser() || !d.laserEditable()) {
            return new Outcome(Proto.REFIT_NOT_ALLOWED, "Цвет этого ЛЦУ не меняется");
        }
        mod.setLoadout(p, st, st.loadout.with(slot, new Loadout.Entry(e.id(), e.virtual(), rgb & 0xFFFFFF))
                .withZoom(st.loadout.zoom));
        return Outcome.OK;
    }

    /** Кратность прицела (номер; по кругу). */
    Outcome zoom(Player p, int level) {
        ShooterState st = mod.state(p);
        mod.syncHeld(p);
        if (st.base == null) {
            return new Outcome(Proto.REFIT_NOT_HELD, "Возьмите оружие в руку");
        }
        Loadout.Entry e = st.loadout.get(Attachments.Slot.SCOPE);
        Attachments.Def d = e == null ? null : mod.catalog().get(e.id());
        if (d == null || d.zoom().length < 2 || mod.gunAtt(st.base.id()).locked(Attachments.Slot.SCOPE)) {
            return new Outcome(Proto.REFIT_NOT_ALLOWED, "У прицела одна кратность");
        }
        mod.setLoadout(p, st, st.loadout.withZoom(Math.floorMod(level, d.zoom().length)));
        return new Outcome(Proto.REFIT_OK, "Кратность " + fmt(d.zoomAt(st.loadout.zoom)) + "x");
    }

    /**
     * Окраска оружия в руке (номер {@link Loadout#PAINTS}): только у оружия с attachments.paint (своя текстура
     * «чёрный металл» в модели), в руке и не во время перезарядки.
     */
    Outcome paint(Player p, int finish) {
        ShooterState st = mod.state(p);
        Outcome bad = held(p, st);
        if (bad != null) {
            return bad;
        }
        if (!mod.gunAtt(st.base.id()).paint()) {
            return new Outcome(Proto.REFIT_NOT_ALLOWED, st.base.name() + " не красится");
        }
        if (finish < 0 || finish >= Loadout.PAINTS.length) {
            return new Outcome(Proto.REFIT_NOT_ALLOWED, "Нет такой окраски");
        }
        if (st.loadout.paint != finish) {
            mod.setLoadout(p, st, st.loadout.withPaint(finish));
            sound(p, "gun_att_attachment_general_a");
        }
        return new Outcome(Proto.REFIT_OK, "Окраска: " + Loadout.paintName(finish));
    }

    // ---------------------------------------------------------------- для /hogun attach

    /** Поставить обвес id в слот его вида без предмета (оператор): проверки совместимости — те же. */
    Outcome adminSet(Player p, String id) {
        ShooterState st = mod.state(p);
        Outcome bad = held(p, st);
        if (bad != null) {
            return bad;
        }
        Attachments cat = mod.catalog();
        Attachments.Def d = cat.get(id);
        if (d == null) {
            return new Outcome(Proto.REFIT_NO_ITEM, "Нет обвеса «" + id + "»");
        }
        Attachments.GunAtt ga = mod.gunAtt(st.base.id());
        if (!ga.has(d.slot())) {
            return new Outcome(Proto.REFIT_NOT_ALLOWED, "У " + st.base.name() + " нет слота «" + d.slot().ru + "»");
        }
        if (!cat.allowed(ga, d, st.loadout.adapter)) {
            return new Outcome(Proto.REFIT_NOT_ALLOWED, d.name() + " не подходит к " + st.base.name());
        }
        Attachments.Slot to = d.slot();                   // первый ЛЦУ занят, второй свободен — во второй
        if (to == Attachments.Slot.LASER && st.loadout.get(to) != null && ga.has(Attachments.Slot.LASER2)
                && st.loadout.get(Attachments.Slot.LASER2) == null) {
            to = Attachments.Slot.LASER2;
        }
        Loadout.Entry old = st.loadout.get(to);
        if (old != null && !old.virtual()) {
            mod.giveOrDrop(p, oldItem(old));
        }
        mod.setLoadout(p, st, st.loadout.with(to, new Loadout.Entry(d.id(), d.builtin(), -1)));
        return Outcome.OK;
    }

    /** Снять весь обвес (предметы — в инвентарь или под ноги). */
    Outcome adminClear(Player p) {
        ShooterState st = mod.state(p);
        Outcome bad = held(p, st);
        if (bad != null) {
            return bad;
        }
        Loadout lo = st.loadout;
        for (var e : lo.entries().entrySet()) {
            if (!e.getValue().virtual()) {
                mod.giveOrDrop(p, oldItem(e.getValue()));
            }
        }
        Loadout empty = Loadout.EMPTY.withInit().withPaint(lo.paint);
        mod.setLoadout(p, st, empty);
        return Outcome.OK;
    }

    // ----------------------------------------------------------------

    private ItemStack oldItem(Loadout.Entry e) {
        Attachments.Def d = mod.catalog().get(e.id());
        if (d == null) {
            return ItemStack.AIR;
        }
        return mod.items().attachment(d, e.laser());
    }

    /** Свободный слот инвентаря (0–35) или −1. */
    private static int free(PlayerInventory inv) {
        for (int i = 0; i < INVENTORY; i++) {
            if (inv.getItemStack(i).isAir()) {
                return i;
            }
        }
        return -1;
    }

    /** Звук установки/снятия: остальным рядом (свой клиент с модулем играет сам), без модуля — и ему. */
    private void sound(Player p, String id) {
        if (id == null || id.isEmpty()) {
            return;
        }
        mod.effects().others(p, id, 0.7f);
        if (!mod.modded(p)) {
            mod.links().play(p, id, p.getPosition(), 0.8f, 1f);
        }
    }

    static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.format(java.util.Locale.ROOT, "%.2f", d)
                .replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    /** Сообщение итога в чат (для команд). */
    static Component text(Outcome o, String okText) {
        return o.ok() ? Component.text(o.message().isEmpty() ? okText : o.message(), NamedTextColor.GREEN)
                : Component.text(o.message(), NamedTextColor.RED);
    }
}
