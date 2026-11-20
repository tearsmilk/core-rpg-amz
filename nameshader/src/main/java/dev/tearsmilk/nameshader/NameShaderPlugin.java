package dev.tearsmilk.nameshader;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class NameShaderPlugin extends JavaPlugin {

    private Settings settings;
    private Texts texts;
    private DisplayStyler styler;
    private TestSpawner tests;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();
        settings = Settings.from(getConfig());
        texts = new Texts(getConfig());
        styler = new DisplayStyler(this);
        tests = new TestSpawner(this);
        getServer().getPluginManager().registerEvents(styler, this);
        Commands commands = new Commands(this);
        PluginCommand cmd = getCommand("nameshader");
        if (cmd != null) {
            cmd.setExecutor(commands);
            cmd.setTabCompleter(commands);
        }
        tests.removeAll();
        styler.applyLoaded();
    }

    @Override
    public void onDisable() {
        if (tests != null) {
            tests.removeAll();
        }
        if (styler != null) {
            styler.restoreLoaded();
        }
    }

    public void reloadAll() {
        reloadConfig();
        settings = Settings.from(getConfig());
        texts.reload(getConfig());
        styler.applyLoaded();
    }

    public Settings settings() {
        return settings;
    }

    public Texts texts() {
        return texts;
    }

    public DisplayStyler styler() {
        return styler;
    }

    public TestSpawner tests() {
        return tests;
    }
}
