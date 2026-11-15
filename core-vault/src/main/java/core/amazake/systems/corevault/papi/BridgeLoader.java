package core.amazake.systems.corevault.papi;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;

/**
 * Загрузчик моста PlaceholderAPI. Загрузчик системы видит классы сервера, но не плагинов, поэтому наследник
 * {@code PlaceholderExpansion} определяется здесь: родитель — загрузчик плагина PlaceholderAPI (через него видны и
 * PlaceholderAPI, и Bukkit), байты класса — ресурс {@code papi-bridge/<путь>.class.bin} из .azs.
 *
 * <p>Ресурс читается без кеша URL: иначе JVM держит открытым jar прошлой версии .azs после перезагрузки системы.
 * Загрузчик живёт, пока зарегистрировано расширение; при выключении на него не остаётся ссылок.</p>
 */
final class BridgeLoader extends ClassLoader {

    /** Имя класса моста. */
    static final String BRIDGE_CLASS = "core.amazake.systems.corevault.papibridge.CoreVaultExpansion";

    static {
        ClassLoader.registerAsParallelCapable();
    }

    private final ClassLoader resources;

    /**
     * @param parent    загрузчик плагина PlaceholderAPI
     * @param resources откуда читать байты моста (загрузчик системы)
     */
    BridgeLoader(final ClassLoader parent, final ClassLoader resources) {
        super("core-vault-papi-bridge", parent);
        this.resources = resources;
    }

    /** Путь ресурса с байтами класса: {@code papi-bridge/a/b/C.class.bin}. */
    static String resourcePath(final String className) {
        return "papi-bridge/" + className.replace('.', '/') + ".class.bin";
    }

    @Override
    protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
        // Классы моста определяем сами, не спрашивая родителя: у PlaceholderAPI их нет, а поиск по чужим плагинам
        // лишний.
        if (name.startsWith("core.amazake.systems.corevault.papibridge.")) {
            synchronized (this.getClassLoadingLock(name)) {
                Class<?> loaded = this.findLoadedClass(name);
                if (loaded == null) {
                    loaded = this.findClass(name);
                }
                if (resolve) {
                    this.resolveClass(loaded);
                }
                return loaded;
            }
        }
        return super.loadClass(name, resolve);
    }

    @Override
    protected Class<?> findClass(final String name) throws ClassNotFoundException {
        final String path = resourcePath(name);
        final URL url = this.resources.getResource(path);
        if (url == null) {
            throw new ClassNotFoundException(name + " (нет ресурса " + path + " в .azs)");
        }
        final byte[] bytes;
        try {
            final URLConnection connection = url.openConnection();
            connection.setUseCaches(false);
            try (InputStream in = connection.getInputStream()) {
                bytes = in.readAllBytes();
            }
        } catch (final IOException e) {
            throw new ClassNotFoundException(name + " (не удалось прочитать " + path + ": " + e.getMessage() + ")", e);
        }
        return this.defineClass(name, bytes, 0, bytes.length);
    }
}
