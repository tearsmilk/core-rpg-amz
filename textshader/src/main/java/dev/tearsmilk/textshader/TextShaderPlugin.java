package dev.tearsmilk.textshader;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class TextShaderPlugin extends JavaPlugin {

    private Msg msg;
    private Choices choices;
    private Menu menu;
    private final Set<UUID> tabbed = ConcurrentHashMap.newKeySet();
    private final Set<UUID> named = ConcurrentHashMap.newKeySet();
    private ShaderHooks.Report pack;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        migrate();
        pack = ShaderHooks.run(this, getConfig().getBoolean("pack.install", true), getConfig().getString("pack.dir", ""), getConfig().getBoolean("pack.reload-nexo", true));
        msg = new Msg(getConfig());
        choices = new Choices(getName());
        menu = new Menu(this);
        TextShaderApi.bind(choices);
        registerPermissions();
        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(menu, this);
        pm.registerEvents(new ChatListener(this), this);
        PluginCommand cmd = getCommand("textshader");
        if (cmd != null) {
            Commands c = new Commands(this);
            cmd.setExecutor(c);
            cmd.setTabCompleter(c);
        }
        if (pm.isPluginEnabled("PlaceholderAPI")) {
            PapiHook.register(this);
            getLogger().info("PlaceholderAPI: %textshader_...% registered");
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            choices.load(p);
            applyNick(p);
        }
        getLogger().info("textshader: " + Effect.visible().size() + " effects, " + Palette.values().length + " colours");
    }

    ShaderHooks.Report pack() {
        return pack;
    }

    @Override
    public void onDisable() {
        TextShaderApi.bind(null);
    }

    static final int CONFIG_VERSION = 2;
    static final List<String> MIGRATED = List.of("menu-", "usage-set", "usage-nick", "help", "effect-");

    private void migrate() {
        FileConfiguration c = getConfig();
        if (c.getInt("config-version", 1) >= CONFIG_VERSION || c.getDefaults() == null) {
            return;
        }
        ConfigurationSection have = c.getConfigurationSection("messages");
        if (have != null) {
            for (String k : have.getKeys(false)) {
                if (k.startsWith("menu-")) {
                    have.set(k, null);
                }
            }
        }
        ConfigurationSection defs = c.getDefaults().getConfigurationSection("messages");
        if (defs != null) {
            for (String k : defs.getKeys(false)) {
                if (MIGRATED.stream().anyMatch(k::startsWith)) {
                    c.set("messages." + k, defs.getString(k));
                }
            }
        }
        if (Objects.equals(c.getString("menu-sample"), "Пример текста")) {
            c.set("menu-sample", c.getDefaults().getString("menu-sample"));
        }
        c.set("chat.nick", c.getBoolean("chat.nick", true));
        c.set("config-version", CONFIG_VERSION);
        File file = new File(getDataFolder(), "config.yml");
        if (file.isFile()) {
            try {
                Files.copy(file.toPath(), new File(getDataFolder(), "config.v1.yml").toPath(), StandardCopyOption.REPLACE_EXISTING);
                getLogger().info("config.yml: old config saved as config.v1.yml");
            } catch (IOException ex) {
                getLogger().warning("config.yml: backup failed: " + ex.getMessage());
            }
        }
        saveConfig();
        getLogger().info("config.yml: messages updated to version " + CONFIG_VERSION);
    }

    private void registerPermissions() {
        PluginManager pm = getServer().getPluginManager();
        PermissionDefault def = getConfig().getBoolean("effects-default", true) ? PermissionDefault.TRUE : PermissionDefault.OP;
        Map<String, Boolean> children = new LinkedHashMap<>();
        for (Effect e : Effect.visible()) {
            children.put(e.permission(), true);
            Permission existing = pm.getPermission(e.permission());
            if (existing != null) {
                existing.setDefault(def);
            } else {
                pm.addPermission(new Permission(e.permission(), "textshader effect " + e.key(), def));
            }
        }
        Permission all = pm.getPermission("textshader.effect.*");
        if (all == null) {
            pm.addPermission(new Permission("textshader.effect.*", "All textshader effects", def, children));
        } else {
            all.setDefault(def);
            all.getChildren().putAll(children);
            all.recalculatePermissibles();
        }
    }

    public void reload() {
        reloadConfig();
        pack = ShaderHooks.run(this, getConfig().getBoolean("pack.install", true), getConfig().getString("pack.dir", ""), getConfig().getBoolean("pack.reload-nexo", true));
        msg.reload(getConfig());
        registerPermissions();
        for (Player p : Bukkit.getOnlinePlayers()) {
            applyNick(p);
        }
    }

    public Msg msg() {
        return msg;
    }

    public Choices choices() {
        return choices;
    }

    public Menu menu() {
        return menu;
    }

    public void apply(Player p, Choices.Target target, FxSpec spec, boolean notify) {
        choices.set(p, target, spec);
        if (target == Choices.Target.NICK) {
            applyNick(p);
        }
        if (!notify) {
            return;
        }
        if (spec == null) {
            msg.send(p, "effect-off", "target", target.title());
        } else {
            String sample = target == Choices.Target.NICK ? p.getName() : getConfig().getString("menu-sample", "Привет всем!");
            msg.send(p, "effect-set", "target", target.title(), "effect", spec.effect().title(),
                    "colour", spec.palette().title(), "sample", FxText.render(sample, spec));
        }
    }

    public FxSpec active(Player p, Choices.Target target) {
        FxSpec spec = choices.get(p.getUniqueId(), target);
        boolean ok = spec != null && p.hasPermission("textshader.use") && p.hasPermission(spec.effect().permission());
        return ok ? spec : null;
    }

    public void applyNick(Player p) {
        FxSpec spec = active(p, Choices.Target.NICK);
        Component name = spec != null ? FxText.render(p.getName(), spec) : null;
        UUID id = p.getUniqueId();
        if (name != null && getConfig().getBoolean("nick.tab", true)) {
            p.playerListName(name);
            tabbed.add(id);
        } else if (tabbed.remove(id)) {
            p.playerListName(null);
        }
        if (name != null && getConfig().getBoolean("nick.display-name", false)) {
            p.displayName(name);
            named.add(id);
        } else if (named.remove(id)) {
            p.displayName(null);
        }
    }

    public void forgetNick(UUID id) {
        tabbed.remove(id);
        named.remove(id);
    }
}
