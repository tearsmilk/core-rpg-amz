package core.amazake.systems.coremechanics.natives;

import core.amazake.systems.coremechanics.physics.PhysicsHost;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Запуск физики (вне тиков — асинхронный поток): lib/ → общий загрузчик Jolt → нативка → хост в новом HostLoader →
 * самотест. Ошибка любого шага — исключение с понятной причиной; система тогда работает без физики (статуи — да,
 * рэгдоллы — нет).
 */
public final class JoltBootstrap {

    static final String HOST_CLASS = "core.amazake.systems.coremechanics.host.JoltHost";

    private JoltBootstrap() {
    }

    /** Что получилось: хост и строка для лога/команды. */
    public record Started(PhysicsHost host, String info) {
    }

    public static Started start(final Path dataFolder, final boolean download, final List<String> repositories, final int threads,
                                final Consumer<String> log) throws Exception {
        final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        final String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (!os.contains("linux") || !(arch.contains("amd64") || arch.contains("x86_64"))) {
            throw new IllegalStateException("нативка Jolt есть только для Linux x86-64 (здесь " + os + " " + arch + ")");
        }
        final ClassLoader azs = JoltBootstrap.class.getClassLoader();
        final List<Libraries.Lib> libs;
        try (InputStream in = azs.getResourceAsStream("libs.txt")) {
            if (in == null) {
                throw new IllegalStateException("в .azs нет libs.txt");
            }
            libs = Libraries.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        final Path libDir = dataFolder.resolve("lib");
        final String fingerprint = Libraries.fingerprint(libs);
        // Пока нативка не загружена, jar-ы можно (пере)писать; после — они уже открыты общим загрузчиком.
        if (!SharedJolt.nativeLoaded()) {
            Libraries.ensure(libDir, libs, file -> bundled(azs, file), download, repositories, log);
        }
        final ClassLoader jolt = SharedJolt.loader(libDir, libs, fingerprint);
        final boolean first = !SharedJolt.nativeLoaded();
        final String so = SharedJolt.loadNative(jolt, libDir.resolve("natives"), fingerprint);
        final URL azsLocation = JoltBootstrap.class.getProtectionDomain().getCodeSource().getLocation();
        final HostLoader hostLoader = new HostLoader(jolt, azs, azsLocation);
        final PhysicsHost host = (PhysicsHost) Class.forName(HOST_CLASS, true, hostLoader).getConstructor().newInstance();
        host.start(threads);
        return new Started(host, "Jolt " + host.version() + ", потоков " + host.threads() + ", нативка "
            + (first ? "загружена " : "уже была в JVM ") + Path.of(so).getFileName() + "; самотест: " + host.selfTest());
    }

    /** jar из bundled-lib/ внутри .azs (без кэша URLConnection — файл .azs не остаётся открытым). */
    private static InputStream bundled(final ClassLoader azs, final String file) throws IOException {
        final URL url = azs.getResource("bundled-lib/" + file);
        if (url == null) {
            return null;
        }
        final java.net.URLConnection c = url.openConnection();
        c.setUseCaches(false);
        return c.getInputStream();
    }
}
