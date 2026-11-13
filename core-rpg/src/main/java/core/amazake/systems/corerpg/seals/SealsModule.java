package core.amazake.systems.corerpg.seals;

import core.amazake.systems.api.SystemContext;
import core.amazake.systems.corerpg.CoreModule;
import core.amazake.systems.corerpg.CoreRpgSystem;
import core.amazake.systems.corerpg.aspect.AspectContributor;
import core.amazake.systems.corerpg.aspect.AspectSnapshot;
import core.amazake.systems.corerpg.profile.PlayerProfile;
import core.amazake.systems.corerpg.util.Configs;
import core.amazake.systems.corerpg.util.Gear;
import core.amazake.systems.corerpg.util.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Модуль C: магические печати. Меню мастера (камень, кристалл крови, сургучи, Сердце Аркона), каталог seals.yml,
 * бонусы печатей надетой экипировки как аспекты, персональные предметы, команда /seals, NPC «Мастер зачарования».
 *
 * <p>Потоки: настройки и каталог — неизменяемые снимки в {@link AtomicReference}; открытые меню — в
 * {@link ConcurrentHashMap}; предметы и инвентарь игрока меняются только в его потоке.</p>
 */
public final class SealsModule implements CoreModule {

    static final String ADMIN = "core.seals.admin";
    static final String FILE = "seals.yml";

    /** Ответ действия: MiniMessage без префикса и подстановки. */
    record Reply(boolean success, String text, TagResolver resolver) {
        static Reply fail(final String text) {
            return new Reply(false, text, TagResolver.empty());
        }

        Reply with(final TagResolver more) {
            return new Reply(this.success, this.text, TagResolver.resolver(this.resolver, more));
        }

        void send(final CommandSender to) {
            Text.send(to, this.text, this.resolver);
        }
    }

    private final CoreRpgSystem system;
    private final AtomicReference<SealSettings> settings = new AtomicReference<>(SealSettings.DEFAULTS);
    private final AtomicReference<SealCatalog> catalog = new AtomicReference<>(SealCatalog.EMPTY);
    private final Map<UUID, SealMenu> openMenus = new ConcurrentHashMap<>();
    private volatile SealItems items;

    public SealsModule(final CoreRpgSystem system) {
        this.system = system;
    }

    // --- жизненный цикл ---

    @Override
    public void enable(final SystemContext context) {
        this.items = new SealItems(this.system.keys());
        this.load(context);
        this.system.aspects().register(new Contributor());
        context.registerListener(new SealMenuListener(this));
        context.registerListener(new BoundItemListener(this));
        context.registerListener(new SealsListener(this));
        context.registerCommand(new SealsCommand(this));
        // Систему могли включить при игроках онлайн: перенести их старые предметы MagicSeals.
        for (final Player player : Bukkit.getOnlinePlayers()) {
            context.runOnceForPlayer(player, () -> this.migrateInventory(player));
        }
        context.logger().info("Печати: в каталоге " + this.catalogSize() + ", выпадают " + this.enabledCount() + ".");
    }

    @Override
    public void reload(final SystemContext context) {
        this.load(context);
    }

    @Override
    public void disable(final SystemContext context) {
        // Закрыть меню и вернуть игрокам расходники из слотов (в потоке каждого игрока).
        for (final Map.Entry<UUID, SealMenu> entry : this.openMenus.entrySet()) {
            final Player player = Bukkit.getPlayer(entry.getKey());
            final SealMenu menu = entry.getValue();
            if (player == null) {
                continue;
            }
            context.runOnceForPlayer(player, () -> {
                this.giveBack(player, menu.finish());
                if (player.getOpenInventory().getTopInventory().getHolder(false) == menu) {
                    player.closeInventory();
                }
            });
        }
        this.openMenus.clear();
    }

    /** Читает seals.yml (настройки и каталог). Ошибка YAML — исключение (в enable система не включится). */
    void load(final SystemContext context) {
        this.apply(Configs.load(context, FILE), context);
    }

    /** Разбирает уже прочитанный файл и подменяет снимки. Любой поток. */
    void apply(final YamlConfiguration yaml, final SystemContext context) {
        final SealSettings parsed = SealSettings.from(yaml, w -> context.logger().warning(FILE + ": " + w))
            .withItemMaterials(Material::isItem, w -> context.logger().warning(FILE + ": " + w));
        final SealCatalog parsedCatalog = SealCatalog.from(yaml, w -> context.logger().warning(FILE + ": " + w));
        this.settings.set(parsed);
        this.catalog.set(parsedCatalog);
    }

