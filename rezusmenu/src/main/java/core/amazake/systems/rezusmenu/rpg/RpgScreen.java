package core.amazake.systems.rezusmenu.rpg;

import core.amazake.systems.rezusmenu.Settings;
import core.amazake.systems.rezusmenu.screen.Screen;
import core.amazake.systems.rezusmenu.util.Papi;
import core.amazake.systems.rezusmenu.util.Text;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.SkullMeta;

/**
 * RPG-меню игрока: вкладки Инвентарь / Снаряжение / Навыки / Задания, слева — персонаж, броня, уровень, полоски и
 * параметры, справа — содержимое вкладки. Инвентарь управляется: клик — выбрать предмет, клик по другой клетке —
 * переложить (сложить, поменять местами, надеть). Предметы никогда не покидают инвентарь.
 *
 * <p>Рисуется по контракту пака на холсте 512 × 288 px (левый верхний угол — (0, 0), y вниз): фон — 4 тайла
 * {@code panel_0..3}, подсветки — накладки, надписи и предметы — код. Всё — в потоке игрока.</p>
 */
public final class RpgScreen {

    private static final String STATIC = "static";
    private static final String CONTENT = "content";

    // Слои (больше — ближе к глазам).
    private static final int L_PANEL = 0;
    private static final int L_ACTIVE = 1;
    private static final int L_SELECTED = 2;
    private static final int L_FILL = 2;
    private static final int L_ICON = 5;
    private static final int L_TEXT = 6;

    // Вкладки.
    private static final int[] TAB_X = {12, 136, 260, 384};
    private static final int TAB_Y = 10;
    private static final int TAB_W = 118;
    private static final int TAB_H = 32;

    /** Колодец слота 28 × 28. */
    private static final int WELL = 28;
    private static final int[] ARMOR_Y = {74, 106, 138, 170};
    private static final int ARMOR_X = 18;
    private static final int OFFHAND_X = 172;
    private static final int OFFHAND_Y = 170;
    private static final int GRID_X = 218;
    private static final int GRID_STEP = 31;
    private static final int[] GRID_Y = {74, 105, 136};
    private static final int HOTBAR_Y = 172;

    /** Полоски: колодец x 18..200, высота 10; заливка — внутренняя часть (x BAR_X0..BAR_X1, y верх+2 .. низ−2). */
    private static final int BAR_X = 18;
    private static final int BAR_W = 182;
    private static final int BAR_H = 10;
    static final int BAR_X0 = 32;
    static final int BAR_X1 = 198;
    private static final int EXP_Y = 206;
    private static final int HP_Y = 220;
    private static final int MANA_Y = 234;

    // Правая секция.
    private static final int RIGHT_X = 216;
    private static final int RIGHT_W = 280;

    /** Броня: индекс слота инвентаря → слот снаряжения. 39 — шлем … 36 — ботинки, 40 — вторая рука. */
    private static final Map<Integer, EquipmentSlot> ARMOR = Map.of(39, EquipmentSlot.HEAD, 38, EquipmentSlot.CHEST,
        37, EquipmentSlot.LEGS, 36, EquipmentSlot.FEET);
    private static final int OFFHAND = 40;

    enum Tab { INVENTORY, EQUIPMENT, SKILLS, QUESTS }

    /** Клетка инвентаря на экране: левый верхний угол колодца (x, y). */
    private record Slot(int index, int x, int y, Screen.Button button, ItemDisplay icon, TextDisplay count) {
    }

    private final Screen screen;
    private final Player player;
    private final Settings settings;
    private final Runnable closeAction;
    private final Runnable calibrateAction;
    private final Map<Integer, Slot> slots = new HashMap<>();
    private final Map<Screen.Button, Integer> slotOf = new HashMap<>();
    private final Map<Integer, ItemStack> shown = new HashMap<>();
    private Tab tab = Tab.INVENTORY;
    private Integer picked;
    private Screen.Button lastHover;

    private Screen.Quad tabActive;
    private Screen.Quad slotSelected;
    private TextDisplay title;
    private TextDisplay expText;
    private TextDisplay hpText;
    private TextDisplay manaText;
    private Screen.Quad expFill;
    private Screen.Quad hpFill;
    private Screen.Quad manaFill;
    private TextDisplay statsText;
    private TextDisplay tooltip;
    private TextDisplay equipmentText;

