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
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.BlockDisplay;
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
 * переложить (сложить, поменять местами, надеть). Предметы никогда не покидают инвентарь, кроме кнопки «Выбросить».
 *
 * <p>Экран {@code W×H = 4.6×2.7} блока, (0, 0) — центр. Всё — в потоке игрока.</p>
 */
public final class RpgScreen {

    private static final double W = 4.6;
    private static final double H = 2.7;
    private static final String STATIC = "static";
    private static final String CONTENT = "content";

    private static final Material FRAME = Material.BROWN_CONCRETE;
    private static final Material BACK = Material.BLACK_CONCRETE;
    private static final Material SECTION = Material.GRAY_CONCRETE;
    private static final Material CELL = Material.BLACK_CONCRETE;
    private static final Material CELL_HOVER = Material.LIGHT_GRAY_CONCRETE;
    private static final Material PICKED = Material.ORANGE_CONCRETE;
    private static final Material PICKED_HOVER = Material.YELLOW_CONCRETE;
    private static final Material BTN = Material.BLACK_CONCRETE;
    private static final Material BTN_HOVER = Material.LIGHT_GRAY_CONCRETE;

    /** Броня: индекс слота инвентаря → слот снаряжения. 39 — шлем … 36 — ботинки, 40 — вторая рука. */
    private static final Map<Integer, EquipmentSlot> ARMOR = Map.of(39, EquipmentSlot.HEAD, 38, EquipmentSlot.CHEST,
        37, EquipmentSlot.LEGS, 36, EquipmentSlot.FEET);
    private static final int OFFHAND = 40;

    private static final NamespacedKey BOUND_CORE = new NamespacedKey("core-rpg", "seal_bound");
    private static final NamespacedKey BOUND_LEGACY = new NamespacedKey("magicseals", "bound_to");

    enum Tab { INVENTORY, EQUIPMENT, SKILLS, QUESTS }

    /** Клетка инвентаря на экране. */
    private record Slot(int index, Screen.Button button, ItemDisplay icon, TextDisplay count) {
    }

    private final Screen screen;
    private final Player player;
    private final Settings settings;
    private final Runnable closeAction;
    private final java.util.Set<java.util.UUID> dropping;
    private final Runnable calibrateAction;
    private final Map<Integer, Slot> slots = new HashMap<>();
    private final Map<Screen.Button, Integer> slotOf = new HashMap<>();
    private final Map<Tab, Screen.Button> tabs = new HashMap<>();
    private final Map<Integer, ItemStack> shown = new HashMap<>();
    private Tab tab = Tab.INVENTORY;
    private Integer picked;
    private Screen.Button lastHover;

    private TextDisplay title;
    private TextDisplay expText;
    private TextDisplay hpText;
    private TextDisplay manaText;
    private BlockDisplay expFill;
    private BlockDisplay hpFill;
    private BlockDisplay manaFill;
    private TextDisplay statsText;
    private TextDisplay tooltip;
    private TextDisplay equipmentText;

    private static final double BAR_U = -2.12;
    private static final double BAR_W = 1.64;

    /**
     * @param dropping игроки, которым меню сейчас само выбрасывает предмет: их событие выброса меню не отменяет
     */
    public RpgScreen(final Screen screen, final Settings settings, final Runnable closeAction,
                     final java.util.Set<java.util.UUID> dropping, final Runnable calibrateAction) {
        this.screen = screen;
        this.player = screen.player();
        this.settings = settings;
        this.closeAction = closeAction;
        this.dropping = dropping;
        this.calibrateAction = calibrateAction;
    }

    // --- постройка ---