    // --- фасад ---

    /** Название печати на предмете («Агрессии») или "" без печати. Любой поток. */
    public String sealName(final ItemStack item) {
        final SealItems it = this.items;
        final SealItems.SealData data = it == null ? null : it.read(item);
        if (data == null || data.id() == null) {
            return "";
        }
        final SealDefinition def = this.catalog.get().get(data.id());
        return def == null ? data.id() : def.name();
    }

    /** Цвет печати по-русски («Фиолетовая») или "". Любой поток. */
    public String sealColorName(final ItemStack item) {
        final SealItems it = this.items;
        final SealItems.SealData data = it == null ? null : it.read(item);
        return data == null || data.id() == null || data.color() == null ? "" : data.color().russian();
    }

    /** Число печатей в каталоге (включённых и выключенных). */
    public int catalogSize() {
        return this.catalog.get().size();
    }

    /** Число печатей, которые могут выпасть (enabled ≠ false). */
    public int enabledCount() {
        int n = 0;
        for (final SealDefinition seal : this.catalog.get().all()) {
            if (seal.enabled()) {
                n++;
            }
        }
        return n;
    }

    // --- для своих классов ---

    CoreRpgSystem system() {
        return this.system;
    }

    SealSettings settings() {
        return this.settings.get();
    }

    SealCatalog catalog() {
        return this.catalog.get();
    }

    SealItems items() {
        return this.items;
    }

    /** Открыть меню мастера. Поток игрока. */
    void openMenu(final Player player) {
        final SealMenu menu = new SealMenu(player.getUniqueId(), this.settings.get().menuTitle());
        player.openInventory(menu.getInventory());
        if (player.getOpenInventory().getTopInventory().getHolder(false) == menu) {
            final SealMenu old = this.openMenus.put(player.getUniqueId(), menu);
            if (old != null && old != menu) {
                this.giveBack(player, old.finish());
            }
        } else {
            menu.finish();
        }
    }

    /** Меню закрыто: вернуть расходники. Поток игрока. */
    void menuClosed(final Player player, final SealMenu menu) {
        this.openMenus.remove(player.getUniqueId(), menu);
        this.giveBack(player, menu.finish());
    }

