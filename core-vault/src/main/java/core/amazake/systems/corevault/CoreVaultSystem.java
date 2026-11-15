package core.amazake.systems.corevault;

import core.amazake.systems.api.AmazakeSystem;
import core.amazake.systems.api.SystemContext;
import core.amazake.systems.corevault.cmd.EcoCommand;
import core.amazake.systems.corevault.cmd.Env;
import core.amazake.systems.corevault.cmd.LevelCommand;
import core.amazake.systems.corevault.cmd.MoneyCommands;
import core.amazake.systems.corevault.data.Account;
import core.amazake.systems.corevault.data.AccountStore;
import core.amazake.systems.corevault.data.TopCache;
import core.amazake.systems.corevault.eco.Treasury;
import core.amazake.systems.corevault.level.LevelService;
import core.amazake.systems.corevault.money.Money;
import core.amazake.systems.corevault.papi.PapiHook;
import core.amazake.systems.corevault.papi.Placeholders;
import core.amazake.systems.corevault.vault.VaultHook;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.plugin.Plugin;

/**
 * core-vault — экономика (баланс, переводы, топ, провайдер Vault), свои уровни с опытом и физические монеты,
 * которые при подъёме сразу идут на баланс. Модули включаются в config.yml и применяются при reload.
 */
public final class CoreVaultSystem implements AmazakeSystem, Listener {

    private final AtomicReference<Settings> settings = new AtomicReference<>();
    private volatile SystemContext context;
    private volatile AccountStore store;
    private volatile Treasury treasury;
    private volatile LevelService levels;
    private volatile PapiHook papi;
    private final TopCache top = new TopCache();
    private volatile ScheduledTask saveTask;
    private volatile ScheduledTask topTask;
    private volatile int saveInterval;
    private volatile int topInterval;
    private volatile Map<String, String> defaultMessages = Map.of();
    private volatile boolean closed;

    @Override
    public void enable(final SystemContext context) throws IOException {
        this.context = context;
        this.closed = false;
        this.defaultMessages = bundledMessages();
        this.loadSettings();
        final Settings s = this.settings.get();
        final AccountStore st = new AccountStore(context.dataFolder().resolve("players.yml"), s.economy().currency().decimals());
        // Счета читаются один раз при включении (до любых операций): дальше всё в памяти, запись — вне потоков тика.
        final int n = st.load(w -> context.logger().warning(w));
        this.store = st;
        final VaultHook vault = new VaultHook(context.logger(), context.plugin());
        final Treasury t = new Treasury(context.logger(), st, this.settings::get, vault);
        this.treasury = t;
        final LevelService ls = new LevelService(context, this.settings::get, t.internal());
        this.levels = ls;
        final Env env = new Env(context, this.settings::get, st, t, ls, this.top, this::reloadCommand, this::importVault);
        context.registerListener(new GameListener(context, this.settings::get, st, t, ls));
        context.registerListener(this);
        context.registerCommand(new MoneyCommands.Balance(env));
        context.registerCommand(new MoneyCommands.Pay(env));
        context.registerCommand(new MoneyCommands.Top(env));
        context.registerCommand(new EcoCommand(env, this::dropAt));
        context.registerCommand(new LevelCommand(env));
        for (final var p : Bukkit.getOnlinePlayers()) {
            st.rename(t.internal().account(p.getUniqueId(), p.getName()), p.getName());
        }
        t.refresh();
        this.papi = new PapiHook(context.logger(), "Плейсхолдеры core-vault: идентификатор corevault уже занят.");
        this.hookPapi();
        this.startTimers();
        context.logger().info("core-vault включён: счетов " + n + ". Модули: " + s.modules().describe() + ".");
    }

    @Override
    public void disable(final SystemContext context) {
        this.closed = true;
        for (final ScheduledTask task : new ScheduledTask[] {this.saveTask, this.topTask}) {
            if (task != null) {
                task.cancel();
            }
        }
        this.saveTask = null;
        this.topTask = null;
        final PapiHook p = this.papi;
        if (p != null) {
            p.unregister();
        }
        final Treasury t = this.treasury;
        if (t != null) {
            t.shutdown();
        }
        final LevelService ls = this.levels;
        if (ls != null) {
            ls.shutdown();
        }
        final AccountStore st = this.store;
        if (st != null) {
            try {
                st.save(false);
            } catch (final IOException e) {
                context.logger().log(Level.SEVERE, "core-vault: не удалось сохранить players.yml при выключении", e);
            }
        }
    }