    public RpgScreen(final Screen screen, final Settings settings, final Runnable closeAction,
                     final Runnable calibrateAction) {
        this.screen = screen;
        this.player = screen.player();
        this.settings = settings;
        this.closeAction = closeAction;
        this.calibrateAction = calibrateAction;
    }

    // --- постройка ---

    public void build() {
        final Screen s = this.screen;
        // Фон: 4 тайла 256 × 144.
        for (int i = 0; i < 4; i++) {
            s.quad("panel_" + i, (i % 2) * 256, (i / 2) * 144, 256, 144, L_PANEL, STATIC);
        }
        this.tabActive = s.marker("tab_active", L_ACTIVE);
        this.slotSelected = s.marker("slot_selected", L_SELECTED);

        // вкладки
        final String[] names = {"Инвентарь", "Снаряжение", "Навыки", "Задания"};
        final Material[] icons = {Material.CHEST, Material.IRON_SWORD, Material.ENCHANTED_BOOK, Material.FILLED_MAP};
        for (int i = 0; i < 4; i++) {
            final Tab t = Tab.values()[i];
            final int x = TAB_X[i];
            s.button(x, TAB_Y, TAB_W, TAB_H, "tab_hover", STATIC, b -> this.switchTab(t));
            s.item(x + 16, TAB_Y + TAB_H / 2.0, 16, new ItemStack(icons[i]), L_ICON, STATIC);
            s.text(x + 26, TAB_Y, TAB_W - 30, TAB_H, 10, Text.item("<white>" + names[i] + "</white>"), L_TEXT, STATIC);
        }

        // левая секция: персонаж
        this.title = s.text(16, 52, 186, 16, 9, Component.empty(), L_TEXT, STATIC);
        final int[] armorSlots = {39, 38, 37, 36};
        for (int i = 0; i < 4; i++) {
            this.slot(armorSlots[i], ARMOR_X, ARMOR_Y[i]);
        }
        this.slot(OFFHAND, OFFHAND_X, OFFHAND_Y);
        s.text(OFFHAND_X - 12, 154, WELL + 24, 14, 7, Text.item("<gray>Вторая рука</gray>"), L_TEXT, STATIC);
        s.item(106, 134, 64, this.head(), L_ICON, STATIC);

        // полоски
        this.expFill = s.quad("bar_exp", BAR_X0, EXP_Y + 2, BAR_X1 - BAR_X0, BAR_H - 4, L_FILL, STATIC);
        this.expText = s.text(BAR_X, EXP_Y, BAR_W, BAR_H, 8, Component.empty(), L_TEXT, STATIC);
        this.hpFill = s.quad("bar_hp", BAR_X0, HP_Y + 2, BAR_X1 - BAR_X0, BAR_H - 4, L_FILL, STATIC);
        this.hpText = s.text(BAR_X, HP_Y, BAR_W, BAR_H, 8, Component.empty(), L_TEXT, STATIC);
        this.manaFill = s.quad("bar_mana", BAR_X0, MANA_Y + 2, BAR_X1 - BAR_X0, BAR_H - 4, L_FILL, STATIC);
        this.manaText = s.text(BAR_X, MANA_Y, BAR_W, BAR_H, 8, Component.empty(), L_TEXT, STATIC);
        this.statsText = s.text(16, 248, 186, 26, 10, Component.empty(), L_TEXT, STATIC);

        // правая секция: подсказка, помощь, кнопки
        this.tooltip = s.text(RIGHT_X, 206, RIGHT_W, 14, 9, Component.empty(), L_TEXT, STATIC);
        s.text(RIGHT_X, 224, RIGHT_W, 12, 7,
            Text.item("<gray>ЛКМ — нажать · Пробел — курсор в центр · Shift — выйти</gray>"), L_TEXT, STATIC);
        s.button(218, 244, 134, 28, "button_hover", STATIC, b -> this.calibrateAction.run());
        s.text(218, 244, 134, 28, 10, Text.item("<yellow>Калибровка</yellow>"), L_TEXT, STATIC);
        s.button(360, 244, 134, 28, "button_hover", STATIC, b -> this.closeAction.run());
        s.text(360, 244, 134, 28, 10, Text.item("<white>Закрыть</white>"), L_TEXT, STATIC);

        s.arrowCursor();
        this.switchTab(Tab.INVENTORY);
    }

    private ItemStack head() {
        final ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        head.editMeta(SkullMeta.class, m -> m.setOwningPlayer(this.player));
        return head;
    }