    /** Отдать предметы игроку; не влезло — под ноги. Поток игрока. */
    void giveBack(final Player player, final List<ItemStack> stacks) {
        for (final ItemStack stack : stacks) {
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            for (final ItemStack left : player.getInventory().addItem(stack).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), left);
            }
        }
    }

    /** Перенести старые предметы MagicSeals в инвентаре игрока на ключи CoreRpg. Поток игрока. */
    void migrateInventory(final Player player) {
        final SealItems it = this.items;
        if (it == null || !player.isOnline()) {
            return;
        }
        final PlayerInventory inventory = player.getInventory();
        final ItemStack[] contents = inventory.getContents();
        int moved = 0;
        for (int i = 0; i < contents.length; i++) {
            final ItemStack migrated = it.migrate(contents[i], this.catalog.get(), this.settings.get());
            if (migrated != null) {
                inventory.setItem(i, migrated);
                moved++;
            }
        }
        if (moved > 0) {
            this.system.aspects().refreshLater(player);
        }
    }

    /** Предмет в основной руке после переноса старых данных. Поток игрока. */
    ItemStack mainHand(final Player player) {
        final PlayerInventory inventory = player.getInventory();
        final ItemStack item = inventory.getItemInMainHand();
        final ItemStack migrated = this.items.migrate(item, this.catalog.get(), this.settings.get());
        if (migrated != null) {
            inventory.setItemInMainHand(migrated);
            return migrated;
        }
        return item;
    }

    /** Кнопка «Наложить печать». Поток игрока. */
    Reply perform(final Player player, final SealMenu menu) {
        final SealSettings s = this.settings.get();
        final SealCatalog cat = this.catalog.get();
        final ItemStack target = this.mainHand(player);
        if (target == null || target.getType().isAir()) {
            return Reply.fail("<red>Возьмите экипировку в основную руку.</red>");
        }
        final Gear.Kind kind = Gear.kind(target);
        final String type = kind == null ? null : kind.sealType();
        if (type == null) {
            return Reply.fail("<red>На этот предмет печать наложить нельзя.</red>");
        }
        if (this.items.isBoundToOther(target, player.getUniqueId())) {
            return Reply.fail("<red>Предмет принадлежит другому игроку.</red>");
        }
        final Inventory inv = menu.getInventory();
        final SealRules.Slots slots = new SealRules.Slots(
            this.items.consumable(inv.getItem(SealMenu.SLOT_STONE)),
            this.items.consumable(inv.getItem(SealMenu.SLOT_CRYSTAL)),
            this.items.consumable(inv.getItem(SealMenu.SLOT_WAX)),
            this.items.consumable(inv.getItem(SealMenu.SLOT_HEART)));
        final SealItems.SealData data = this.items.read(target);
        final boolean hasSeal = data != null && data.id() != null;
        final SealColor currentColor = hasSeal ? data.color() : null;
        final SealRules.Decision decision = SealRules.decide(hasSeal, currentColor, Gear.grade(target), slots);
        if (!decision.ok()) {
            return Reply.fail(decision.error());
        }
        final SealRules.Action action = decision.action();
        final SealDefinition current = hasSeal ? cat.get(data.id()) : null;
        List<SealDefinition> pool = List.of();
        if (action == SealRules.Action.APPLY || action == SealRules.Action.CHANGE) {
            final PlayerProfile profile = this.system.profiles().get(player.getUniqueId());
            pool = cat.pool(type, profile.classId(), profile.professionId(), s.classFilter());
            if (pool.isEmpty()) {
                return Reply.fail("<red>Для этого типа предмета нет доступных печатей в seals.yml.</red>");
            }
        } else if (current == null) {
            return Reply.fail("<red>Текущая печать отсутствует в каталоге. Проверьте seals.yml.</red>");
        }

        final ThreadLocalRandom random = ThreadLocalRandom.current();
        // Арконит бросаем до списания: если не сработал, цвет не меняется и Сердце Аркона остаётся в слоте.
        final boolean arkoniteFailed = action == SealRules.Action.ARKONITE
            && !SealRules.arkoniteRerolls(s.arkoniteRerollChance(), random.nextDouble(100.0));
        final boolean useHeart = decision.heart() && !arkoniteFailed;

        // Списываем по одному расходнику из слотов (меню не закрывается).
        switch (action) {
            case APPLY -> menu.takeOne(SealMenu.SLOT_STONE);
            case CHANGE -> menu.takeOne(SealMenu.SLOT_CRYSTAL);
            case ARKONITE -> {
                menu.takeOne(SealMenu.SLOT_CRYSTAL);
                menu.takeOne(SealMenu.SLOT_WAX);
            }
            case WAX, ACCOUNT_WAX -> menu.takeOne(SealMenu.SLOT_WAX);
            case HEART -> {
            }
        }
        if (useHeart) {
            menu.takeOne(SealMenu.SLOT_HEART);
        }

        final SealColor kept = currentColor == null ? SealColor.GREEN : currentColor;
        SealDefinition chosen = current;
        SealColor base;
        switch (action) {
            case APPLY, CHANGE -> {
                chosen = pool.get(random.nextInt(pool.size()));
                base = this.rollColor(s);
            }
            case WAX, ACCOUNT_WAX -> base = SealColor.ORANGE;
            case ARKONITE -> base = arkoniteFailed ? kept : this.rollColor(s);
            default -> base = kept;
        }
        final SealColor color = useHeart ? SealRules.withHeart(base) : base;
        final boolean heartHelped = useHeart && color != base;

        this.items.write(target, chosen, color, action == SealRules.Action.ACCOUNT_WAX ? player.getUniqueId() : null);
        player.getInventory().setItemInMainHand(target);
        this.system.aspects().refreshLater(player);

        final String open = "<" + color.tag() + ">";
        final String close = "</" + color.tag() + ">";
        String text = switch (action) {
            case APPLY -> open + "Наложена печать <seal> (<color>)." + close;
            case CHANGE -> open + "Печать заменена: <seal> (<color>)." + close;
            case WAX -> open + "Печать стала оранжевой." + close;
            case ACCOUNT_WAX -> open + "Печать стала оранжевой, предмет привязан к вам." + close;
            case ARKONITE -> arkoniteFailed
                ? "<yellow>Арконитовый сургуч не сработал: цвет остался " + open + "<color>" + close + "."
                    + (decision.heart() ? " Сердце Аркона не потрачено." : "") + "</yellow>"
                : open + "Цвет изменён на <color>; название печати сохранено." + close;
            case HEART -> open + "Сердце Аркона закрепило цвет: <color>." + close;
        };
        if (heartHelped) {
            text += " <aqua>Сердце Аркона закрепило цвет: " + open + "<color>" + close + ".</aqua>";
        }
        return new Reply(true, text, TagResolver.resolver(
            Placeholder.unparsed("seal", chosen.name()),
            Placeholder.unparsed("color", color.russian())));
    }

    private SealColor rollColor(final SealSettings s) {
        final int[] weights = s.weights();
        final int total = SealRules.totalWeight(weights);
        return total <= 0 ? SealColor.GREEN : SealRules.rollColor(weights, ThreadLocalRandom.current().nextInt(total));
    }

    /** Звук после нажатия кнопки. Поток игрока. */
    static void playResult(final Player player, final boolean success) {
        if (success) {
            player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.0f, 1.0f);
        } else {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
        }
    }

    /** Админ: поставить печать на предмет в основной руке. Поток игрока target. */
    Reply setSeal(final Player target, final SealDefinition seal, final SealColor color) {
        final ItemStack item = this.mainHand(target);
        if (item == null || item.getType().isAir()) {
            return Reply.fail("<red>У игрока <player> пусто в основной руке.</red>")
                .with(Placeholder.unparsed("player", target.getName()));
        }
        final Gear.Kind kind = Gear.kind(item);
        final String type = kind == null ? null : kind.sealType();
        if (type == null) {
            return Reply.fail("<red>На предмет в руке <player> печать наложить нельзя.</red>")
                .with(Placeholder.unparsed("player", target.getName()));
        }
        if (!seal.accepts(type)) {
            return Reply.fail("<red>Печать <seal> не накладывается на " + typeName(type) + ".</red>")
                .with(Placeholder.unparsed("seal", seal.name()));
        }
        this.items.write(item, seal, color, null);
        target.getInventory().setItemInMainHand(item);
        this.system.aspects().refreshLater(target);
        return new Reply(true, "<green>Игроку <player> поставлена печать <seal> (<" + color.tag() + ">"
            + color.russian().toLowerCase(Locale.ROOT) + "</" + color.tag() + ">).</green>", TagResolver.resolver(
            Placeholder.unparsed("player", target.getName()), Placeholder.unparsed("seal", seal.name())));
    }

    /** Админ: выдать расходники. Поток игрока target. */
    void give(final Player target, final List<Consumable> list, final int amount) {
        final SealSettings s = this.settings.get();
        final List<ItemStack> stacks = new ArrayList<>();
        for (final Consumable c : list) {
            stacks.add(this.items.createConsumable(c, amount, s));
        }
        this.giveBack(target, stacks);
    }

    /** «оружие», «броню», «щит» (винительный падеж). */
    static String typeName(final String type) {
        return switch (type) {
            case "WEAPON" -> "оружие";
            case "ARMOR" -> "броню";
            case "SHIELD" -> "щит";
            default -> type;
        };
    }

    /** Вкладчик аспектов (order 0): бонусы печатей надетой брони и предметов в руках. Поток игрока. */
    private final class Contributor implements AspectContributor {
        @Override
        public void contribute(final Player player, final PlayerProfile profile, final AspectSnapshot.Builder out) {
            final SealItems it = SealsModule.this.items;
            if (it == null) {
                return;
            }
            final SealCatalog cat = SealsModule.this.catalog.get();
            final PlayerInventory inventory = player.getInventory();
            for (final ItemStack armor : inventory.getArmorContents()) {
                add(it, cat, armor, out);
            }
            final ItemStack main = inventory.getItemInMainHand();
            if (handTyped(main)) {
                add(it, cat, main, out);
            }
            final ItemStack off = inventory.getItemInOffHand();
            if (handTyped(off)) {
                add(it, cat, off, out);
            }
        }

        @Override
        public int order() {
            return 0;
        }
    }

    private static boolean handTyped(final ItemStack item) {
        final Gear.Kind kind = Gear.kind(item);
        final String type = kind == null ? null : kind.sealType();
        return "WEAPON".equals(type) || "SHIELD".equals(type);
    }

    private static void add(final SealItems it, final SealCatalog cat, final ItemStack item, final AspectSnapshot.Builder out) {
        final SealItems.SealData data = it.read(item);
        if (data == null || data.id() == null || data.color() == null) {
            return;
        }
        final SealDefinition seal = cat.get(data.id());
        if (seal == null) {
            return;
        }
        for (final Map.Entry<String, SealDefinition.Values> e : seal.bonuses().entrySet()) {
            final double v = e.getValue().get(data.color());
            if (!Double.isNaN(v)) {
                out.add(e.getKey(), v);
            }
        }
    }
}
