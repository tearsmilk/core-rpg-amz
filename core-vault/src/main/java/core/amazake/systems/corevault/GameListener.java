package core.amazake.systems.corevault;

import core.amazake.systems.api.SystemContext;
import core.amazake.systems.corevault.coin.CoinBonus;
import core.amazake.systems.corevault.coin.Coins;
import core.amazake.systems.corevault.coin.DropRule;
import core.amazake.systems.corevault.data.Account;
import core.amazake.systems.corevault.data.AccountStore;
import core.amazake.systems.corevault.eco.Ledger;
import core.amazake.systems.corevault.eco.Treasury;
import core.amazake.systems.corevault.level.LevelService;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;

/**
 * События: вход/выход (счёт, имя, награды, монеты в инвентаре), смерть моба (опыт и монеты — поток региона моба;
 * игроку — через runOnceForPlayer), подбор монет (поток игрока/региона предмета), воронки, ванильный опыт.
 */
final class GameListener implements Listener {

    /** Метка моба core-mobs: id из mobs.yml. */
    static final NamespacedKey CORE_MOB = new NamespacedKey("core-mobs", "mob");

    private final SystemContext context;
    private final Supplier<Settings> settings;
    private final AccountStore store;
    private final Treasury treasury;
    private final LevelService levels;

    GameListener(final SystemContext context, final Supplier<Settings> settings, final AccountStore store,
                 final Treasury treasury, final LevelService levels) {
        this.context = context;
        this.settings = settings;
        this.store = store;
        this.treasury = treasury;
        this.levels = levels;
    }

    private boolean coinsOn() {
        final Settings.Modules m = this.settings.get().modules();
        return m.coins() && m.economy();
    }

