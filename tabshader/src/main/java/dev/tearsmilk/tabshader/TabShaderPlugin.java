package dev.tearsmilk.tabshader;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
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
    private BukkitTask task;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        widths = Widths.load();
        toggles = new Toggles(getName());
        texts = new Texts(getConfig());
        hud = new Hud(this);
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

    @Override
    public void onDisable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (hud != null) {
            hud.clearAll();
        }
        TabShaderApi.bind(null);
    }

    private void applyConfig() {
        settings = Settings.of(getConfig());
        vars = new Vars(settings.server(), settings.zone(), settings.timeFormat(), settings.dateFormat());
        if (task != null) {
            task.cancel();
        }
        task = Bukkit.getScheduler().runTaskTimer(this, hud::tick, 20L, settings.period());
    }

    void reload() {
        reloadConfig();
        texts.reload(getConfig());
        hud.clearAll();
        applyConfig();
        hud.tick();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (p.isOnline()) {
                hud.update(p);
            }
        }, 10L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        hud.forget(e.getPlayer());
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
}
