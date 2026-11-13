package core.amazake.systems.corerpg.sharpen;

import core.amazake.systems.api.SystemContext;
import core.amazake.systems.corerpg.util.Gear;
import core.amazake.systems.corerpg.util.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Меню заточки на 45 слотов (как в плагине Zatochka): предмет из руки (только показ), слоты камня и руны, в режиме RQ
 * — слот жертвы для +11, превью шансов, кнопки «Улучшить» и «Закрыть», подсказки. Расходники возвращаются при
 * закрытии; после попытки меню переоткрывается с остатками в слотах.
 *
 * <p>Потоки: всё меню игрока живёт в его потоке (его события и {@code runOnceForPlayer}). Общий только список
 * открытых меню — {@link ConcurrentHashMap}; в {@link #closeAll()} каждое меню закрывается задачей в потоке игрока.</p>
 */
final class SharpenMenus implements Listener {

    static final int SIZE = 45;
    static final int SLOT_HELP = 0;
    static final int SLOT_ITEM = 10;
    static final int SLOT_STONE = 13;
    static final int SLOT_STONE_HINT = 14;
    static final int SLOT_RUNE = 16;
    static final int SLOT_RUNE_HINT = 17;
    static final int SLOT_ARROW = 22;
    static final int SLOT_SACRIFICE = 28;
    static final int SLOT_SACRIFICE_HINT = 29;
    static final int SLOT_PREVIEW = 31;
    static final int SLOT_CONFIRM = 39;
    static final int SLOT_COST = 40;
    static final int SLOT_CLOSE = 41;
    private static final int[] ITEM_FRAME = {1, 2, 3, 9, 11, 18, 19, 20};
    private static final int[] STONE_FRAME = {4, 5, 6, 12, 15, 21, 23, 24, 25};
    private static final int[] SACRIFICE_FRAME = {27, 36, 37, 38};

    /** Открытое меню одного игрока. Поля меняет только поток игрока. */
    static final class Holder implements InventoryHolder {
        final UUID owner;
        final SharpenSettings.Mode mode;
        private Inventory inventory;
        /** Попытка сделана, ждём переоткрытия: клики игнорируются. */
        volatile boolean busy;
        /** Закрытие без возврата: содержимое слотов уже перенесено в новое меню или отдано игроку. */
        volatile boolean skipReturn;

        Holder(final UUID owner, final SharpenSettings.Mode mode) {
            this.owner = owner;
            this.mode = mode;
        }

        @Override
        public Inventory getInventory() {
            return this.inventory;
        }

        boolean sacrificeEnabled() {
            return this.mode == SharpenSettings.Mode.ROYAL_QUEST;
        }

        boolean isInput(final int slot) {
            return slot == SLOT_STONE || slot == SLOT_RUNE || (slot == SLOT_SACRIFICE && this.sacrificeEnabled());
        }
    }

    private final SharpenModule module;
    private final SystemContext context;
    private final Map<UUID, Holder> open = new ConcurrentHashMap<>();

    SharpenMenus(final SharpenModule module, final SystemContext context) {
        this.module = module;
        this.context = context;
    }

    private SharpenSettings settings() {
        return this.module.settings();
    }

    private SharpenItems items() {
        return this.module.items();
    }

    // ------------------------------------------------------------------ открытие и отрисовка

    /** Открыть меню. Поток игрока. */
    void open(final Player player) {
        this.open(player, null, null, null);
    }

    private void open(final Player player, final ItemStack stone, final ItemStack rune, final ItemStack sacrifice) {
        final ItemStack hand = player.getInventory().getItemInMainHand();
        if (this.items().migrate(hand)) {
            player.getInventory().setItemInMainHand(hand);
        }
        final SharpenSettings s = this.settings();
        final Holder holder = new Holder(player.getUniqueId(), s.mode());
        final Inventory inv = Bukkit.createInventory(holder, SIZE, Text.mm(s.menuTitle()));
        holder.inventory = inv;

        final ItemStack pane = pane(Material.GRAY_STAINED_GLASS_PANE);
        final ItemStack dark = pane(Material.BLACK_STAINED_GLASS_PANE);
        for (int i = 0; i < SIZE; i++) {
            inv.setItem(i, pane);
        }
        for (final int slot : ITEM_FRAME) {
            inv.setItem(slot, dark);
        }
        for (final int slot : STONE_FRAME) {
            inv.setItem(slot, dark);
        }
        for (final int slot : SACRIFICE_FRAME) {
            inv.setItem(slot, dark);
        }
        inv.setItem(SLOT_STONE, stone);
        inv.setItem(SLOT_RUNE, rune);
        inv.setItem(SLOT_STONE_HINT, named(Material.YELLOW_STAINED_GLASS_PANE, "<yellow>← Слот камня</yellow>", List.of(
            "<gray>Положите камень заточки:",
            "<white>• Осколок / Крошка",
            "<white>• Ашкалотский кубик",
            "<white>• Чёрный / Белый / Красный / Пурпурный")));
        inv.setItem(SLOT_RUNE_HINT, named(Material.CYAN_STAINED_GLASS_PANE, "<aqua>← Слот руны</aqua>", List.of(
            "<gray>Опционально: руна защиты",
            "<white>• Руна Сохранения",
            "<white>• Руна Холода",
            "",
            "<gray>При неудаче уровень не падает",
            "<gray>и предмет не уничтожается.")));
        if (holder.sacrificeEnabled()) {
            inv.setItem(SLOT_SACRIFICE, sacrifice);
            final List<String> lore = new ArrayList<>(List.of(
                "<gray>Только для грейдов <white>B</white> и <white>A</white> с <yellow>+10</yellow>.",
                "<gray>Положите второй предмет",
                "<gray>того же материала. Камень не нужен.",
                "",
                "<gray>Шанс зависит от заточки жертвы:"));
            for (int lvl = 0; lvl <= 10; lvl++) {
                lore.add("<white>  +" + lvl + " → <green>" + SharpenRules.percent(s.legendaryChance(lvl)) + "%</green>");
            }
            lore.add("");
            lore.add("<red>Жертва тратится всегда; при провале остаётся +10.");
            lore.add("<gold>Надетый предмет +11: +" + s.legendaryStat() + " ко всем параметрам.");
            inv.setItem(SLOT_SACRIFICE_HINT, named(Material.ORANGE_STAINED_GLASS_PANE, "<gold>← Слот жертвы (+11)</gold>", lore));
        } else {
            inv.setItem(SLOT_SACRIFICE, dark);
            if (sacrifice != null) {
                giveBack(player, sacrifice);
            }
        }
        inv.setItem(SLOT_ARROW, named(Material.LIME_DYE, "<green>↓ Результат</green>", List.of("<gray>Превью после выбора камня")));
        inv.setItem(SLOT_CONFIRM, named(Material.LIME_CONCRETE, "<green><bold>Улучшить</bold></green>", List.of(
            "<gray>Нажмите, чтобы заточить предмет",
            "<yellow>Камень и руна будут потрачены")));
        inv.setItem(SLOT_CLOSE, named(Material.RED_CONCRETE, "<red><bold>Закрыть</bold></red>", List.of(
            "<gray>Вернуть предметы и закрыть")));
        inv.setItem(SLOT_COST, named(Material.GOLD_NUGGET, "<gold>Стоимость</gold>", List.of(
            "<gray>В этой версии золото не требуется.",
            "<gray>Расходуются только камень и руна.")));
        inv.setItem(SLOT_HELP, named(Material.BOOK, "<aqua>Как пользоваться</aqua>", List.of(
            "<gray>1. Держите предмет в основной руке",
            "<gray>2. Откройте это меню",
            "<gray>3. Положите <yellow>камень</yellow> в пустой слот",
            "<gray>4. (Опц.) положите <aqua>руну</aqua>",
            "<gray>5. Нажмите <green>Улучшить</green>",
            "",
            "<gray>Для <gold>+11</gold> (грейды B и A) вместо камня",
            "<gray>нужен второй такой же предмет в слоте жертвы.")));
        this.render(player, holder);

        this.open.put(player.getUniqueId(), holder);
        final InventoryView view = player.openInventory(inv);
        if (view == null || view.getTopInventory().getHolder(false) != holder) {
            // Открытие отменил другой плагин: вернуть то, что успели положить.
            this.open.remove(player.getUniqueId(), holder);
            holder.skipReturn = true;
            this.returnInputs(player, holder);
        }
    }

    /** Обновить показ предмета из руки и превью. Поток игрока. */
    void render(final Player player, final Holder holder) {
        final Inventory inv = holder.inventory;
        final SharpenSettings s = this.settings();
        final ItemStack hand = player.getInventory().getItemInMainHand();
        final Gear.Kind kind = Gear.kind(hand);
        if (kind == null) {
            inv.setItem(SLOT_ITEM, named(Material.BARRIER, "<red>Возьмите предмет в руку</red>", List.of(
                "<gray>Оружие или броню нужно держать", "<gray>в основной руке перед открытием меню.")));
            inv.setItem(SLOT_PREVIEW, named(Material.BARRIER, "<red>Нет предмета</red>", List.of("<gray>Возьмите оружие/броню в руку")));
            return;
        }
        final int current = this.items().level(hand);
        final Gear.Grade grade = Gear.grade(hand);
        final int max = s.maxLevel(grade);

        final ItemStack display = hand.clone();
        display.setAmount(1);
        display.editMeta(meta -> {
            final List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            lore.add(Component.empty());
            lore.add(Text.item("<gold>Текущая заточка: <yellow>+" + current + "</yellow> <gray>/ +" + max
                + " (грейд " + grade + ")</gray>"));
            meta.lore(lore);
        });
        inv.setItem(SLOT_ITEM, display);

        final Consumable stone = this.stoneIn(inv);
        final Consumable rune = this.runeIn(inv);
        final int sacrificeLevel = holder.sacrificeEnabled() ? this.sacrificeLevel(hand, inv.getItem(SLOT_SACRIFICE)) : -1;
        final boolean legendary = SharpenRules.needsSacrifice(s, grade, current);

        if (current >= max) {
            inv.setItem(SLOT_PREVIEW, named(Material.BARRIER, "<red>Максимальный уровень</red>", List.of("<gray>Предмет уже +" + max)));
            return;
        }
        if (!legendary && stone == null) {
            inv.setItem(SLOT_PREVIEW, named(Material.GRAY_STAINED_GLASS_PANE, "<gray>Превью результата</gray>", List.of(
                "<gray>Текущий уровень: <yellow>+" + current + "</yellow>",
                "<gray>Максимум: <yellow>+" + max + "</yellow>",
                "",
                "<yellow>Положите камень заточки</yellow>")));
            return;
        }
        if (legendary && sacrificeLevel < 0) {
            inv.setItem(SLOT_PREVIEW, named(Material.GRAY_STAINED_GLASS_PANE, "<gray>Превью результата</gray>", List.of(
                "<gray>Текущий уровень: <yellow>+" + current + "</yellow>",
                "<gray>Максимум: <yellow>+" + max + "</yellow>",
                "",
                "<gold>Для +" + (current + 1) + " положите в слот жертвы</gold>",
                "<gold>второй предмет: " + hand.getType().name().toLowerCase(java.util.Locale.ROOT) + "</gold>")));
            return;
        }
        final SharpenRules.Check check = SharpenRules.plan(s, kind.sharpenCategory(), grade, current, stone, rune, sacrificeLevel);
        if (!check.ok()) {
            inv.setItem(SLOT_PREVIEW, named(Material.BARRIER, "<red>Нельзя заточить</red>", List.of(check.error())));
            return;
        }
        final SharpenRules.Plan plan = check.plan();
        final double bonus = s.bonus(kind.attacks(), plan.target());
        final List<String> lore = new ArrayList<>();
        lore.add("<gold>→ Заточка <yellow>+" + plan.target() + "</yellow>");
        lore.add("");
        lore.add("<green>Шанс успеха: <white>" + SharpenRules.percent(plan.success()) + "%</white></green>");
        if (plan.type() == SharpenRules.Type.LEGENDARY) {
            lore.add("<gray>Жертва: заточка <yellow>+" + plan.sacrificeLevel() + "</yellow>");
            lore.add("<red>Жертва тратится всегда</red>");
            lore.add("<aqua>При провале остаётся +" + plan.current() + "</aqua>");
        } else {
            if (plan.noDown()) {
                lore.add("<aqua>Руна: уровень сохранится при провале</aqua>");
            } else {
                lore.add("<red>При провале: уровень −1</red>");
            }
            if (plan.noDestroy()) {
                lore.add("<aqua>Предмет не будет уничтожен</aqua>");
            } else {
                lore.add("<dark_red>Шанс уничтожения: <white>" + SharpenRules.percent(plan.destroy()) + "%</white></dark_red>");
            }
        }
        lore.add("");
        lore.add("<gray>" + (kind.attacks() ? "Атака" : "Защита") + " бонус на +" + plan.target() + ": <yellow>+"
            + Text.num(bonus) + "%</yellow>");
        if (plan.target() >= 11) {
            lore.add("<gold>+" + s.legendaryStat() + " ко всем параметрам, пока предмет надет</gold>");
        }
        if (plan.stone() != null || plan.rune() != null) {
            lore.add("");
        }
        if (plan.stone() != null) {
            lore.add("<white>Камень: <yellow>" + Text.escape(this.items().name(plan.stone())) + "</yellow>");
        }
        if (plan.rune() != null) {
            lore.add("<white>Руна: <aqua>" + Text.escape(this.items().name(plan.rune())) + "</aqua>");
        }
        final ItemStack preview = hand.clone();
        preview.setAmount(1);
        preview.editMeta(meta -> {
            meta.displayName(Text.item("<yellow>+" + plan.target() + " <white>превью</white>"));
            meta.lore(Text.lore(lore));
        });
        inv.setItem(SLOT_PREVIEW, preview);
    }

    private Consumable stoneIn(final Inventory inv) {
        final Consumable c = this.items().consumable(inv.getItem(SLOT_STONE));
        return c != null && c.isStone() ? c : null;
    }

    private Consumable runeIn(final Inventory inv) {
        final Consumable c = this.items().consumable(inv.getItem(SLOT_RUNE));
        return c != null && c.isRune() ? c : null;
    }

    /** Заточка жертвы, если она подходит к предмету в руке, иначе −1. */
    private int sacrificeLevel(final ItemStack hand, final ItemStack sacrifice) {
        if (!this.isSacrificeFor(hand, sacrifice)) {
            return -1;
        }
        return this.items().level(sacrifice);
    }

    private boolean isSacrificeFor(final ItemStack hand, final ItemStack candidate) {
        return hand != null && candidate != null && !candidate.getType().isAir() && Gear.kind(hand) != null
            && candidate.getType() == hand.getType() && this.items().consumable(candidate) == null;
    }

    /** Подходит ли предмет для слота меню. */
    private boolean accepts(final Player player, final Holder holder, final int slot, final ItemStack item) {
        if (slot == SLOT_STONE) {
            final Consumable c = this.items().consumable(item);
            return c != null && c.isStone();
        }
        if (slot == SLOT_RUNE) {
            final Consumable c = this.items().consumable(item);
            return c != null && c.isRune();
        }
        if (slot == SLOT_SACRIFICE && holder.sacrificeEnabled()) {
            return this.isSacrificeFor(player.getInventory().getItemInMainHand(), item);
        }
        return false;
    }

    private void denyMessage(final Player player, final int slot) {
        if (slot == SLOT_STONE) {
            Text.send(player, "<red>Сюда можно положить только камень заточки.</red>");
        } else if (slot == SLOT_RUNE) {
            Text.send(player, "<red>Сюда можно положить только руну.</red>");
        } else if (slot == SLOT_SACRIFICE) {
            Text.send(player, "<red>Сюда можно положить только второй предмет того же материала, что в руке.</red>");
        }
    }

    // ------------------------------------------------------------------ события (поток игрока)

    private static Holder holderOf(final InventoryView view) {
        return view.getTopInventory().getHolder(false) instanceof final Holder h ? h : null;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(final InventoryClickEvent event) {
        final Holder holder = holderOf(event.getView());
        if (holder == null || !(event.getWhoClicked() instanceof final Player player)) {
            return;
        }
        if (holder.busy) {
            event.setCancelled(true);
            return;
        }
        final Inventory top = holder.inventory;
        final int raw = event.getRawSlot();
        final ClickType click = event.getClick();
        if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR || click == ClickType.DOUBLE_CLICK) {
            // Двойной клик собрал бы предметы из слотов меню.
            event.setCancelled(true);
            return;
        }
        if (raw >= 0 && raw < top.getSize()) {
            if (raw == SLOT_CLOSE) {
                event.setCancelled(true);
                this.context.runOnceForPlayer(player, () -> {
                    if (holderOf(player.getOpenInventory()) == holder) {
                        player.closeInventory();
                    }
                });
                return;
            }
            if (raw == SLOT_CONFIRM) {
                event.setCancelled(true);
                this.attempt(player, holder);
                return;
            }
            if (!holder.isInput(raw)) {
                event.setCancelled(true);
                return;
            }
            if (click == ClickType.NUMBER_KEY || click == ClickType.SWAP_OFFHAND) {
                // Обмен с хотбаром мимо проверки типа предмета.
                event.setCancelled(true);
                return;
            }
            final ItemStack cursor = event.getCursor();
            if (!cursor.getType().isAir() && !this.accepts(player, holder, raw, cursor)) {
                event.setCancelled(true);
                this.denyMessage(player, raw);
                return;
            }
            if (raw == SLOT_SACRIFICE && !cursor.getType().isAir() && cursor.getAmount() > 1) {
                // Жертва — один предмет.
                event.setCancelled(true);
                return;
            }
            this.scheduleRender(player, holder);
            return;
        }
        if (raw < 0) {
            return;
        }
        // Нижний инвентарь: предмет в основной руке трогать нельзя — его и точим.
        final PlayerInventory own = player.getInventory();
        final int held = own.getHeldItemSlot();
        if (event.getClickedInventory() == own && event.getSlot() == held
            || click == ClickType.NUMBER_KEY && event.getHotbarButton() == held) {
            event.setCancelled(true);
            return;
        }
        if (event.isShiftClick()) {
            event.setCancelled(true);
            final ItemStack current = event.getCurrentItem();
            if (current == null || current.getType().isAir() || event.getClickedInventory() == null) {
                return;
            }
            final ItemStack rest = this.moveIn(player, holder, current);
            event.getClickedInventory().setItem(event.getSlot(), rest);
            this.render(player, holder);
            return;
        }
        this.scheduleRender(player, holder);
    }

    /** Shift-клик: положить предмет в подходящий слот меню. Возвращает остаток (null — всё перенесено). */
    private ItemStack moveIn(final Player player, final Holder holder, final ItemStack stack) {
        final int target;
        if (this.accepts(player, holder, SLOT_STONE, stack)) {
            target = SLOT_STONE;
        } else if (this.accepts(player, holder, SLOT_RUNE, stack)) {
            target = SLOT_RUNE;
        } else if (this.accepts(player, holder, SLOT_SACRIFICE, stack)) {
            target = SLOT_SACRIFICE;
        } else {
            return stack;
        }
        final Inventory top = holder.inventory;
        final ItemStack inSlot = top.getItem(target);
        final ItemStack rest = stack.clone();
        if (inSlot == null || inSlot.getType().isAir()) {
            final int put = target == SLOT_SACRIFICE ? 1 : Math.min(rest.getAmount(), rest.getMaxStackSize());
            final ItemStack placed = rest.clone();
            placed.setAmount(put);
            top.setItem(target, placed);
            rest.setAmount(rest.getAmount() - put);
        } else if (target != SLOT_SACRIFICE && inSlot.isSimilar(rest)) {
            final int put = Math.min(rest.getAmount(), inSlot.getMaxStackSize() - inSlot.getAmount());
            if (put > 0) {
                inSlot.setAmount(inSlot.getAmount() + put);
                top.setItem(target, inSlot);
                rest.setAmount(rest.getAmount() - put);
            }
        }
        return rest.getAmount() <= 0 ? null : rest;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(final InventoryDragEvent event) {
        final Holder holder = holderOf(event.getView());
        if (holder == null || !(event.getWhoClicked() instanceof final Player player)) {
            return;
        }
        if (holder.busy) {
            event.setCancelled(true);
            return;
        }
        final int size = holder.inventory.getSize();
        final int held = player.getInventory().getHeldItemSlot();
        for (final int raw : event.getRawSlots()) {
            if (raw < size) {
                if (!holder.isInput(raw) || !this.accepts(player, holder, raw, event.getOldCursor())
                    || raw == SLOT_SACRIFICE && event.getNewItems().get(raw) != null && event.getNewItems().get(raw).getAmount() > 1) {
                    event.setCancelled(true);
                    return;
                }
            } else if (event.getView().convertSlot(raw) == held && event.getView().getInventory(raw) == player.getInventory()) {
                event.setCancelled(true);
                return;
            }
        }
        this.scheduleRender(player, holder);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(final InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof final Holder holder)
            || !(event.getPlayer() instanceof final Player player)) {
            return;
        }
        this.open.remove(player.getUniqueId(), holder);
        if (holder.skipReturn) {
            return;
        }
        holder.skipReturn = true;
        this.returnInputs(player, holder);
    }

    private void scheduleRender(final Player player, final Holder holder) {
        this.context.runOnceForPlayer(player, () -> {
            if (holderOf(player.getOpenInventory()) == holder && !holder.busy) {
                this.render(player, holder);
            }
        });
    }

    // ------------------------------------------------------------------ попытка

    private void attempt(final Player player, final Holder holder) {
        final Inventory top = holder.inventory;
        final PlayerInventory own = player.getInventory();
        final ItemStack hand = own.getItemInMainHand();
        final Gear.Kind kind = Gear.kind(hand);
        if (kind == null) {
            Text.send(player, "<red>Возьмите предмет в основную руку.</red>");
            return;
        }
        this.items().migrate(hand);
        final SharpenSettings s = this.settings();
        final Gear.Grade grade = Gear.grade(hand);
        final int current = this.items().level(hand);
        final Consumable stone = this.stoneIn(top);
        final Consumable rune = this.runeIn(top);
        final int sacrificeLevel = holder.sacrificeEnabled() ? this.sacrificeLevel(hand, top.getItem(SLOT_SACRIFICE)) : -1;
        final SharpenRules.Check check = SharpenRules.plan(s, kind.sharpenCategory(), grade, current, stone, rune, sacrificeLevel);
        if (!check.ok()) {
            Text.send(player, check.error());
            return;
        }
        final SharpenRules.Plan plan = check.plan();

        // Списать расходники из слотов меню.
        if (plan.type() == SharpenRules.Type.LEGENDARY) {
            takeOne(top, SLOT_SACRIFICE);
        } else {
            takeOne(top, SLOT_STONE);
            if (plan.rune() != null) {
                takeOne(top, SLOT_RUNE);
            }
        }

        final ThreadLocalRandom random = ThreadLocalRandom.current();
        final SharpenRules.Outcome outcome = SharpenRules.roll(plan, random.nextDouble() * 100.0, random.nextDouble() * 100.0);
        final SharpenSettings.Sounds sounds = s.sounds();
        switch (outcome) {
            case SUCCESS -> {
                this.items().setLevel(hand, plan.target());
                own.setItemInMainHand(hand);
                if (plan.type() == SharpenRules.Type.LEGENDARY) {
                    Text.send(player, "<gold>Невероятно! Предмет заточен до <yellow>+" + plan.target() + "</yellow>.</gold>");
                    play(player, sounds.legendary());
                } else {
                    Text.send(player, "<green>Успех! Уровень заточки: <yellow>+" + plan.target() + "</yellow>.</green>");
                    play(player, sounds.success());
                }
            }
            case FAIL_KEEP -> {
                own.setItemInMainHand(hand);
                if (plan.type() == SharpenRules.Type.LEGENDARY) {
                    Text.send(player, "<yellow>Неудача. Жертва потеряна, заточка осталась +" + plan.current() + ".</yellow>");
                    play(player, sounds.legendaryFail());
                } else if (plan.rune() != null) {
                    Text.send(player, "<yellow>Неудача. Руна сохранила уровень заточки (+" + plan.current() + ").</yellow>");
                    play(player, sounds.fail());
                } else {
                    Text.send(player, "<yellow>Неудача. Уровень заточки не изменился (+" + plan.current() + ").</yellow>");
                    play(player, sounds.fail());
                }
            }
            case FAIL_DOWN -> {
                this.items().setLevel(hand, plan.levelAfterFail());
                own.setItemInMainHand(hand);
                Text.send(player, "<red>Неудача. Уровень заточки понижен до <yellow>+" + plan.levelAfterFail() + "</yellow>.</red>");
                play(player, sounds.fail());
            }
            case DESTROYED -> {
                own.setItemInMainHand(null);
                Text.send(player, "<dark_red>Предмет разрушен при заточке!</dark_red>");
                play(player, sounds.destroy());
            }
        }
        this.module.system().aspects().refreshLater(player);

        // Переоткрыть меню с остатками в слотах (как в плагине), в следующем тике потока игрока.
        holder.busy = true;
        final boolean queued = this.context.runOnceForPlayer(player, () -> this.reopen(player, holder));
        if (!queued) {
            holder.busy = false;
        }
    }

    private void reopen(final Player player, final Holder holder) {
        if (holderOf(player.getOpenInventory()) != holder || holder.skipReturn) {
            // Меню уже закрыто: остатки вернул обработчик закрытия.
            return;
        }
        final Inventory top = holder.inventory;
        final ItemStack stone = cloneOrNull(top.getItem(SLOT_STONE));
        final ItemStack rune = cloneOrNull(top.getItem(SLOT_RUNE));
        final ItemStack sacrifice = holder.sacrificeEnabled() ? cloneOrNull(top.getItem(SLOT_SACRIFICE)) : null;
        holder.skipReturn = true;
        clearInputs(holder);
        this.open(player, stone, rune, sacrifice);
    }

    private static void takeOne(final Inventory inv, final int slot) {
        final ItemStack stack = inv.getItem(slot);
        if (stack == null || stack.getType().isAir()) {
            return;
        }
        if (stack.getAmount() <= 1) {
            inv.setItem(slot, null);
        } else {
            stack.setAmount(stack.getAmount() - 1);
            inv.setItem(slot, stack);
        }
    }

    private static void play(final Player player, final SharpenSettings.SoundDef sound) {
        if (sound.present()) {
            player.playSound(player.getLocation(), sound.key(), sound.volume(), sound.pitch());
        }
    }

    // ------------------------------------------------------------------ возврат и закрытие

    /** Вернуть игроку содержимое слотов ввода и очистить их. Поток игрока. */
    private void returnInputs(final Player player, final Holder holder) {
        final Inventory top = holder.inventory;
        giveBack(player, top.getItem(SLOT_STONE));
        giveBack(player, top.getItem(SLOT_RUNE));
        if (holder.sacrificeEnabled()) {
            giveBack(player, top.getItem(SLOT_SACRIFICE));
        }
        clearInputs(holder);
    }

    private static void clearInputs(final Holder holder) {
        holder.inventory.setItem(SLOT_STONE, null);
        holder.inventory.setItem(SLOT_RUNE, null);
        if (holder.sacrificeEnabled()) {
            holder.inventory.setItem(SLOT_SACRIFICE, null);
        }
    }

    private static void giveBack(final Player player, final ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return;
        }
        for (final ItemStack left : player.getInventory().addItem(stack.clone()).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), left);
        }
    }

    private static ItemStack cloneOrNull(final ItemStack stack) {
        return stack == null || stack.getType().isAir() ? null : stack.clone();
    }

    /**
     * Выключение модуля: каждому игроку с открытым меню — задача в его потоке: вернуть предметы из слотов и закрыть
     * меню. Обработчики к этому моменту уже сняты, поэтому возврат делается здесь. Глобальный поток.
     */
    void closeAll() {
        for (final Holder holder : this.open.values()) {
            final Player player = Bukkit.getPlayer(holder.owner);
            if (player == null) {
                continue;
            }
            this.context.runOnceForPlayer(player, () -> {
                if (!holder.skipReturn) {
                    holder.skipReturn = true;
                    this.returnInputs(player, holder);
                }
                if (holderOf(player.getOpenInventory()) == holder) {
                    player.closeInventory();
                }
            });
        }
        this.open.clear();
    }

    /** Есть ли у игрока открытое меню заточки. */
    boolean isOpen(final UUID uuid) {
        return this.open.containsKey(uuid);
    }

    // ------------------------------------------------------------------ предметы интерфейса

    private static ItemStack pane(final Material material) {
        return named(material, " ", List.of());
    }

    private static ItemStack named(final Material material, final String name, final List<String> lore) {
        final ItemStack stack = new ItemStack(material);
        stack.editMeta(meta -> {
            meta.displayName(Text.item(name));
            if (!lore.isEmpty()) {
                meta.lore(Text.lore(lore));
            }
        });
        return stack;
    }
}
