package core.amazake.systems.corenpc.hook;

import java.lang.reflect.Method;
import java.util.function.UnaryOperator;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    /** Плейсхолдер PlaceholderAPI: %идентификатор_параметры% (как его собственный шаблон). */
    private static final Pattern TOKEN = Pattern.compile("%[A-Za-z0-9]+_[^%]+%");

    /**
     * Подставить %плейсхолдеры% по одному, пропуская каждый результат через value (экранирование MiniMessage, проверка
     * для консоли): значение плейсхолдера может зависеть от игрока и не должно становиться разметкой или аргументами.
     * Нераскрытый плейсхолдер (PAPI вернул его как есть) остаётся без изменений. Поток игрока.
     */
    public String apply(final OfflinePlayer player, final String text, final UnaryOperator<String> value) {
        if (text == null || text.indexOf('%') < 0 || this.method() == null) {
            return text;
        }
        final Matcher m = TOKEN.matcher(text);
        final StringBuilder sb = new StringBuilder(text.length() + 16);
        int last = 0;
        while (m.find()) {
            final String token = m.group();
            final String r = this.apply(player, token);
            sb.append(text, last, m.start()).append(r == null || r.equals(token) ? token : value.apply(r));
            last = m.end();
        }
        return sb.append(text, last, text.length()).toString();
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