    /** Клетка слота инвентаря (броня и вторая рука — в левой секции, всегда). (x, y) — левый верхний угол колодца. */
    private void slot(final int index, final int x, final int y) {
        final String group = index >= 36 ? STATIC : CONTENT;
        final Screen.Button b = this.screen.button(x, y, WELL, WELL, "slot_hover", group, z -> this.clickSlot(index));
        final ItemDisplay icon = this.screen.item(x + WELL / 2.0, y + WELL / 2.0, 22, null, L_ICON, group);
        final TextDisplay count = this.screen.text(x + 12, y + 18, 16, 10, 8, Component.empty(), L_TEXT, group);
        this.slots.put(index, new Slot(index, x, y, b, icon, count));
        this.slotOf.put(b, index);
        this.shown.remove(index);
    }

    // --- вкладки ---

    private void switchTab(final Tab next) {
        this.tab = next;
        this.screen.clear(CONTENT);
        this.slots.values().removeIf(s -> s.index() < 36);
        this.slotOf.values().removeIf(i -> i < 36);
        this.equipmentText = null;
        this.tabActive.place(TAB_X[next.ordinal()], TAB_Y);
        if (this.picked != null && this.picked < 36 && next != Tab.INVENTORY) {
            this.picked = null;
        }
        final Screen s = this.screen;
        switch (next) {
            case INVENTORY -> {
                this.heading("Инвентарь");
                for (int row = 0; row < 3; row++) {
                    for (int col = 0; col < 9; col++) {
                        this.slot(9 + row * 9 + col, GRID_X + col * GRID_STEP, GRID_Y[row]);
                    }
                }
                for (int col = 0; col < 9; col++) {
                    this.slot(col, GRID_X + col * GRID_STEP, HOTBAR_Y);
                }
            }
            case EQUIPMENT -> {
                this.heading("Снаряжение и характеристики");
                this.equipmentText = s.text(RIGHT_X + 4, 74, RIGHT_W - 8, 128, 9, Component.empty(), L_TEXT, CONTENT);
            }
            case SKILLS -> this.placeholderTab(Material.ENCHANTED_BOOK, "Навыки");
            case QUESTS -> this.placeholderTab(Material.FILLED_MAP, "Задания");
        }
        this.repaintSlots();
        this.refresh();
    }

    private void heading(final String name) {
        this.screen.text(RIGHT_X, 52, RIGHT_W, 16, 10, Text.item("<gold>" + name + "</gold>"), L_TEXT, CONTENT);
    }

    private void placeholderTab(final Material icon, final String name) {
        this.heading(name);
        this.screen.item(RIGHT_X + RIGHT_W / 2.0, 120, 48, new ItemStack(icon), L_ICON, CONTENT);
        this.screen.text(RIGHT_X, 160, RIGHT_W, 20, 12, Text.item("<gray>Скоро будет</gray>"), L_TEXT, CONTENT);
    }

    // --- обновление ---

    /** Такт меню: подсказка под прицелом. */
    public void tick() {
        final Screen.Button h = this.screen.hovered();
        if (h != this.lastHover) {
            this.lastHover = h;
            this.updateTooltip();
        }
    }

    /** Перерисовать предметы и значения (раз в refresh-ticks и после каждого действия). */
    public void refresh() {
        final PlayerInventory inv = this.player.getInventory();
        for (final Slot s : this.slots.values()) {
            final ItemStack item = inv.getItem(s.index());
            final ItemStack norm = item == null || item.isEmpty() ? null : item;
            if (!Objects.equals(this.shown.get(s.index()), norm)) {
                this.shown.put(s.index(), norm == null ? null : norm.clone());
                if (s.icon().isValid()) {
                    s.icon().setItemStack(norm == null ? null : norm.clone());
                }
                Screen.setText(s.count(), norm == null || norm.getAmount() <= 1 ? Component.empty()
                    : Text.item("<white>" + norm.getAmount() + "</white>"));
            }
        }
        if (this.picked != null && (inv.getItem(this.picked) == null || inv.getItem(this.picked).isEmpty())) {
            this.picked = null;
            this.repaintSlots();
        }
        this.updateStats();
        this.updateTooltip();
    }

