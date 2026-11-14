package core.amazake.systems.rezusmenu.util;

import java.lang.reflect.Method;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * PlaceholderAPI без жёсткой зависимости: загрузчик системы не видит классы плагинов, поэтому метод берётся
 * рефлексией из загрузчика самого PlaceholderAPI. Нет плагина — текст возвращается как есть.
 */
public final class Papi {

    private record Cached(Plugin plugin, Method method) {
    }

    private static volatile Cached cached;

    private Papi() {
    }

    public static String apply(final Player player, final String text) {
        if (text == null || text.indexOf('%') < 0) {
            return text;
        }
        final Plugin plugin = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
        if (plugin == null || !plugin.isEnabled()) {
            return text;
        }
        try {
            Cached c = cached;
            if (c == null || c.plugin() != plugin) {
                final Class<?> api = Class.forName("me.clip.placeholderapi.PlaceholderAPI", true, plugin.getClass().getClassLoader());
                c = new Cached(plugin, api.getMethod("setPlaceholders", OfflinePlayer.class, String.class));
                cached = c;
            }
            return (String) c.method().invoke(null, player, text);
        } catch (final ReflectiveOperationException | RuntimeException e) {
            return text;
        }
    }

    /** Значение плейсхолдера; null — плейсхолдер не подставился (нет PAPI, нет расширения, пусто). */
    public static String value(final Player player, final String placeholder) {
        if (placeholder == null || placeholder.isBlank()) {
            return null;
        }
        final String out = apply(player, placeholder);
        return out == null || out.isBlank() || out.equals(placeholder) || out.contains("%") ? null : out;
    }

    /** Забыть ссылку на PlaceholderAPI (выключение системы). */
    public static void reset() {
        cached = null;
    }
}
