package dev.tearsmilk.coreshaders;

import java.util.List;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class CoreShadersPlugin extends JavaPlugin {
    private Settings settings;
    private Keys keys;
    private Wand wand;
    private Effects effects;
    private Triggers triggers;
    private ImageManager images;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        upgradeConfig();
        settings = new Settings(getConfig());
        keys = new Keys(this);
        wand = new Wand(keys);
        effects = new Effects(this, keys);
        triggers = new Triggers(this);
        images = new ImageManager(this);
        images.prepare();
        CoreCommand cmd = new CoreCommand(this);
        PluginCommand pc = getCommand("coreshaders");
        if (pc != null) {
            pc.setExecutor(cmd);
            pc.setTabCompleter(cmd);
        }
        getServer().getPluginManager().registerEvents(new WandListener(this), this);
        getServer().getPluginManager().registerEvents(triggers, this);
        getServer().getScheduler().runTaskLater(this, () -> {
            int n = effects.verifyAll(true);
            if (n > 0) {
                getLogger().info("Hitboxes fixed: " + n);
            }
        }, 20L);
        getServer().getScheduler().runTaskTimer(this, () -> effects.verifyAll(false), 600L, 600L);
        getLogger().info("coreshaders by tearsmilk - images: " + String.join(", ", settings.images));
    }

    public void reload() {
        reloadConfig();
        upgradeConfig();
        settings = new Settings(getConfig());
    }

    private void upgradeConfig() {
        int before = getConfig().getInt("config-version", 1);
        List<String> added = Settings.upgrade(getConfig());
        if (before >= Settings.CONFIG_VERSION) {
            return;
        }
        saveConfig();
        if (!added.isEmpty()) {
            getLogger().info("config.yml updated, new images added: " + String.join(", ", added));
        }
    }

    public Settings settings() {
        return settings;
    }

    public Wand wand() {
        return wand;
    }

    public Effects effects() {
        return effects;
    }

    public Triggers triggers() {
        return triggers;
    }

    public ImageManager images() {
        return images;
    }
}
