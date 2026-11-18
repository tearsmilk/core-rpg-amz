package core.amazake.systems.coremechanics.natives;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.security.ProtectionDomain;

/**
 * Загрузчик кода хоста физики: классы host/**.class.bin из .azs. Классы Jolt ({@code com.github.stephengold.*}) берёт
 * из общего загрузчика ({@link SharedJolt}), всё остальное (Bukkit, joml, интерфейсы physics.* из main) — из загрузчика
 * .azs (родитель). Новый на каждое включение системы: после выключения на него никто не ссылается (общий загрузчик
 * Jolt про хост не знает).
 */
final class HostLoader extends ClassLoader {

    static {
        ClassLoader.registerAsParallelCapable();
    }

    private static final String JOLT_PREFIX = "com.github.stephengold.";

    private final ClassLoader jolt;
    private final ClassLoader azs;
    private final ProtectionDomain domain;

    HostLoader(final ClassLoader jolt, final ClassLoader azs, final URL azsLocation) {
        super("core-mechanics/host", azs);
        this.jolt = jolt;
        this.azs = azs;
        // Домен без ссылки на загрузчик: снимок стека в новых потоках не удержит классы.
        this.domain = new ProtectionDomain(new CodeSource(azsLocation, (CodeSigner[]) null), null);
    }

    @Override
    protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
        if (name.startsWith(JOLT_PREFIX)) {
            return this.jolt.loadClass(name);
        }
        return super.loadClass(name, resolve);
    }

    @Override
    protected Class<?> findClass(final String name) throws ClassNotFoundException {
        final String path = "host/" + name.replace('.', '/') + ".class.bin";
        final byte[] bytes;
        try (InputStream in = this.ownStream(path)) {
            if (in == null) {
                throw new ClassNotFoundException(name);
            }
            bytes = in.readAllBytes();
        } catch (final IOException e) {
            throw new ClassNotFoundException(name, e);
        }
        final int dot = name.lastIndexOf('.');
        if (dot > 0) {
            final String pkg = name.substring(0, dot);
            if (this.getDefinedPackage(pkg) == null) {
                try {
                    this.definePackage(pkg, null, null, null, null, null, null, null);
                } catch (final IllegalArgumentException alreadyDefined) {
                    // другой поток успел раньше
                }
            }
        }
        return this.defineClass(name, bytes, 0, bytes.length, this.domain);
    }

    /** Поток ресурса .azs без кэша URLConnection: после закрытия файл не остаётся открытым. */
    private InputStream ownStream(final String path) throws IOException {
        final URL url = this.azs.getResource(path);
        if (url == null) {
            return null;
        }
        final java.net.URLConnection c = url.openConnection();
        c.setUseCaches(false);
        return c.getInputStream();
    }
}
