package core.amazake.systems.corenpc;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;

/** Сообщения в MiniMessage с префиксом системы. */
public final class Text {

    public static final MiniMessage MM = MiniMessage.miniMessage();

    private Text() {
    }

    public static Component parse(final String mini, final TagResolver... resolvers) {
        return MM.deserialize(mini == null ? "" : mini, resolvers);
    }

    public static Component message(final Settings s, final String key, final TagResolver... resolvers) {
        final String template = s.message(key);
        if (template == null || template.isBlank()) {
            return null;
        }
        return MM.deserialize(s.prefix() + template, resolvers);
    }

    /** Отправить сообщение с префиксом; пустой шаблон — ничего. Вызывать в потоке получателя. */
    public static void send(final CommandSender to, final Settings s, final String key, final TagResolver... resolvers) {
        final Component c = message(s, key, resolvers);
        if (c != null) {
            to.sendMessage(c);
        }
    }
}
