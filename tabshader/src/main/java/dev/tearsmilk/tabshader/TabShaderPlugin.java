package dev.tearsmilk.tabshader;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class TabShaderPlugin extends JavaPlugin implements Listener {

    private Settings settings;
    private Texts texts;
    private Vars vars;
    private Widths widths;
    private Toggles toggles;
    private Hud hud;
    private TabNames names;
    private TabLimiter limiter;
    private BukkitTask task;
    private ShaderHooks.Report pack;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        pack = ShaderHooks.run(this, getConfig().getBoolean("pack.install", true), getConfig().getString("pack.dir", ""), getConfig().getBoolean("pack.reload-nexo", true));
        widths = Widths.load();
        toggles = new Toggles(getName());
        texts = new Texts(getConfig());
        hud = new Hud(this);
        names = new TabNames();
        limiter = new TabLimiter();
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            texts.external(PapiBridge::apply);
            getLogger().info("PlaceholderAPI: placeholders in texts enabled");
        }
        applyConfig();
        TabShaderApi.bind(this);
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand cmd = getCommand("tabshader");
        if (cmd != null) {
            Commands c = new Commands(this);
            cmd.setExecutor(c);
            cmd.setTabCompleter(c);
        }
        getLogger().info("tabshader: tab, board, bossbar every " + settings.period() + " ticks, "
                + widths.size() + " glyph widths");
    }

    ShaderHooks.Report pack() {
        return pack;
    }

    @Override
    public void onDisable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (hud != null) {
            hud.clearAll();
        }
        if (limiter != null) {
            limiter.relistAll();
        }
        if (names != null) {
            names.restoreAll();
        }
        TabShaderApi.bind(null);
    }

    private void applyConfig() {
        settings = Settings.of(getConfig());
        vars = new Vars(settings.server(), settings.zone(), settings.timeFormat(), settings.dateFormat());
        if (task != null) {
            task.cancel();
        }
        task = Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, settings.period());
    }

    void tick() {
        if (settings.tab()) {
            names.tick(settings.maxNameLength(), widths);
            limiter.reconcile(settings.tabLimit());
        }
        hud.tick();
    }

    void reload() {
        reloadConfig();
        pack = ShaderHooks.run(this, getConfig().getBoolean("pack.install", true), getConfig().getString("pack.dir", ""), getConfig().getBoolean("pack.reload-nexo", true));
        texts.reload(getConfig());
        hud.clearAll();
        limiter.relistAll();
        names.restoreAll();
        applyConfig();
        tick();
    }

    private void retab() {
        if (settings.tab()) {
            limiter.reconcile(settings.tabLimit());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (settings.tab()) {
            names.update(p, settings.maxNameLength(), widths);
        }
        retab();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (p.isOnline()) {
                if (settings.tab()) {
                    names.update(p, settings.maxNameLength(), widths);
                }
                retab();
                hud.update(p);
            }
        }, 10L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        hud.forget(p);
        limiter.quit(p);
        names.forget(p);
        Bukkit.getScheduler().runTask(this, this::retab);
    }

    Settings settings() {
        return settings;
    }

    Texts texts() {
        return texts;
    }

    Vars vars() {
        return vars;
    }

    Widths widths() {
        return widths;
    }

    Toggles toggles() {
        return toggles;
    }

    Hud hud() {
        return hud;
    }

    TabNames names() {
        return names;
    }

    TabLimiter limiter() {
        return limiter;
    }
}
