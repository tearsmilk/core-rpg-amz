package core.amazake.systems.coremechanics.tooltip;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * Плашки классов (глифы U+E500.. шрифта azerus:tooltip, рисует nexo-items/tools/class_badges.py — порядок тот же).
 * Строку описания «Для: Маг, Волшебник, …» заменяет на «Для:» и плашки; неизвестные имена остаются текстом.
 */
public final class ClassBadges {

    static final Map<String, Character> BADGES = Map.ofEntries(
        Map.entry("маг", ''), Map.entry("волшебник", ''), Map.entry("чернокнижник", ''),
        Map.entry("стрелок", ''), Map.entry("снайпер", ''), Map.entry("охотник", ''),
        Map.entry("мечник", ''), Map.entry("крестоносец", ''), Map.entry("тёмный рыцарь", ''),
        Map.entry("темный рыцарь", ''), Map.entry("ассасин", ''), Map.entry("разбойник", ''),
        Map.entry("вор", ''));

    private ClassBadges() {
    }

    /** Заменить строку «Для: …» плашками; null — строка не про классы (оставить как есть). */
    static Component replace(final String plain) {
        final String t = plain.strip();
        final int colon = t.indexOf(':');
        if (colon < 0 || !t.substring(0, colon).strip().equalsIgnoreCase("Для")) {
            return null;
        }
        final String[] names = t.substring(colon + 1).split(",");
        final List<Component> parts = new ArrayList<>();
        int known = 0;
        for (final String raw : names) {
            final String n = raw.strip();
            if (n.isEmpty()) {
                continue;
            }
            final Character ch = BADGES.get(n.toLowerCase(java.util.Locale.ROOT));
            if (!parts.isEmpty()) {
                parts.add(Component.text(" "));
            }
            if (ch != null) {
                known++;
                parts.add(Component.text(String.valueOf(ch)).font(ParchmentTooltip.FONT).color(NamedTextColor.WHITE));
            } else {
                parts.add(Component.text(n, InkPalette.INK));
            }
        }
        if (known == 0) {
            return null;
        }
        return Component.text("Для: ", InkPalette.FADED).decoration(TextDecoration.ITALIC, false)
            .shadowColor(ShadowColor.none()).append(Component.join(net.kyori.adventure.text.JoinConfiguration.noSeparators(), parts));
    }
}
