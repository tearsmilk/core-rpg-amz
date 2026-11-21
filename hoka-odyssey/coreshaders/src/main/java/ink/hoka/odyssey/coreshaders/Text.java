package ink.hoka.odyssey.coreshaders;

import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Текст из конфига: цвета через &amp; (&amp;a, &amp;l, &amp;#RRGGBB), подстановки {имя}. Значение подстановки — обычный
 * текст (цвета из него не применяются, так что ник или команда игрока не «покрасит» сообщение) или готовый Component.
 */
final class Text {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
        .character('&').hexColors().build();

    private Text() {
    }

    static Component of(String template, Map<String, ?> values) {
        Component c = LEGACY.deserialize(template == null ? "" : template);
        for (Map.Entry<String, ?> e : values.entrySet()) {
            String key = "{" + e.getKey() + "}";
            Object v = e.getValue();
            ComponentLike rep = v instanceof ComponentLike cl ? cl : Component.text(v == null ? "" : String.valueOf(v));
            c = c.replaceText(TextReplacementConfig.builder().matchLiteral(key).replacement(rep).build());
        }
        return c;
    }

    /** Для названий и описаний предметов: без курсива, который клиент ставит по умолчанию. */
    static Component item(String template, Map<String, ?> values) {
        return of(template, values).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
