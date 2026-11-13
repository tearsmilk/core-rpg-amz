package core.amazake.systems.corerpg.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;

/** Тексты в MiniMessage. Пользовательский ввод (ники, названия) подставляйте через {@code Placeholder.unparsed}. */
public final class Text {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static volatile String prefix = "<dark_gray>[<gold>CoreRpg</gold>]</dark_gray> ";

    private Text() {
    }

    public static void setPrefix(final String value) {
        prefix = value == null ? "" : value;
    }

    public static String prefix() {
        return prefix;
    }

    /** MiniMessage → компонент. */
    public static Component mm(final String input, final TagResolver... resolvers) {
        return MM.deserialize(input == null ? "" : input, resolvers);
    }

    /** То же, без курсива по умолчанию: для названий и лора предметов. */
    public static Component item(final String input, final TagResolver... resolvers) {
        return mm(input, resolvers).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static List<Component> lore(final List<String> lines, final TagResolver... resolvers) {
        final List<Component> out = new ArrayList<>(lines.size());
        for (final String line : lines) {
            out.add(item(line, resolvers));
        }
        return out;
    }

    /** Сообщение с префиксом системы. */
    public static void send(final CommandSender to, final String message, final TagResolver... resolvers) {
        to.sendMessage(mm(prefix + message, resolvers));
    }

    public static String plain(final Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    /** 12 → «12», 12.5 → «12.5», 0.333 → «0.33». */
    public static String num(final double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return Long.toString((long) value);
        }
        final String s = String.format(Locale.ROOT, "%.2f", value);
        return s.endsWith("0") ? s.substring(0, s.length() - 1) : s;
    }

    /** Экранирует теги MiniMessage в произвольной строке. */
    public static String escape(final String raw) {
        return MM.escapeTags(raw == null ? "" : raw);
    }
}
