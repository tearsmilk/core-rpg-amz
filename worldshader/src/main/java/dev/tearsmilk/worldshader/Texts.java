package dev.tearsmilk.worldshader;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;

public final class Texts {

    private final MiniMessage mm = MiniMessage.miniMessage();
    private ConfigurationSection config;

    public Texts(ConfigurationSection config) {
        this.config = config;
    }

    public void reload(ConfigurationSection config) {
        this.config = config;
    }

    public Component parse(String text) {
        return mm.deserialize(text == null ? "" : text);
    }

    public String raw(String key) {
        String s = config.getString("messages." + key);
        return s == null ? key : s;
    }

    public Component get(String key, String... vars) {
        List<TagResolver> r = new ArrayList<>();
        for (int i = 0; i + 1 < vars.length; i += 2) {
            r.add(Placeholder.unparsed(vars[i], vars[i + 1]));
        }
        return mm.deserialize(raw(key), TagResolver.resolver(r));
    }

    public void send(CommandSender to, String key, String... vars) {
        to.sendMessage(get(key, vars));
    }
}