    private void updateStats() {
        final Settings st = this.settings;
        final String level = or(Papi.value(this.player, st.phLevel()), Integer.toString(this.player.getLevel()));
        final String cls = or(Papi.value(this.player, st.phClass()), "");
        Screen.setText(this.title, Text.item("<yellow><name></yellow><gray> · <cls> <white><lvl></white> ур.</gray>",
            Placeholder.unparsed("name", this.player.getName()), Placeholder.unparsed("cls", cls),
            Placeholder.unparsed("lvl", level)));

        final double exp = number(Papi.value(this.player, st.phExp()), -1);
        final double need = number(Papi.value(this.player, st.phExpNeeded()), -1);
        final double expPart = exp >= 0 && need > 0 ? exp / need : this.player.getExp();
        this.expFill.width((BAR_X1 - BAR_X0) * clamp01(expPart));
        Screen.setText(this.expText, Text.item(exp >= 0 && need > 0
            ? "<white>Опыт " + Text.num(exp) + " / " + Text.num(need) + "</white>"
            : "<white>Опыт " + Math.round(this.player.getExp() * 100) + "%</white>"));

        final AttributeInstance maxHp = this.player.getAttribute(Attribute.MAX_HEALTH);
        final double max = maxHp == null ? 20 : maxHp.getValue();
        this.hpFill.width((BAR_X1 - BAR_X0) * clamp01(this.player.getHealth() / max));
        Screen.setText(this.hpText, Text.item("<white>Здоровье " + Text.num(Math.ceil(this.player.getHealth())) + " / "
            + Text.num(max) + "</white>"));

        final double mana = number(Papi.value(this.player, st.phMana()), -1);
        final double manaMax = number(Papi.value(this.player, st.phManaMax()), -1);
        this.manaFill.width(mana >= 0 && manaMax > 0 ? (BAR_X1 - BAR_X0) * clamp01(mana / manaMax) : 0);
        Screen.setText(this.manaText, Text.item(mana >= 0 && manaMax > 0
            ? "<white>Мана " + Text.num(mana) + " / " + Text.num(manaMax) + "</white>" : "<gray>Мана —</gray>"));

        Screen.setText(this.statsText, Text.item(this.lines(st.stats(), 2)));
        if (this.equipmentText != null) {
            Screen.setText(this.equipmentText, Text.item(this.equipmentLines()));
        }
    }

    /** Строки «название значение», по {@code perLine} на строку. */
    private String lines(final List<Settings.Stat> stats, final int perLine) {
        final StringBuilder b = new StringBuilder();
        int n = 0;
        for (final Settings.Stat stat : stats) {
            final String name = or(Papi.value(this.player, stat.name()), stat.name().contains("%") ? "?" : stat.name());
            final String value = or(Papi.value(this.player, stat.value()), stat.value().contains("%") ? "—" : stat.value());
            if (n > 0) {
                b.append(n % perLine == 0 ? "\n" : "   ");
            }
            b.append("<gray>").append(Text.escape(name)).append("</gray> <white>").append(Text.escape(value)).append("</white>");
            n++;
        }
        return b.toString();
    }

    private String equipmentLines() {
        final StringBuilder b = new StringBuilder();
        final AttributeInstance armor = this.player.getAttribute(Attribute.ARMOR);
        final AttributeInstance dmg = this.player.getAttribute(Attribute.ATTACK_DAMAGE);
        b.append("<gray>Броня (всего)</gray> <white>").append(armor == null ? "—" : Text.num(armor.getValue())).append("</white>\n");
        b.append("<gray>Урон рукой (всего)</gray> <white>").append(dmg == null ? "—" : Text.num(dmg.getValue())).append("</white>");
        for (final Settings.Stat stat : this.settings.equipmentLines()) {
            final String value = Papi.value(this.player, stat.value());
            if (value == null && stat.value().contains("%")) {
                continue;
            }
            b.append("\n<gray>").append(Text.escape(stat.name())).append("</gray> <white>")
                .append(Text.escape(value == null ? stat.value() : value)).append("</white>");
        }
        return b.toString();
    }

    private void updateTooltip() {
        final Integer index = this.lastHover == null ? null : this.slotOf.get(this.lastHover);
        Component text = Component.empty();
        if (index != null) {
            final ItemStack item = this.player.getInventory().getItem(index);
            if (item != null && !item.isEmpty()) {
                text = item.effectiveName().append(Text.item(item.getAmount() > 1 ? " <gray>×" + item.getAmount() + "</gray>" : ""));
            } else {
                text = Text.item("<dark_gray>" + slotName(index) + "</dark_gray>");
            }
        } else if (this.picked != null) {
            final ItemStack item = this.player.getInventory().getItem(this.picked);
            if (item != null && !item.isEmpty()) {
                text = Text.item("<gray>Выбрано:</gray> ").append(item.effectiveName());
            }
        }
        Screen.setText(this.tooltip, text);
    }