    // ------------------------------------------------------------------------------------------- вход/выход

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent e) {
        final Player p = e.getPlayer();
        final Account a = this.treasury.internal().account(p.getUniqueId(), p.getName());
        this.store.rename(a, p.getName());
        final Settings s = this.settings.get();
        if (s.modules().levels()) {
            this.levels.join(p);
        }
        if (this.coinsOn() && s.coins().convertJoin() && !AdminMode.isOn(p)) {
            this.context.runOnceForPlayer(p, () -> this.convertInventory(p));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent e) {
        this.levels.quit(e.getPlayer().getUniqueId());
        this.store.markDirty();
    }

    // ------------------------------------------------------------------------------------------- мобы

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDeath(final EntityDeathEvent e) {
        final LivingEntity mob = e.getEntity();
        if (mob instanceof Player) {
            return;
        }
        final Settings s = this.settings.get();
        final Player killer = mob.getKiller();
        final String coreId = mob.getPersistentDataContainer().get(CORE_MOB, PersistentDataType.STRING);
        final String type = mob.getType().name();
        final boolean hostile = mob instanceof Enemy;
        final boolean spawner = mob.fromMobSpawner();
        if (killer != null && s.modules().levels()) {
            final int[] range = s.levels().mobExp().find(coreId, type, hostile);
            if (range != null && range[1] > 0) {
                long exp = range[0] == range[1] ? range[0] : ThreadLocalRandom.current().nextInt(range[0], range[1] + 1);
                if (spawner) {
                    exp = Math.round(exp * s.levels().spawnerMultiplier());
                }
                if (exp > 0) {
                    final long gain = exp;
                    final UUID id = killer.getUniqueId();
                    final String name = killer.getName();
                    this.levels.addExp(id, name, gain, true);
                }
            }
        }
        if (this.coinsOn() && (killer != null || !s.coins().requirePlayerKill()) && (!spawner || s.coins().fromSpawners())) {
            final DropRule rule = s.coins().drops().find(coreId, type, hostile);
            if (rule != null) {
                final int count = rule.roll(ThreadLocalRandom.current());
                if (count > 0) {
                    dropCoins(s, mob.getLocation(), count, rule.value() > 0 ? rule.value() : s.coins().value());
                }
            }
        }
    }

    /** Поток региона точки: выбросить монеты. */
    static void dropCoins(final Settings s, final Location at, final int count, final long value) {
        final ItemStack stack = Coins.create(s.coins(), value, 1);
        final int maxStack = stack.getMaxStackSize();
        int left = count;
        while (left > 0) {
            final int n = Math.min(maxStack, left);
            left -= n;
            final ItemStack st = stack.clone();
            st.setAmount(n);
            at.getWorld().dropItemNaturally(at, st, item -> prepare(s, item));
        }
    }

    private static void prepare(final Settings s, final Item item) {
        item.setPickupDelay(s.coins().pickupDelayTicks());
        item.setCanMobPickup(s.coins().mobsPickup());
        item.setGlowing(s.coins().glow());
        final int despawn = s.coins().despawnSeconds();
        if (despawn <= 0) {
            item.setUnlimitedLifetime(true);
        } else {
            // возраст предмета: ванильно исчезает в 6000 тиков
            item.setTicksLived(Math.max(1, 6000 - Math.min(6000, despawn * 20)));
        }
    }

    // ------------------------------------------------------------------------------------------- подбор

    /** Подбор игроком (его поток): монета не идёт в инвентарь, а сразу на баланс — даже если инвентарь полон. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAttemptPickup(final PlayerAttemptPickupItemEvent e) {
        if (!this.coinsOn()) {
            return;
        }
        final Item item = e.getItem();
        final long value = Coins.value(item.getItemStack());
        if (value <= 0) {
            return;
        }
        e.setCancelled(true);
        e.setFlyAtPlayer(false);
        this.collect(e.getPlayer(), item, value);
    }

    /** На случай, если подбор пришёл в обход PlayerAttemptPickupItemEvent; мобам монеты — по настройке. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPickup(final EntityPickupItemEvent e) {
        if (!this.coinsOn()) {
            return;
        }
        final Item item = e.getItem();
        final long value = Coins.value(item.getItemStack());
        if (value <= 0) {
            return;
        }
        if (e.getEntity() instanceof Player p) {
            e.setCancelled(true);
            this.collect(p, item, value);
        } else if (!this.settings.get().coins().mobsPickup()) {
            e.setCancelled(true);
        }
    }

    private void collect(final Player p, final Item item, final long value) {
        if (!item.isValid() || item.isDead()) {
            return;
        }
        final ItemStack stack = item.getItemStack();
        final int amount = stack.getAmount();
        // Бонус CUSTOM_COIN_GAIN (MythicLib) — только за поднятые с земли монеты, не за перевод из инвентаря.
        final long total = CoinBonus.apply(p, value * amount, this.context.logger());
        final Ledger.Result r = this.treasury.deposit(p.getUniqueId(), p.getName(), total);
        if (!r.ok()) {
            this.refused(p, r);
            return;
        }
        item.remove();
        this.feedback(p, total, r.balance());
    }

    /** Зачисление не прошло (потолок счёта, отказ провайдера): монета остаётся; в лог — не чаще раза в минуту. */
    private void refused(final Player p, final Ledger.Result r) {
        final long now = System.currentTimeMillis();
        if (now - this.lastRefusalLog > 60_000) {
            this.lastRefusalLog = now;
            this.context.logger().warning("Монеты не зачислены игроку " + p.getName() + ": " + r.error());
        }
    }

    private volatile long lastRefusalLog;

    /** Поток игрока: звук и actionbar. */
    private void feedback(final Player p, final long total, final long balance) {
        final Settings s = this.settings.get();
        LevelService.playSound(p, s.coins().sound(), s.coins().volume(), s.coins().pitch());
        if (!s.coins().actionbar().isBlank()) {
            final var cur = s.economy().currency();
            p.sendActionBar(Text.parse(s.coins().actionbar(), Text.p("amount", cur.number(total)),
                Text.p("name", cur.nameFor(total)), Text.p("balance", cur.format(balance))));
        }
    }

    /** Воронки и вагонетки с воронкой (поток региона). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHopper(final InventoryPickupItemEvent e) {
        if (this.coinsOn() && !this.settings.get().coins().hoppers() && Coins.value(e.getItem().getItemStack()) > 0) {
            e.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------------------------------- инвентарь

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(final InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || !this.coinsOn() || !this.settings.get().coins().convertClick()
            || AdminMode.isOn(p)) {
            return; // режим админа (/rpgadmin): монеты остаются предметами
        }
        if (Coins.isCoin(e.getCurrentItem()) || Coins.isCoin(e.getCursor())) {
            this.context.runOnceForPlayer(p, () -> this.convertInventory(p));
        }
    }

    /** Поток игрока: монеты из инвентаря — на баланс. */
    void convertInventory(final Player p) {
        if (!p.isOnline() || !this.coinsOn()) {
            return;
        }
        final PlayerInventory inv = p.getInventory();
        long total = 0;
        long balance = -1;
        final ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            final long v = Coins.value(contents[i]);
            if (v <= 0) {
                continue;
            }
            final long sum = v * contents[i].getAmount();
            final Ledger.Result r = this.treasury.deposit(p.getUniqueId(), p.getName(), sum);
            if (!r.ok()) {
                this.refused(p, r);
                break;
            }
            inv.setItem(i, null);
            total += sum;
            balance = r.balance();
        }
        if (total > 0) {
            this.feedback(p, total, balance);
        }
    }

    // ------------------------------------------------------------------------------------------- ванильный опыт

    @EventHandler(priority = EventPriority.HIGH)
    public void onExp(final PlayerExpChangeEvent e) {
        final Settings s = this.settings.get();
        if (!s.modules().levels() || e.getAmount() <= 0) {
            return;
        }
        final Settings.Levels l = s.levels();
        if (l.orbs()) {
            final long gain = Math.round(e.getAmount() * l.orbRatio());
            if (gain > 0) {
                this.levels.addExp(e.getPlayer().getUniqueId(), e.getPlayer().getName(), gain, true);
            }
        }
        if ((l.orbs() && l.orbCancel()) || l.vanillaBar()) {
            e.setAmount(0);
        }
    }
}
