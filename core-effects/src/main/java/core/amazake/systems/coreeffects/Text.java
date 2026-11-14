package core.amazake.systems.coreeffects;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;

/** Сообщения в MiniMessage с префиксом системы. */
public final class Text {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private Text() {
    }

    public static Component parse(final String mini, final TagResolver... resolvers) {
        return MM.deserialize(mini, resolvers);
    }

    /** Для имени и описания предмета: без курсива по умолчанию. */
    public static Component item(final String mini) {
        return MM.deserialize(mini).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    /** Отправить сообщение с префиксом; пустой шаблон — ничего не отправлять. */
    public static void send(final CommandSender to, final Settings s, final String key, final TagResolver... resolvers) {
        final String template = s.messages().get(key);
        if (template == null || template.isBlank()) {
            return;
        }
        to.sendMessage(MM.deserialize(s.prefix() + template, resolvers));
    }
}
