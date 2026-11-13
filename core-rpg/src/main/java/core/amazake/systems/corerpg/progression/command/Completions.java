package core.amazake.systems.corerpg.progression.command;

import core.amazake.systems.corerpg.progression.Names;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Автодополнение: фильтр по началу ввода без учёта регистра и «ё». */
final class Completions {

    private Completions() {
    }

    static List<String> filter(final Collection<String> options, final String input) {
        final String prefix = Names.normalize(input);
        final String raw = input == null ? "" : input.toLowerCase(Locale.ROOT);
        final LinkedHashSet<String> out = new LinkedHashSet<>();
        for (final String option : options) {
            if (option == null || option.isEmpty()) {
                continue;
            }
            if (option.toLowerCase(Locale.ROOT).startsWith(raw) || Names.normalize(option).startsWith(prefix)) {
                out.add(option);
            }
        }
        return new ArrayList<>(out);
    }

    /** Ники игроков онлайн. */
    static List<String> players(final String input) {
        final List<String> names = new ArrayList<>();
        for (final Player p : Bukkit.getOnlinePlayers()) {
            names.add(p.getName());
        }
        return filter(names, input);
    }

    /** В автодополнении пробелы недопустимы: «Тёмный рыцарь» → «Тёмный_рыцарь» (поиск понимает «_»). */
    static String token(final String name) {
        return Names.stripTags(name).trim().replace(' ', '_');
    }
}
