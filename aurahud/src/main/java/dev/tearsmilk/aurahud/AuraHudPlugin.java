package dev.tearsmilk.aurahud;

import dev.tearsmilk.aurahud.layout.LayoutModule;
import dev.tearsmilk.aurahud.layout.Presets;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.Map;

public final class AuraHudPlugin extends JavaPlugin {

    private final ModuleRegistry modules = new ModuleRegistry();
    private final LayoutModule layout = new LayoutModule();
    private Texts texts;
    private Presets presets;
    private volatile ShaderHooks.Report pack;
    private volatile boolean installing;
    private boolean queued;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();
        texts = new Texts(getConfig());
        presets = new Presets(getDataFolder().toPath().resolve("presets"));
        modules.register(layout);
        modules.enableAll(this);
        installPack(true);
        Commands commands = new Commands(this);
        PluginCommand cmd = getCommand("aurahud");
        if (cmd != null) {
            cmd.setExecutor(commands);
            cmd.setTabCompleter(commands);
        }
    }

    @Override
    public void onDisable() {
        modules.disableAll();
    }

    public void reloadAll() {
        reloadConfig();
        texts.reload(getConfig());
        modules.reloadAll(this);
        installPack(false);
    }

    public void saveAndApply() {
        saveConfig();
        reloadAll();
    }

    private void installPack(boolean sync) {
        if (!sync && installing) {
            queued = true;
            return;
        }
        PackTarget target = target();
        Map<String, byte[]> files = modules.packFiles(target);
        if (sync) {
            pack = ShaderHooks.run(this, packInstall(), packDir(), reloadNexo(), files);
            return;
        }
        installing = true;
        ShaderHooks.runAsync(this, packInstall(), packDir(), reloadNexo(), files, r -> {
            pack = r;
            installing = false;
            if (queued && isEnabled()) {
                queued = false;
                installPack(false);
            }
        });
    }

    private PackTarget target() {
        Path work = getDataFolder().toPath().toAbsolutePath();
        if (!packInstall()) {
            return new PackTarget(null, work, getLogger());
        }
        String dir = packDir();
        Path assets = dir == null || dir.isBlank()
                ? work.getParent().resolve("Nexo").resolve("pack").resolve("assets")
                : Path.of(dir).toAbsolutePath();
        return new PackTarget(assets, work, getLogger());
    }

    private boolean packInstall() {
        return getConfig().getBoolean("pack.install", true);
    }

    private String packDir() {
        return getConfig().getString("pack.dir", "");
    }

    private boolean reloadNexo() {
        return getConfig().getBoolean("pack.reload-nexo", true);
    }

    public Texts texts() {
        return texts;
    }

    public ModuleRegistry modules() {
        return modules;
    }

    public LayoutModule layout() {
        return layout;
    }

    public Presets presets() {
        return presets;
    }

    boolean installing() {
        return installing;
    }

    ShaderHooks.Report pack() {
        return pack;
    }
}