    public void build() {
        final Screen s = this.screen;
        // Макет 4.6 × 2.7 — во весь откалиброванный экран.
        final Screen.Calibration c = s.calibration();
        s.scale(Math.min(2 * c.halfW() * 0.98 / W, 2 * c.halfH() * 0.98 / H));
        s.rect(-W / 2, -H / 2, W, H, 0, FRAME, STATIC);
        s.rect(-W / 2 + 0.05, -H / 2 + 0.05, W - 0.1, H - 0.1, 1, BACK, STATIC);

        // вкладки
        final String[] names = {"Инвентарь", "Снаряжение", "Навыки", "Задания"};
        final Material[] icons = {Material.CHEST, Material.IRON_SWORD, Material.ENCHANTED_BOOK, Material.FILLED_MAP};
        final double tabW = (4.4 - 0.15) / 4;
        for (int i = 0; i < 4; i++) {
            final Tab t = Tab.values()[i];
            final double u = -2.2 + i * (tabW + 0.05);
            final Screen.Button b = s.button(u, 0.98, tabW, 0.26, 2, BTN, BTN_HOVER, STATIC, x -> this.switchTab(t));
            this.tabs.put(t, b);
            s.item(u + 0.16, 1.11, 0.2, new ItemStack(icons[i]), 3, STATIC);
            s.text(u + 0.24, 0.98, tabW - 0.26, 0.26, 0.12, Text.item("<white>" + names[i] + "</white>"), 3, STATIC);
        }

        // левая колонка: персонаж
        s.rect(-2.2, -1.25, 1.8, 2.17, 2, SECTION, STATIC);
        this.title = s.text(-2.15, 0.66, 1.7, 0.22, 0.11, Component.empty(), 3, STATIC);
        final double[] armorV = {0.36, 0.09, -0.18, -0.45};
        final int[] armorSlots = {39, 38, 37, 36};
        for (int i = 0; i < 4; i++) {
            this.slot(armorSlots[i], -2.12, armorV[i], 0.25);
        }
        this.slot(OFFHAND, -0.72, -0.45, 0.25);
        s.text(-0.86, -0.2, 0.52, 0.2, 0.065, Text.item("<gray>Вторая\nрука</gray>"), 3, STATIC);
        s.item(-1.3, -0.02, 0.62, this.head(), 3, STATIC);

        // полоски
        this.expFill = this.bar(-0.62, Material.LIME_CONCRETE);
        this.expText = s.text(BAR_U, -0.62, BAR_W, 0.1, 0.075, Component.empty(), 5, STATIC);
        this.hpFill = this.bar(-0.76, Material.RED_CONCRETE);
        this.hpText = s.text(BAR_U, -0.76, BAR_W, 0.1, 0.075, Component.empty(), 5, STATIC);
        this.manaFill = this.bar(-0.90, Material.BLUE_CONCRETE);
        this.manaText = s.text(BAR_U, -0.90, BAR_W, 0.1, 0.075, Component.empty(), 5, STATIC);
        this.statsText = s.text(-2.15, -1.23, 1.7, 0.3, 0.09, Component.empty(), 3, STATIC);

        // правая колонка
        s.rect(-0.32, -1.25, 2.52, 2.17, 2, SECTION, STATIC);
        this.tooltip = s.text(-0.3, -0.76, 2.48, 0.2, 0.1, Component.empty(), 3, STATIC);
        s.button(-0.26, -1.1, 0.78, 0.24, 3, BTN, BTN_HOVER, STATIC, b -> this.dropPicked());
        s.text(-0.26, -1.1, 0.78, 0.24, 0.1, Text.item("<red>Выбросить</red>"), 4, STATIC);
        s.button(0.56, -1.1, 0.78, 0.24, 3, BTN, BTN_HOVER, STATIC, b -> this.calibrateAction.run());
        s.text(0.56, -1.1, 0.78, 0.24, 0.09, Text.item("<yellow>Калибровка</yellow>"), 4, STATIC);
        s.button(1.38, -1.1, 0.78, 0.24, 3, BTN, BTN_HOVER, STATIC, b -> this.closeAction.run());
        s.text(1.38, -1.1, 0.78, 0.24, 0.1, Text.item("<white>Закрыть</white>"), 4, STATIC);
        s.text(-0.3, -1.25, 2.48, 0.14, 0.06, Text.item("<gray>ЛКМ — нажать · Пробел — курсор в центр · Shift — выйти</gray>"), 3, STATIC);

        this.switchTab(Tab.INVENTORY);
        this.refresh();
    }

    private BlockDisplay bar(final double v, final Material fill) {
        this.screen.rect(BAR_U, v, BAR_W, 0.1, 3, Material.BLACK_CONCRETE, STATIC);
        return this.screen.rect(BAR_U, v, BAR_W, 0.1, 4, fill, STATIC);
    }

    private ItemStack head() {
        final ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        head.editMeta(SkullMeta.class, m -> m.setOwningPlayer(this.player));
        return head;
    }

    /** Клетка слота инвентаря (броня и вторая рука — в левой колонке, всегда). */
    private void slot(final int index, final double u, final double v, final double size) {
        final String group = index >= 36 ? STATIC : CONTENT;
        final Screen.Button b = this.screen.button(u, v, size, size, 3, CELL, CELL_HOVER, group, x -> this.clickSlot(index));
        final ItemDisplay icon = this.screen.item(u + size / 2, v + size / 2, size * 0.8, null, 4, group);
        final TextDisplay count = this.screen.text(u + size * 0.45, v, size * 0.55, size * 0.35, size * 0.3,
            Component.empty(), 5, group);
        this.slots.put(index, new Slot(index, b, icon, count));
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
        this.tabs.forEach((t, b) -> b.colors(t == next ? PICKED : BTN, t == next ? PICKED_HOVER : BTN_HOVER));
        if (this.picked != null && this.picked < 36 && next != Tab.INVENTORY) {
            this.picked = null;
        }
        final Screen s = this.screen;
        switch (next) {
            case INVENTORY -> {
                s.text(-0.3, 0.68, 2.48, 0.2, 0.12, Text.item("<gold>Инвентарь</gold>"), 3, CONTENT);
                final double cell = 0.25;
                final double step = 0.272;
                final double[] rowV = {0.40, 0.128, -0.144};
                for (int row = 0; row < 3; row++) {
                    for (int col = 0; col < 9; col++) {
                        this.slot(9 + row * 9 + col, -0.26 + col * step, rowV[row], cell);
                    }
                }
                for (int col = 0; col < 9; col++) {
                    this.slot(col, -0.26 + col * step, -0.48, cell);
                }
            }
            case EQUIPMENT -> {
                s.text(-0.3, 0.68, 2.48, 0.2, 0.12, Text.item("<gold>Снаряжение и характеристики</gold>"), 3, CONTENT);
                this.equipmentText = s.text(-0.25, -0.5, 2.38, 1.12, 0.095, Component.empty(), 3, CONTENT);
            }
            case SKILLS -> this.placeholderTab(Material.ENCHANTED_BOOK, "Навыки");
            case QUESTS -> this.placeholderTab(Material.FILLED_MAP, "Задания");
        }
        this.repaintSlots();
        this.refresh();
    }