    @Override
    public void reload(final SystemContext context) {
        this.reloadNow();
    }

    /** Глобальный поток. */
    private void reloadNow() {
        this.loadSettings();
        final Settings s = this.settings.get();
        this.store.rescale(s.economy().currency().decimals());
        this.treasury.refresh();
        this.hookPapi();
        this.startTimers();
        this.computeTop();
        this.context.logger().info("core-vault: настройки перечитаны. Модули: " + s.modules().describe() + ".");
    }

    /** /ceco reload из любого потока: применяется в глобальном. */
    private void reloadCommand(final CommandSender sender) {
        Bukkit.getGlobalRegionScheduler().execute(this.context.plugin(), () -> {
            if (this.closed) {
                return;
            }
            this.reloadNow();
            Text.reply(this.context, sender, this.settings.get(), "reloaded",
                Text.p("modules", this.settings.get().modules().describe()));
        });
    }

    private void loadSettings() {
        final YamlConfiguration y = this.context.loadConfig();
        this.settings.set(Settings.from(y, this.defaultMessages, w -> this.context.logger().warning("config.yml: " + w)));
    }

    /** Сообщения встроенного config.yml (ключи, которых нет в файле на сервере). Ресурс читается без кеша jar. */
    private static Map<String, String> bundledMessages() {
        final URL url = CoreVaultSystem.class.getClassLoader().getResource("config.yml");
        if (url == null) {
            return Map.of();
        }
        try {
            final URLConnection c = url.openConnection();
            c.setUseCaches(false);
            try (InputStream in = c.getInputStream()) {
                final YamlConfiguration y = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
                return Map.copyOf(Settings.messagesOf(y));
            }
        } catch (final IOException e) {
            return Map.of();
        }
    }

    private void startTimers() {
        final Settings s = this.settings.get();
        if (this.saveTask == null || this.saveInterval != s.saveIntervalSeconds()) {
            if (this.saveTask != null) {
                this.saveTask.cancel();
            }
            this.saveInterval = s.saveIntervalSeconds();
            this.saveTask = this.context.runAsyncTimer(t -> this.saveNow(), Duration.ofSeconds(s.saveIntervalSeconds()));
        }
        if (this.topTask == null || this.topInterval != s.economy().topRefreshSeconds()) {
            if (this.topTask != null) {
                this.topTask.cancel();
            }
            this.topInterval = s.economy().topRefreshSeconds();
            this.topTask = this.context.runAsyncTimer(t -> this.computeTop(), Duration.ofSeconds(this.topInterval));
        }
    }

    /** Вне потоков тика. */
    private void saveNow() {
        try {
            this.store.save(false);
        } catch (final IOException | RuntimeException e) {
            this.context.logger().log(Level.WARNING, "core-vault: не удалось сохранить players.yml (повторю)", e);
        }
    }

    /** Пересчитать топ вне потоков тика. */
    private void computeTop() {
        final AccountStore st = this.store;
        final Treasury t = this.treasury;
        if (st == null || t == null) {
            return;
        }
        Bukkit.getAsyncScheduler().runNow(this.context.plugin(), task -> {
            try {
                this.top.compute(st.all(), t::balance, this.settings.get().economy().topSize());
            } catch (final RuntimeException e) {
                this.context.logger().log(Level.WARNING, "core-vault: топ не пересчитан", e);
            }
        });
    }

    /** /ceco drop: монеты в точке, в потоке её региона. */
    private void dropAt(final Location at, final Integer count) {
        final Settings s = this.settings.get();
        Bukkit.getRegionScheduler().execute(this.context.plugin(), at, () -> GameListener.dropCoins(s, at, count, s.coins().value()));
    }

    // ------------------------------------------------------------------------------------------- импорт

