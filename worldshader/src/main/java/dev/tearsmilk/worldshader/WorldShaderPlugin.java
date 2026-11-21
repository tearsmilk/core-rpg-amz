package dev.tearsmilk.worldshader;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class WorldShaderPlugin extends JavaPlugin {

    private Settings settings;
    private Texts texts;
    private Zones zones;
    private PackInstaller.Report pack;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();
        texts = new Texts(getConfig());
        settings = Settings.from(getConfig());
        zones = new Zones(this);
        zones.load();
        getServer().getPluginManager().registerEvents(zones, this);
        afterPack(PackInstaller.run(this, settings));
        Commands commands = new Commands(this);
        PluginCommand cmd = getCommand("worldshader");
        if (cmd != null) {
            cmd.setExecutor(commands);
            cmd.setTabCompleter(commands);
        }
        getServer().getScheduler().runTaskLater(this, zones::scan, 20L);
        getServer().getScheduler().runTaskTimer(this, zones::scan, 600L, 600L);
        getServer().getScheduler().runTaskTimer(this, zones::tickExpiry, 20L, 20L);
    }

    @Override
    public void onDisable() {
        if (zones != null) {
            zones.save();
        }
    }

    public void reloadAll() {
        reloadConfig();
        texts.reload(getConfig());
        settings = Settings.from(getConfig());
        zones.refreshLoaded();
        PackInstaller.runAsync(this, settings, this::afterPack);
    }

    private void afterPack(PackInstaller.Report report) {
        pack = report;
        if (!report.active()) {
            getLogger().warning("Эффект зон в пак не попал (" + report.summary() + "): маркеры работают, но игроки искажения не увидят.");
        }
    }

    public Settings settings() {
        return settings;
    }

    public Texts texts() {
        return texts;
    }

    public Zones zones() {
        return zones;
    }

    PackInstaller.Report pack() {
        return pack;
    }
}
