package core.amazake.systems.corevault;

import core.amazake.systems.api.SystemContext;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Сообщения в MiniMessage с префиксом системы. */
public final class Text {

    public static final MiniMessage MM = MiniMessage.miniMessage();

    private Text() {
    }

    public static Component parse(final String mini, final TagResolver... resolvers) {
        return MM.deserialize(mini == null ? "" : mini, resolvers);
    }

    public static TagResolver p(final String key, final Object value) {
        return Placeholder.unparsed(key, String.valueOf(value));
    }

    public static Component message(final Settings s, final String key, final TagResolver... resolvers) {
        final String template = s.message(key);
        if (template == null || template.isBlank()) {
            return null;
        }
        return MM.deserialize(s.prefix() + template, resolvers);
    }

    /**
     * Отправить из любого потока: игроку — в его потоке, консоли и прочим — в глобальном. Пустой шаблон — ничего.
     */
    public static void reply(final SystemContext ctx, final CommandSender to, final Settings s, final String key,
                             final TagResolver... resolvers) {
        final Component c = message(s, key, resolvers);
        if (c != null) {
            send(ctx, to, c);
        }
    }

    public static void send(final SystemContext ctx, final CommandSender to, final Component c) {
        if (to instanceof Player p) {
            ctx.runOnceForPlayer(p, () -> p.sendMessage(c));
        } else {
            Bukkit.getGlobalRegionScheduler().execute(ctx.plugin(), () -> to.sendMessage(c));
        }
    }
}
