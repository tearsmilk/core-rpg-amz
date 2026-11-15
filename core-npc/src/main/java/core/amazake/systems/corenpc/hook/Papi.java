package core.amazake.systems.corenpc.hook;

import java.lang.reflect.Method;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

/**
 * PlaceholderAPI через рефлексию: загрузчик системы не видит классы плагинов, поэтому класс PlaceholderAPI берётся из
 * загрузчика самого плагина. Нет плагина — текст возвращается как есть.
 */
public final class Papi {

    private final Logger logger;
    private volatile Method method;
    private volatile Plugin owner;
    private volatile boolean warned;

    public Papi(final Logger logger) {
        this.logger = logger;
    }

    private Method method() {
        final Plugin p = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
        if (p == null || !p.isEnabled()) {
            this.method = null;
            this.owner = null;
            return null;
        }
        Method m = this.method;
        if (m != null && this.owner == p) {
            return m;
        }
        try {
            final Class<?> c = Class.forName("me.clip.placeholderapi.PlaceholderAPI", true, p.getClass().getClassLoader());
            m = c.getMethod("setPlaceholders", OfflinePlayer.class, String.class);
            this.method = m;
            this.owner = p;
            return m;
        } catch (final ReflectiveOperationException | LinkageError e) {
            if (!this.warned) {
                this.warned = true;
                this.logger.warning("PlaceholderAPI найден, но вызвать его не вышло: " + e);
            }
            return null;
        }
    }

    public boolean available() {
        return this.method() != null;
    }

    /** Подставить %плейсхолдеры%. Вызывать в потоке игрока. */
    public String apply(final OfflinePlayer player, final String text) {
        if (text == null || text.indexOf('%') < 0) {
            return text;
        }
        final Method m = this.method();
        if (m == null) {
            return text;
        }
        try {
            final Object r = m.invoke(null, player, text);
            return r instanceof String s ? s : text;
        } catch (final ReflectiveOperationException | RuntimeException e) {
            return text;
        }
    }
}
