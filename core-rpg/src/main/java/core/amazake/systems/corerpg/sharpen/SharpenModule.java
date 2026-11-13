package core.amazake.systems.corerpg.sharpen;

import core.amazake.systems.api.SystemContext;
import core.amazake.systems.corerpg.CoreModule;
import core.amazake.systems.corerpg.CoreRpgSystem;
import core.amazake.systems.corerpg.aspect.Aspect;
import core.amazake.systems.corerpg.aspect.AspectContributor;
import core.amazake.systems.corerpg.aspect.AspectSnapshot;
import core.amazake.systems.corerpg.profile.PlayerProfile;
import core.amazake.systems.corerpg.util.Configs;
import core.amazake.systems.corerpg.util.Gear;
import core.amazake.systems.corerpg.util.Text;
import io.papermc.paper.event.player.PlayerLoomPatternSelectEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Cat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.Wolf;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Модуль B: заточка +0…+11 (из плагина Zatochka и решений артефакта экономики).
 *
 * <p>Меню заточки, расходники, команды {@code /sharpen}, NPC «Королевский алхимик», перенос старых предметов и
 * вкладчик аспектов: {@code armor_sharpen} (средний бонус надетой брони) и +1 ко всем параметрам за каждый надетый
 * предмет +11. Урон модуль не меняет — это делает бой через {@link #bonusPercent}.</p>
 */
public final class SharpenModule implements CoreModule, Listener {

    private static final String FILE = "sharpen.yml";

    private final CoreRpgSystem system;
    private final AtomicReference<SharpenSettings> settings = new AtomicReference<>(SharpenSettings.DEFAULTS);
    private final SharpenItems items;
    private volatile SystemContext context;
    private volatile SharpenMenus menus;

    public SharpenModule(final CoreRpgSystem system) {
        this.system = system;
        this.items = new SharpenItems(system.keys(), this.settings::get);
    }

    @Override
    public void enable(final SystemContext context) {
        this.context = context;
        this.loadSettings(context);
        this.menus = new SharpenMenus(this, context);
        context.registerListener(this);
        context.registerListener(this.menus);
        context.registerCommand(new SharpenCommand(this));
        this.system.aspects().register(new Contributor());
        // Систему могли включить при игроках онлайн: перенести их старые предметы.
        for (final Player player : Bukkit.getOnlinePlayers()) {
            context.runOnceForPlayer(player, () -> this.migratePlayer(player));
        }
    }

    @Override
    public void reload(final SystemContext context) {
        this.loadSettings(context);
    }

    @Override
    public void disable(final SystemContext context) {
        final SharpenMenus m = this.menus;
        if (m != null) {
            m.closeAll();
        }
        this.menus = null;
    }

    private void loadSettings(final SystemContext context) {
        this.settings.set(SharpenSettings.from(Configs.load(context, FILE), w -> context.logger().warning(FILE + ": " + w)));
    }

    /** /sharpen reload: файл читается вне тика, ответ — в потоке отправителя. */
    void reloadAsync(final CommandSender sender) {
        final SystemContext ctx = this.context;
        Bukkit.getAsyncScheduler().runNow(ctx.plugin(), task -> {
            final List<String> warnings = new ArrayList<>();
            try {
                this.settings.set(SharpenSettings.from(Configs.load(ctx, FILE), warnings::add));
            } catch (final RuntimeException e) {
                ctx.logger().log(Level.WARNING, "Не удалось перечитать " + FILE, e);
                this.reply(sender, "<red>Не удалось перечитать sharpen.yml: <white><error></white>. Остались прежние настройки.</red>",
                    Placeholder.unparsed("error", String.valueOf(e.getMessage())));
                return;
            }
            for (final String w : warnings) {
                ctx.logger().warning(FILE + ": " + w);
            }
            for (final Player player : Bukkit.getOnlinePlayers()) {
                this.system.aspects().refreshLater(player);
            }
            this.reply(sender, warnings.isEmpty()
                ? "<green>sharpen.yml перечитан.</green>"
                : "<yellow>sharpen.yml перечитан, предупреждений: " + warnings.size() + " (см. консоль).</yellow>");
        });
    }

    /** Сообщение отправителю команды из любого потока: игроку — в его потоке. */
    void reply(final CommandSender sender, final String message, final TagResolver... resolvers) {
        if (sender instanceof final Player player) {
            this.context.runOnceForPlayer(player, () -> Text.send(player, message, resolvers));
        } else {
            Text.send(sender, message, resolvers);
        }
    }

    // ------------------------------------------------------------------ события

    @EventHandler(priority = EventPriority.NORMAL)
    public void onJoin(final PlayerJoinEvent event) {
        this.migratePlayer(event.getPlayer());
    }

    private void migratePlayer(final Player player) {
        if (player.isOnline() && this.items.migrateInventory(player) > 0) {
            this.system.aspects().refreshLater(player);
        }
    }

    /** Правый клик по NPC с именем из настроек (Citizens не нужен: подходит любая сущность с таким именем). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(final PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        final SharpenSettings s = this.settings.get();
        final SharpenMenus m = this.menus;
        if (m == null || !s.npcEnabled() || s.npcNames().isEmpty()) {
            return;
        }
        final String name = entityName(event.getRightClicked());
        for (final String expected : s.npcNames()) {
            if (expected.equalsIgnoreCase(name)) {
                event.setCancelled(true);
                m.open(event.getPlayer());
                return;
            }
        }
    }

    // ------------------------------------------------------------------ защита расходников

    /*
     * Расходники сделаны из ванильных материалов (кластер аметиста, магнетит, синий лёд — блоки; камни — красители;
     * осколок — ингредиент). Ванильное использование уничтожило бы их PDC, поэтому модуль его запрещает.
     */

    private boolean isConsumable(final ItemStack item) {
        return this.items.consumable(item) != null;
    }

    private boolean anyConsumable(final ItemStack[] stacks) {
        if (stacks != null) {
            for (final ItemStack stack : stacks) {
                if (this.isConsumable(stack)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Расходник нельзя поставить блоком: сломанный блок выпадет обычным предметом без PDC. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(final BlockPlaceEvent event) {
        if (this.isConsumable(event.getItemInHand())) {
            event.setCancelled(true);
        }
    }

    /** Камень-краситель нельзя потратить на табличку. */
    @EventHandler(priority = EventPriority.LOW)
    public void onInteractBlock(final PlayerInteractEvent event) {
        final Block block = event.getClickedBlock();
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || block == null || !this.isConsumable(event.getItem())) {
            return;
        }
        if (Tag.ALL_SIGNS.isTagged(block.getType())) {
            event.setCancelled(true);
        }
    }

    /** Камень-краситель нельзя потратить на овцу или ошейник волка/кошки. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDyeEntity(final PlayerInteractEntityEvent event) {
        final Entity target = event.getRightClicked();
        if (!(target instanceof Sheep) && !(target instanceof Wolf) && !(target instanceof Cat)) {
            return;
        }
        if (this.isConsumable(event.getPlayer().getInventory().getItem(event.getHand()))) {
            event.setCancelled(true);
        }
    }

    /** Расходник не ингредиент верстака. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepareCraft(final PrepareItemCraftEvent event) {
        if (this.anyConsumable(event.getInventory().getMatrix())) {
            event.getInventory().setResult(null);
        }
    }

    /** …и автокрафтера. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCrafter(final CrafterCraftEvent event) {
        if (event.getBlock().getState(false) instanceof final Container container) {
            final Inventory inv = container.getInventory();
            if (this.anyConsumable(inv.getContents())) {
                event.setCancelled(true);
            }
        }
    }

    /** …и кузнечного стола. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepareSmithing(final PrepareSmithingEvent event) {
        if (this.anyConsumable(event.getInventory().getContents())) {
            event.setResult(null);
        }
    }

    /** …и ткацкого станка (краситель тратится на узор). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onLoom(final PlayerLoomPatternSelectEvent event) {
        if (this.anyConsumable(event.getLoomInventory().getContents())) {
            event.setCancelled(true);
        }
    }

    private static String entityName(final Entity entity) {
        final Component custom = entity.customName();
        final String raw = custom != null ? Text.plain(custom) : entity.getName();
        return raw == null ? "" : raw.replaceAll("§.", "").trim();
    }

    // ------------------------------------------------------------------ вкладчик аспектов

    /** armor_sharpen: средний бонус заточенной надетой брони; stat_*: +N за каждый надетый предмет +11. Поток игрока. */
    private final class Contributor implements AspectContributor {
        @Override
        public void contribute(final Player player, final PlayerProfile profile, final AspectSnapshot.Builder out) {
            final EntityEquipment eq = player.getEquipment();
            final SharpenSettings s = SharpenModule.this.settings.get();
            final SharpenItems it = SharpenModule.this.items;
            final List<Double> armorBonuses = new ArrayList<>(4);
            int legendary = 0;
            for (final ItemStack piece : new ItemStack[] {eq.getHelmet(), eq.getChestplate(), eq.getLeggings(), eq.getBoots()}) {
                if (Gear.kind(piece) != Gear.Kind.ARMOR) {
                    continue;
                }
                final int lvl = it.level(piece);
                if (lvl > 0) {
                    armorBonuses.add(s.bonus(false, lvl));
                }
                if (lvl >= SharpenSettings.HARD_MAX) {
                    legendary++;
                }
            }
            // Оружие или инструмент в основной руке, щит во второй.
            final ItemStack main = eq.getItemInMainHand();
            final Gear.Kind mainKind = Gear.kind(main);
            if (mainKind != null && mainKind != Gear.Kind.ARMOR && it.level(main) >= SharpenSettings.HARD_MAX) {
                legendary++;
            }
            final ItemStack off = eq.getItemInOffHand();
            if (Gear.kind(off) == Gear.Kind.SHIELD && it.level(off) >= SharpenSettings.HARD_MAX) {
                legendary++;
            }
            out.add(Aspect.ARMOR_SHARPEN, SharpenRules.averageBonus(armorBonuses));
            if (legendary > 0 && s.legendaryStat() > 0) {
                final double v = (double) legendary * s.legendaryStat();
                out.add(Aspect.STAT_PRIMARY, v);
                out.add(Aspect.STAT_AGILITY, v);
                out.add(Aspect.STAT_STAMINA, v);
                out.add(Aspect.STAT_LUCK, v);
            }
        }

        @Override
        public int order() {
            return 0;
        }
    }

    // ------------------------------------------------------------------ доступ внутри модуля

    SharpenSettings settings() {
        return this.settings.get();
    }

    SharpenItems items() {
        return this.items;
    }

    SharpenMenus menus() {
        return this.menus;
    }

    SystemContext context() {
        return this.context;
    }

    CoreRpgSystem system() {
        return this.system;
    }

    // ------------------------------------------------------------------ фасад (контракт)

    /** Уровень заточки предмета (0, если нет; читает и старый ключ zatochka:sharpen_level). Любой поток. */
    public int level(final ItemStack item) {
        return this.items.level(item);
    }

    /** Бонус заточки в процентах: к атаке для оружия, к защите для брони и щита. 0 для прочего. Любой поток. */
    public double bonusPercent(final ItemStack item) {
        final Gear.Kind kind = Gear.kind(item);
        if (kind == null) {
            return 0.0;
        }
        return this.settings.get().bonus(kind.attacks(), this.level(item));
    }

    /** Максимальная заточка для предмета (по грейду), 0 если не точится. */
    public int maxLevel(final ItemStack item) {
        if (Gear.kind(item) == null) {
            return 0;
        }
        return this.settings.get().maxLevel(Gear.grade(item));
    }

    /** Открыть меню заточки игроку (например, из другого модуля или NPC). Поток игрока. */
    public void openMenu(final Player player) {
        final SharpenMenus m = this.menus;
        if (m != null) {
            m.open(player);
        }
    }
}
