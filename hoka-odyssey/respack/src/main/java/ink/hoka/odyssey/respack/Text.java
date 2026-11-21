package ink.hoka.odyssey.respack;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/** Текст из конфига: цвета через &amp; (&amp;a, &amp;c, &amp;#RRGGBB), подстановки {имя} — как обычный текст (без цветов). */
final class Text {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&').hexColors().build();

    private Text() { }

    /** pairs: ключ, значение, ключ, значение… */
    static Component of(String template, String... pairs) {
        Component c = LEGACY.deserialize(template == null ? "" : template);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            String key = "{" + pairs[i] + "}";
            String value = pairs[i + 1] == null ? "" : pairs[i + 1];
            c = c.replaceText(TextReplacementConfig.builder().matchLiteral(key).replacement(value).build());
        }
        return c;
    }
}
