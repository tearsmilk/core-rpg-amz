package core.amazake.systems.coremechanics.natives;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Properties;

/**
 * Общий на всю JVM загрузчик jolt-jni и его нативка.
 *
 * <p>JVM разрешает привязать нативную библиотеку (libjoltjni.so) только к ОДНОМУ загрузчику классов: если бы классы
 * Jolt грузил загрузчик .azs (новый на каждое включение системы), второе включение упало бы с «already loaded in
 * another classloader». Поэтому jar-ы jolt-jni грузит один {@link URLClassLoader} на всю жизнь JVM; он лежит в
 * {@link System#getProperties()} (единственная общая карта JVM, видимая из любого загрузчика; значение — класс JDK,
 * поэтому он не держит классы системы), а {@code System.load} вызывает класс из того же загрузчика —
 * {@code com.github.stephengold.joltjni.NativeLibraryLoader}, так нативка привязывается именно к нему.</p>
 *
 * <p>Родитель общего загрузчика — платформенный загрузчик JDK: Jolt кроме java.base ничего не нужно.</p>
 */
public final class SharedJolt {

    static final String KEY_LOADER = "core-mechanics.jolt.loader";
    static final String KEY_FINGERPRINT = "core-mechanics.jolt.fingerprint";
    static final String KEY_NATIVE = "core-mechanics.jolt.native";

    /** Нативка внутри jar-а ReleaseSp. */
    static final String NATIVE_RESOURCE = "linux/x86-64/com/github/stephengold/libjoltjni.so";

    private SharedJolt() {
    }

    /** Общий загрузчик для этого набора jar-ов (создаётся при первом вызове в JVM). */
    public static ClassLoader loader(final Path libDir, final List<Libraries.Lib> libs, final String fingerprint) {
        final Properties props = System.getProperties();
        synchronized (props) {
            final Object existing = props.get(KEY_LOADER);
            if (existing instanceof URLClassLoader loader) {
                if (fingerprint.equals(props.getProperty(KEY_FINGERPRINT))) {
                    return loader;
                }
                if (props.getProperty(KEY_NATIVE) != null) {
                    throw new IllegalStateException("jar-ы jolt-jni сменились, а старая нативка уже привязана к старому "
                        + "загрузчику — нужен рестарт сервера");
                }
            }
            final URL[] urls = new URL[libs.size()];
            for (int i = 0; i < urls.length; i++) {
                try {
                    urls[i] = libDir.resolve(libs.get(i).file()).toUri().toURL();
                } catch (final MalformedURLException e) {
                    throw new IllegalStateException(e);
                }
            }
            final URLClassLoader loader = new URLClassLoader("core-mechanics/jolt", urls, ClassLoader.getPlatformClassLoader());
            props.put(KEY_LOADER, loader);
            props.setProperty(KEY_FINGERPRINT, fingerprint);
            return loader;
        }
    }

    /** Нативка уже загружена в этой JVM. */
    public static boolean nativeLoaded() {
        return System.getProperty(KEY_NATIVE) != null;
    }

    /**
     * Загрузить libjoltjni.so (раз на JVM): распаковать из jar-а в nativeDir и вызвать
     * {@code NativeLibraryLoader.loadLibrary} общего загрузчика. Повторный вызов — ничего не делает.
     *
     * @return путь к загруженной нативке
     */
    public static String loadNative(final ClassLoader jolt, final Path nativeDir, final String fingerprint) throws IOException,
        ReflectiveOperationException {
        final Properties props = System.getProperties();
        synchronized (props) {
            final String already = props.getProperty(KEY_NATIVE);
            if (already != null) {
                return already;
            }
            Files.createDirectories(nativeDir);
            final Path so = nativeDir.resolve("libjoltjni-" + fingerprint + ".so");
            try (InputStream in = jolt.getResourceAsStream(NATIVE_RESOURCE)) {
                if (in == null) {
                    throw new IOException("в jar-ах jolt-jni нет " + NATIVE_RESOURCE);
                }
                final byte[] bytes = in.readAllBytes();
                if (!Files.isRegularFile(so) || Files.size(so) != bytes.length) {
                    final Path tmp = nativeDir.resolve(so.getFileName() + ".tmp");
                    Files.write(tmp, bytes);
                    Files.move(tmp, so, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            final String path = so.toAbsolutePath().toString();
            final Class<?> loaderClass = Class.forName("com.github.stephengold.joltjni.NativeLibraryLoader", true, jolt);
            final Object ok = loaderClass.getMethod("loadLibrary", String.class).invoke(null, path);
            if (!Boolean.TRUE.equals(ok)) {
                throw new IOException("System.load(" + path + ") не удался (подробности — строкой выше в логе: «libraryPath = …»)");
            }
            props.setProperty(KEY_NATIVE, path);
            return path;
        }
    }
}
