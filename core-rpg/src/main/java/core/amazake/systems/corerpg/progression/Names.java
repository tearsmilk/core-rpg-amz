package core.amazake.systems.corerpg.progression;

import java.util.Locale;

/** Нормализация названий для поиска по имени: регистр, «ё», подчёркивания и лишние пробелы не важны. */
public final class Names {

    private Names() {
    }

    /** «Тёмный_Рыцарь » → «темный рыцарь». null → "". */
    public static String normalize(final String raw) {
        if (raw == null) {
            return "";
        }
        final String lower = raw.toLowerCase(Locale.ROOT).replace('ё', 'е').replace('_', ' ').replace('-', ' ');
        return lower.trim().replaceAll("\\s+", " ");
    }

    /** Убирает теги MiniMessage: «<red>Мечник</red>» → «Мечник» (для сравнения с вводом игрока). */
    public static String stripTags(final String raw) {
        return raw == null ? "" : raw.replaceAll("<[^<>]*>", "");
    }
}
