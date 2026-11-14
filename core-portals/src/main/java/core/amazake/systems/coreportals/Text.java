package core.amazake.systems.coreportals;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;

/** Сообщения в MiniMessage с префиксом системы. */
final class Text {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private Text() {
    }

    static Component parse(final String mini, final TagResolver... resolvers) {
        return MM.deserialize(mini, resolvers);
    }

    /** Отправить сообщение с префиксом; пустой шаблон — ничего не отправлять. */
    static void send(final CommandSender to, final Settings s, final String template, final TagResolver... resolvers) {
        if (template == null || template.isBlank()) {
            return;
        }
        to.sendMessage(MM.deserialize(s.prefix() + template, resolvers));
    }
}