    /** /ceco import vault [replace]: балансы из plugins/Vault/balances.yml (Vault 2.x) в свои счета. Вне потоков тика. */
    private void importVault(final CommandSender sender, final Boolean replace) {
        final Plugin vault = Bukkit.getPluginManager().getPlugin("Vault");
        final Path file = (vault != null ? vault.getDataFolder().toPath() : Path.of("plugins", "Vault")).resolve("balances.yml");
        Text.reply(this.context, sender, this.settings.get(), "import-started", Text.p("file", file.toString()));
        Bukkit.getAsyncScheduler().runNow(this.context.plugin(), task -> {
            try {
                if (!Files.isRegularFile(file)) {
                    throw new IOException("нет файла " + file);
                }
                final YamlConfiguration y = YamlConfiguration.loadConfiguration(file.toFile());
                final ConfigurationSection sec = y.getConfigurationSection("balances");
                if (sec == null) {
                    throw new IOException("в файле нет раздела balances");
                }
                final Settings s = this.settings.get();
                final int decimals = s.economy().currency().decimals();
                int count = 0;
                int skipped = 0;
                for (final String key : sec.getKeys(false)) {
                    final UUID id;
                    try {
                        id = UUID.fromString(key);
                    } catch (final IllegalArgumentException e) {
                        skipped++;
                        continue;
                    }
                    if (!sec.isDouble(key) && !sec.isInt(key) && !sec.isLong(key)) {
                        skipped++;
                        continue;
                    }
                    final long amount = Math.min(s.economy().maxBalance(), Math.max(0, Money.fromDouble(sec.getDouble(key), decimals)));
                    final Account existing = this.store.get(id);
                    if (!replace && existing != null && existing.balance() != s.economy().startBalance()) {
                        skipped++;
                        continue;
                    }
                    final OfflinePlayer op = Bukkit.getOfflinePlayer(id);
                    final String name = existing != null && !existing.name().isEmpty() ? existing.name()
                        : op.getName() == null ? "" : op.getName();
                    this.treasury.internal().set(id, name, amount);
                    count++;
                }
                this.store.save(false);
                Text.reply(this.context, sender, s, "import-done", Text.p("count", count), Text.p("skipped", skipped));
                this.context.logger().info("core-vault: импорт из " + file + ": " + count + " счетов, пропущено " + skipped + ".");
                this.computeTop();
            } catch (final IOException | RuntimeException e) {
                Text.reply(this.context, sender, this.settings.get(), "import-failed", Text.p("reason", String.valueOf(e.getMessage())));
            }
        });
    }

    // ------------------------------------------------------------------------------------------- хуки

    private void hookPapi() {
        final PapiHook p = this.papi;
        if (p == null) {
            return;
        }
        final Plugin papiPlugin = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
        if (papiPlugin == null || !papiPlugin.isEnabled()) {
            p.unregister();
            return;
        }
        if (p.isRegistered()) {
            return;
        }
        final Treasury t = this.treasury;
        final LevelService ls = this.levels;
        final Placeholders ph = new Placeholders(this.settings::get, t::balance, ls::state, this.top::get);
        p.register(papiPlugin, "corevault", "tearsmilk", this.context.info().version(),
            (OfflinePlayer player, String params) -> ph.resolve(player == null ? null : player.getUniqueId(), params));
    }

    @EventHandler
    public void onPluginEnable(final PluginEnableEvent event) {
        final String name = event.getPlugin().getName();
        if (name.equals("PlaceholderAPI")) {
            this.hookPapi();
        } else if (name.equals("Vault")) {
            this.treasury.refresh();
        }
    }

    @EventHandler
    public void onPluginDisable(final PluginDisableEvent event) {
        final String name = event.getPlugin().getName();
        if (name.equals("PlaceholderAPI")) {
            this.papi.unregister();
        } else if (name.equals("Vault")) {
            this.treasury.vault().unregister();
            this.treasury.refresh();
        }
    }

    /** Чужой провайдер экономики появился или ушёл — пересобрать в глобальном потоке. */
    @EventHandler
    public void onServiceRegister(final ServiceRegisterEvent event) {
        this.serviceChanged(event.getProvider().getService().getName(), event.getProvider().getProvider());
    }

    @EventHandler
    public void onServiceUnregister(final ServiceUnregisterEvent event) {
        this.serviceChanged(event.getProvider().getService().getName(), event.getProvider().getProvider());
    }

    private void serviceChanged(final String service, final Object provider) {
        if (!VaultHook.ECONOMY.equals(service) || this.treasury.vault().isOurs(provider) || this.closed) {
            return;
        }
        Bukkit.getGlobalRegionScheduler().execute(this.context.plugin(), () -> {
            if (!this.closed) {
                this.treasury.refresh();
            }
        });
    }
}
