package ink.hoka.odyssey.spacesky;

import ink.hoka.odyssey.api.ModuleConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Тип мира карт: ключ {@code dimension} в maps/&lt;карта&gt;/map.yml (его читает ядро при загрузке карты). Только файлы —
 * вызывать из async. Остальные ключи map.yml (генерация, точка появления) сохраняются.
 */
final class MapDimensions {
    static final String KEY = "dimension";
    static final String DEFAULT = "minecraft:overworld";
    /** Имя карты — как в ядре (Maps.NAME). */
    static final Pattern NAME = Pattern.compile("[a-z0-9_\\-]{1,32}");

    private MapDimensions() {
    }

    static Path file(Path mapsDir, String map) {
        return mapsDir.resolve(map).resolve("map.yml");
    }

    /** Тип мира каждой карты (нет ключа — minecraft:overworld); карта без папки пропускается. */
    static Map<String, String> read(Path mapsDir, Collection<String> maps) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String m : maps) {
            if (!NAME.matcher(m).matches() || !Files.isDirectory(mapsDir.resolve(m))) {
                continue;
            }
            String d;
            try {
                d = new ModuleConfig(file(mapsDir, m)).getString(KEY, DEFAULT);
            } catch (IllegalStateException e) {
                d = "? (map.yml не читается)";
            }
            out.put(m, d);
        }
        return out;
    }

    /**
     * Записать (on) или убрать (off) {@code dimension: "hoka:space"}; off убирает ключ, только если там hoka:space
     * (чужой тип мира не трогает). Возвращает прежнее значение ключа (null — не было). Файл пишется, только если
     * что-то меняется. Исключение — нет такой карты или файл не прочитать/записать.
     */
    static String write(Path mapsDir, String map, boolean on) {
        if (!NAME.matcher(map).matches() || !Files.isDirectory(mapsDir.resolve(map))) {
            throw new IllegalArgumentException("нет карты «" + map + "»");
        }
        ModuleConfig c = new ModuleConfig(file(mapsDir, map));
        Object o = c.get(KEY);
        String prev = o == null ? null : String.valueOf(o);
        boolean space = SpaceDimension.KEY.equals(prev);
        if (on && !space) {
            c.set(KEY, SpaceDimension.KEY);
            c.save();
        } else if (!on && space) {
            c.set(KEY, null);
            c.save();
        }
        return prev;
    }
}
