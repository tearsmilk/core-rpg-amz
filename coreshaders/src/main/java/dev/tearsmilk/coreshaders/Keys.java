package dev.tearsmilk.coreshaders;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

public final class Keys {
    public final NamespacedKey effect;
    public final NamespacedKey shape;
    public final NamespacedKey image;
    public final NamespacedKey color;
    public final NamespacedKey face;
    public final NamespacedKey block;
    public final NamespacedKey link;
    public final NamespacedKey owner;
    public final NamespacedKey wand;
    public final NamespacedKey rim;
    public final NamespacedKey commands;
    public final NamespacedKey trigger;
    public final NamespacedKey cooldown;
    public final NamespacedKey permission;
    public final NamespacedKey message;
    public final NamespacedKey group;

    public Keys(Plugin plugin) {
        effect = new NamespacedKey(plugin, "effect");
        shape = new NamespacedKey(plugin, "shape");
        image = new NamespacedKey(plugin, "image");
        color = new NamespacedKey(plugin, "color");
        face = new NamespacedKey(plugin, "face");
        block = new NamespacedKey(plugin, "block");
        link = new NamespacedKey(plugin, "link");
        owner = new NamespacedKey(plugin, "owner");
        wand = new NamespacedKey(plugin, "wand");
        rim = new NamespacedKey(plugin, "rim");
        commands = new NamespacedKey(plugin, "commands");
        trigger = new NamespacedKey(plugin, "trigger");
        cooldown = new NamespacedKey(plugin, "cooldown");
        permission = new NamespacedKey(plugin, "permission");
        message = new NamespacedKey(plugin, "message");
        group = new NamespacedKey(plugin, "group");
    }
}
