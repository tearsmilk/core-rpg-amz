package dev.tearsmilk.coreshaders;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class CoreShadersPlugin extends JavaPlugin {
    private Settings settings;
    private Keys keys;
    private Wand wand;
    private Effects effects;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = new Settings(getConfig());
        keys = new Keys(this);
        wand = new Wand(keys);
        effects = new Effects(this, keys);
        CoreCommand cmd = new CoreCommand(this);
        PluginCommand pc = getCommand("coreshaders");
        if (pc != null) {
            pc.setExecutor(cmd);
            pc.setTabCompleter(cmd);
        }
        getServer().getPluginManager().registerEvents(new WandListener(this), this);
        getServer().getScheduler().runTaskLater(this, () -> {
            int n = effects.verifyAll();
            if (n > 0) {
                getLogger().info("Hitboxes fixed: " + n);
            }
        }, 20L);
        getServer().getScheduler().runTaskTimer(this, effects::verifyAll, 600L, 600L);
        getLogger().info("coreshaders by tearsmilk - images: " + String.join(", ", settings.images));
    }

    public void reload() {
        reloadConfig();
        settings = new Settings(getConfig());
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
}
