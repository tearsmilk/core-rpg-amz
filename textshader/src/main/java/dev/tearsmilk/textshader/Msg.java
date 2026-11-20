package dev.tearsmilk.textshader;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;

public final class Msg {

    private final MiniMessage mm;
    private FileConfiguration config;

    public Msg(FileConfiguration config) {
        this.mm = MiniMessage.builder().editTags(b -> b.resolver(FxTag.resolver())).build();
        this.config = config;
    }

    public void reload(FileConfiguration config) {
        this.config = config;
    }

    public MiniMessage mini() {
        return mm;
    }

    public String raw(String key) {
        String s = config.getString("messages." + key);
        return s == null ? key : s;
    }

    public Component get(String key, Object... pairs) {
        List<TagResolver> resolvers = new ArrayList<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            String name = String.valueOf(pairs[i]);
            Object v = pairs[i + 1];
            if (v instanceof Component c) {
                resolvers.add(Placeholder.component(name, c));
            } else {
                resolvers.add(Placeholder.unparsed(name, String.valueOf(v)));
            }
        }
        return mm.deserialize(raw(key), TagResolver.resolver(resolvers));
    }

    public void send(CommandSender to, String key, Object... pairs) {
        to.sendMessage(get(key, pairs));
    }
}
