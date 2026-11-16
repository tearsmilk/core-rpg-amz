package core.amazake.systems.corepython;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Общий на всю JVM загрузчик библиотек GraalPy.
 *
 * <p>Почему общий, а не новый на каждое включение системы: Truffle/GraalPy после закрытия движка не отпускает свой
 * загрузчик (замер: каждый новый загрузчик + движок оставались в памяти, ~18 МБ за раз), а нативную библиотеку
 * TruffleAttach JVM разрешает привязать только к одному загрузчику. Поэтому классы GraalPy грузятся один раз за жизнь
 * сервера, а перезагрузка core-python пересоздаёт только движок, контексты и код хоста (HostLoader).</p>
 *
 * <p>Где хранится: в {@link System#getProperties()} под ключом {@link #KEY} (это единственная общая карта JVM, видимая
 * из любого загрузчика; значение — сам URLClassLoader, класс JDK, поэтому он не держит классы системы). Смена набора
 * jar-ов (новая версия GraalPy) — новый загрузчик; старый остаётся до рестарта сервера (пишем предупреждение).</p>
 */
final class SharedGraal {

    static final String KEY = "core-python.graalpy.loader";
    static final String KEY_FINGERPRINT = "core-python.graalpy.fingerprint";

    private SharedGraal() {
    }

    static ClassLoader get(final Path libDir, final List<Libraries.Lib> libs, final String fingerprint, final ClassLoader serverLoader,
                           final Logger log) {
        final Properties props = System.getProperties();
        synchronized (props) {
            final Object existing = props.get(KEY);
            if (existing instanceof URLClassLoader loader && fingerprint.equals(props.getProperty(KEY_FINGERPRINT))) {
                return loader;
            }
            if (existing != null) {
                log.warning("core-python: библиотеки GraalPy сменились — новые загружены, старые останутся в памяти до рестарта сервера");
            }
            final URL[] urls = new URL[libs.size()];
            for (int i = 0; i < urls.length; i++) {
                try {
                    urls[i] = libDir.resolve(libs.get(i).file()).toUri().toURL();
                } catch (final MalformedURLException e) {
                    throw new IllegalStateException(e);
                }
            }
            final URLClassLoader loader = new URLClassLoader("core-python/graalpy", urls, serverLoader);
            props.put(KEY, loader);
            props.setProperty(KEY_FINGERPRINT, fingerprint);
            return loader;
        }
    }
}