    private static String slotName(final int index) {
        return switch (index) {
            case 39 -> "Шлем";
            case 38 -> "Нагрудник";
            case 37 -> "Поножи";
            case 36 -> "Ботинки";
            case OFFHAND -> "Вторая рука";
            default -> index < 9 ? "Панель быстрого доступа" : "Пусто";
        };
    }

    private void repaintSlots() {
        final Slot sel = this.picked == null ? null : this.slots.get(this.picked);
        if (sel == null) {
            this.slotSelected.hide();
        } else {
            this.slotSelected.place(sel.x(), sel.y());
        }
    }

    // --- управление инвентарём ---

    private void clickSlot(final int index) {
        final PlayerInventory inv = this.player.getInventory();
        if (this.picked == null) {
            final ItemStack item = inv.getItem(index);
            if (item == null || item.isEmpty()) {
                return;
            }
            this.picked = index;
            this.sound(Sound.UI_BUTTON_CLICK);
        } else if (this.picked == index) {
            this.picked = null;
        } else {
            final String error = this.move(this.picked, index);
            if (error != null) {
                this.player.sendActionBar(Text.mm("<red>" + error + "</red>"));
                this.sound(Sound.ENTITY_VILLAGER_NO);
            } else {
                this.sound(Sound.ITEM_ARMOR_EQUIP_GENERIC);
            }
            this.picked = null;
        }
        this.repaintSlots();
        this.refresh();
    }

    /** Переложить из {@code from} в {@code to}: сложить одинаковые или поменять местами. null — получилось. */
    private String move(final int from, final int to) {
        final PlayerInventory inv = this.player.getInventory();
        final ItemStack a = inv.getItem(from);
        final ItemStack b = inv.getItem(to);
        if (a == null || a.isEmpty()) {
            return "Предмета уже нет";
        }
        if (this.stuck(from, a) || (b != null && !b.isEmpty() && this.stuck(to, b))) {
            return "Проклятие несъёмности: снять нельзя";
        }
        if (!fits(to, a)) {
            return "Этот предмет сюда не надеть";
        }
        if (b != null && !b.isEmpty() && !fits(from, b)) {
            return "Нельзя поменять местами: " + slotName(from).toLowerCase(java.util.Locale.ROOT) + " не подходит";
        }
        if (b != null && !b.isEmpty() && a.isSimilar(b) && b.getAmount() < b.getMaxStackSize()) {
            final int moved = Math.min(a.getAmount(), b.getMaxStackSize() - b.getAmount());
            b.setAmount(b.getAmount() + moved);
            a.setAmount(a.getAmount() - moved);
            inv.setItem(to, b);
            inv.setItem(from, a.getAmount() <= 0 ? null : a);
            return null;
        }
        inv.setItem(to, a);
        inv.setItem(from, b == null || b.isEmpty() ? null : b);
        return null;
    }

    /** Подходит ли предмет в слот: броню — только в свой слот. */
    private static boolean fits(final int slot, final ItemStack item) {
        if (item == null || item.isEmpty()) {
            return true;
        }
        final EquipmentSlot need = ARMOR.get(slot);
        return need == null || item.getType().getEquipmentSlot() == need;
    }

    /** Надетый предмет с проклятием несъёмности (в творческом снимать можно). */
    private boolean stuck(final int slot, final ItemStack item) {
        // Сейчас игрок — наблюдатель: решает режим, в котором он был до меню.
        return ARMOR.containsKey(slot) && this.screen.saved().mode() != GameMode.CREATIVE
            && item.containsEnchantment(Enchantment.BINDING_CURSE);
    }

    private void sound(final Sound sound) {
        this.player.playSound(this.player.getLocation(), sound, 0.6f, 1.2f);
    }

    // --- мелочи ---

    private static String or(final String value, final String fallback) {
        return value == null ? fallback : value;
    }

    private static double number(final String raw, final double fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(raw.replace(',', '.').replaceAll("[^0-9.\\-]", ""));
        } catch (final NumberFormatException e) {
            return fallback;
        }
    }

    private static double clamp01(final double v) {
        return Double.isNaN(v) ? 0 : Math.max(0, Math.min(1, v));
    }
}