    private void placeholderTab(final Material icon, final String name) {
        this.screen.text(-0.3, 0.68, 2.48, 0.2, 0.12, Text.item("<gold>" + name + "</gold>"), 3, CONTENT);
        this.screen.item(0.94, 0.2, 0.5, new ItemStack(icon), 3, CONTENT);
        this.screen.text(-0.3, -0.4, 2.48, 0.3, 0.13, Text.item("<gray>Скоро будет</gray>"), 3, CONTENT);
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
        this.screen.resize(this.expFill, BAR_W * clamp01(expPart), 0.1);
        Screen.setText(this.expText, Text.item(exp >= 0 && need > 0
            ? "<white>Опыт " + Text.num(exp) + " / " + Text.num(need) + "</white>"
            : "<white>Опыт " + Math.round(this.player.getExp() * 100) + "%</white>"));

        final AttributeInstance maxHp = this.player.getAttribute(Attribute.MAX_HEALTH);
        final double max = maxHp == null ? 20 : maxHp.getValue();
        this.screen.resize(this.hpFill, BAR_W * clamp01(this.player.getHealth() / max), 0.1);
        Screen.setText(this.hpText, Text.item("<white>Здоровье " + Text.num(Math.ceil(this.player.getHealth())) + " / "
            + Text.num(max) + "</white>"));

        final double mana = number(Papi.value(this.player, st.phMana()), -1);
        final double manaMax = number(Papi.value(this.player, st.phManaMax()), -1);
        this.screen.resize(this.manaFill, mana >= 0 && manaMax > 0 ? BAR_W * clamp01(mana / manaMax) : 0.0001, 0.1);
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
        for (final Slot s : this.slots.values()) {
            final boolean sel = this.picked != null && this.picked == s.index();
            s.button().colors(sel ? PICKED : CELL, sel ? PICKED_HOVER : CELL_HOVER);
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
        return ARMOR.containsKey(slot) && this.player.getGameMode() != GameMode.CREATIVE
            && item.containsEnchantment(Enchantment.BINDING_CURSE);
    }

    private void dropPicked() {
        if (this.picked == null) {
            this.player.sendActionBar(Text.mm("<gray>Сначала выберите предмет</gray>"));
            return;
        }
        final PlayerInventory inv = this.player.getInventory();
        final ItemStack item = inv.getItem(this.picked);
        if (item == null || item.isEmpty()) {
            this.picked = null;
            this.refresh();
            return;
        }
        if (bound(item)) {
            this.player.sendActionBar(Text.mm("<red>Персональный предмет нельзя выбросить</red>"));
            this.sound(Sound.ENTITY_VILLAGER_NO);
            return;
        }
        if (this.stuck(this.picked, item)) {
            this.player.sendActionBar(Text.mm("<red>Проклятие несъёмности: снять нельзя</red>"));
            return;
        }
        // Как клавиша Q: бросок вперёд с задержкой подбора и событием выброса (его могут запретить другие плагины).
        final org.bukkit.entity.Item dropped;
        this.dropping.add(this.player.getUniqueId());
        try {
            dropped = this.player.dropItem(this.picked, item.getAmount(), false, null);
        } finally {
            this.dropping.remove(this.player.getUniqueId());
        }
        if (dropped == null) {
            this.player.sendActionBar(Text.mm("<red>Этот предмет нельзя выбросить</red>"));
            this.sound(Sound.ENTITY_VILLAGER_NO);
            return;
        }
        this.picked = null;
        this.sound(Sound.ENTITY_ITEM_PICKUP);
        this.repaintSlots();
        this.refresh();
    }

    private static boolean bound(final ItemStack item) {
        if (!item.hasItemMeta()) {
            return false;
        }
        final var pdc = item.getItemMeta().getPersistentDataContainer();
        return pdc.has(BOUND_CORE) || pdc.has(BOUND_LEGACY);
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
