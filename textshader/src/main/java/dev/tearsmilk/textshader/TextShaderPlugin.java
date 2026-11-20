package dev.tearsmilk.textshader;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.LinkedHashMap;
import java.util.Map;

public final class TextShaderPlugin extends JavaPlugin {

    private Msg msg;
    private Choices choices;
    private Menu menu;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        msg = new Msg(getConfig());
        choices = new Choices(this);
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

    @Override
    public void onDisable() {
        TextShaderApi.bind(null);
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
            String sample = target == Choices.Target.NICK ? p.getName() : spec.effect().title();
            msg.send(p, "effect-set", "target", target.title(), "effect", spec.effect().title(),
                    "colour", spec.palette().title(), "sample", FxText.render(sample, spec));
        }
    }

    public void applyNick(Player p) {
        FxSpec spec = choices.get(p.getUniqueId(), Choices.Target.NICK);
        boolean ok = spec != null && p.hasPermission("textshader.use") && p.hasPermission(spec.effect().permission());
        Component name = ok ? FxText.render(p.getName(), spec) : null;
        if (getConfig().getBoolean("nick.tab", true)) {
            p.playerListName(name);
        }
        if (getConfig().getBoolean("nick.display-name", false)) {
            p.displayName(name);
        }
    }
}
