package core.amazake.systems.corepython;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.security.ProtectionDomain;

/**
 * Загрузчик кода хоста: классы host/**.class.bin и ресурсы host/** из .azs. Родитель — общий загрузчик библиотек
 * GraalPy (а у него — загрузчик сервера), поэтому хост видит и polyglot API, и Bukkit/Paper, и API систем.
 * Живёт, пока включена система: после выключения на него никто не ссылается (общий загрузчик GraalPy на хост не
 * ссылается — он его родитель, а не наоборот).
 */
final class HostLoader extends ClassLoader {

    static {
        ClassLoader.registerAsParallelCapable();
    }

    private final ClassLoader azs;
    private final ProtectionDomain domain;

    HostLoader(final ClassLoader graal, final ClassLoader azs, final URL azsLocation) {
        super("core-python/host", graal);
        this.azs = azs;
        // Домен без ссылки на загрузчик (как у SystemClassLoader): снимок стека в новых потоках не удержит классы.
        this.domain = new ProtectionDomain(new CodeSource(azsLocation, (CodeSigner[]) null), null);
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

    @Override
    public InputStream getResourceAsStream(final String name) {
        try {
            final InputStream own = this.ownStream("host/" + name);
            return own != null ? own : super.getResourceAsStream(name);
        } catch (final IOException e) {
            return null;
        }
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
