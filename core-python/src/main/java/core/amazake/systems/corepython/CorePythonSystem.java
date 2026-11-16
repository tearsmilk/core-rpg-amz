package core.amazake.systems.corepython;

import core.amazake.systems.api.AmazakeSystem;
import core.amazake.systems.api.SystemContext;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * core-python — хост GraalPy: системы на Python в systems/core-python/scripts/&lt;имя&gt;/.
 *
 * <p>Этот класс — только загрузчик. На тике (enable) он регистрирует /python и уходит в асинхронный поток: проверяет
 * (и при необходимости скачивает) jar-ы GraalPy в lib/, берёт общий загрузчик GraalPy ({@link SharedGraal}), грузит в
 * {@link HostLoader} класс хоста и включает его. Хост создаёт движок и загружает Python-системы.</p>
 */
public final class CorePythonSystem implements AmazakeSystem {

    static final String HOST_CLASS = "core.amazake.systems.corepython.host.Host";

    private final Object lock = new Object();
    private volatile SystemContext context;
    /** Хост (класс из HostLoader); null, пока GraalPy загружается или если загрузка не удалась. */
    private volatile AmazakeSystem host;
    private volatile String status = "загружается";
    private volatile boolean disabled;

    @Override
    public void enable(final SystemContext context) {
        final long started = System.nanoTime();
        this.context = context;
        this.disabled = false;
        context.saveDefaultResource("config.yml");
        final YamlConfiguration config = context.loadConfig();
        final Path data = context.dataFolder();
        this.extractDefaultScripts(data.resolve("scripts"));
        // Кэш ресурсов GraalPy (стандартная библиотека Python распаковывается сюда один раз). Свойство читается при
        // первой инициализации Truffle — то есть один раз за жизнь JVM.
        if (System.getProperty("polyglot.engine.userResourceCache") == null) {
            System.setProperty("polyglot.engine.userResourceCache", data.resolve("cache").toAbsolutePath().toString());
        }
        context.registerCommand(new PythonCommand());
        Bukkit.getAsyncScheduler().runNow(context.plugin(), task -> this.start(context, config, started));
        context.logger().info("core-python: включён за " + (System.nanoTime() - started) / 1_000_000
            + " мс; GraalPy загружается в фоне");
    }

    private void start(final SystemContext context, final YamlConfiguration config, final long started) {
        try {
            final Path libDir = context.dataFolder().resolve("lib");
            final List<Libraries.Lib> libs;
            try (InputStream in = CorePythonSystem.class.getClassLoader().getResourceAsStream("libs.txt")) {
                if (in == null) {
                    throw new IllegalStateException("в .azs нет libs.txt");
                }
                libs = Libraries.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            final List<Libraries.Lib> missing = Libraries.missing(libDir, libs);
            if (!missing.isEmpty()) {
                if (!config.getBoolean("libraries.auto-download", true)) {
                    throw new IllegalStateException("нет " + missing.size() + " jar-ов GraalPy в " + libDir
                        + " (автоскачивание выключено: libraries.auto-download) — положите их туда (см. README)");
                }
                this.status = "скачивает GraalPy";
                Libraries.download(libDir, missing, config.getString("libraries.repository", "https://repo1.maven.org/maven2/"),
                    m -> context.logger().info("core-python: " + m));
            }
            if (this.disabled) {
                return;
            }
            this.status = "запускает GraalPy";
            final ClassLoader graal = SharedGraal.get(libDir, libs, Libraries.fingerprint(libs), AmazakeSystem.class.getClassLoader(),
                context.logger());
            final URL azs = CorePythonSystem.class.getProtectionDomain().getCodeSource().getLocation();
            final HostLoader hostLoader = new HostLoader(graal, CorePythonSystem.class.getClassLoader(), azs);
            final Class<?> type = Class.forName(HOST_CLASS, true, hostLoader);
            final AmazakeSystem h = (AmazakeSystem) type.getConstructor(SystemContext.class, YamlConfiguration.class, long.class)
                .newInstance(context, config, started);
            synchronized (this.lock) {
                if (this.disabled) {
                    return;
                }
                this.host = h;
            }
            this.status = "работает";
            // Движок + автозагрузка Python-систем (долго — здесь, в асинхронном потоке).
            h.enable(context);
        } catch (final Throwable e) {
            if (e instanceof VirtualMachineError error && !(e instanceof StackOverflowError)) {
                throw error;
            }
            this.status = "ошибка: " + e;
            context.logger().log(Level.SEVERE, "core-python: не удалось запустить GraalPy", e);
        }
    }

    @Override
    public void disable(final SystemContext context) throws Exception {
        final AmazakeSystem h;
        synchronized (this.lock) {
            this.disabled = true;
            h = this.host;
            this.host = null;
        }
        if (h != null) {
            h.disable(context);
        }
    }

    @Override
    public void reload(final SystemContext context) throws Exception {
        final AmazakeSystem h = this.host;
        if (h != null) {
            h.reload(context);
        }
    }

    /** Python-системы из .azs (default-scripts/&lt;имя&gt;/…) — в scripts/, только если такой папки ещё нет. */
    private void extractDefaultScripts(final Path scripts) {
        try {
            final URL azs = CorePythonSystem.class.getProtectionDomain().getCodeSource().getLocation();
            final Path file = Path.of(azs.toURI());
            final List<String> created = new ArrayList<>();
            try (JarFile jar = new JarFile(file.toFile())) {
                final Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    final JarEntry e = entries.nextElement();
                    if (e.isDirectory() || !e.getName().startsWith("default-scripts/")) {
                        continue;
                    }
                    final String rel = e.getName().substring("default-scripts/".length());
                    final int slash = rel.indexOf('/');
                    if (slash <= 0) {
                        continue;
                    }
                    final String name = rel.substring(0, slash);
                    if (Files.exists(scripts.resolve(name)) && !created.contains(name)) {
                        continue;
                    }
                    final Path target = scripts.resolve(rel).normalize();
                    if (!target.startsWith(scripts)) {
                        continue;
                    }
                    if (!created.contains(name)) {
                        created.add(name);
                    }
                    Files.createDirectories(target.getParent());
                    try (InputStream in = jar.getInputStream(e)) {
                        Files.copy(in, target);
                    }
                }
            }
            if (!created.isEmpty()) {
                this.context.logger().info("core-python: распакованы Python-системы по умолчанию: " + String.join(", ", created));
            }
        } catch (final IOException | java.net.URISyntaxException | RuntimeException e) {
            this.context.logger().log(Level.WARNING, "core-python: не удалось распаковать Python-системы по умолчанию", e);
        }
    }

    /** /python — сама логика в хосте; пока он не готов, команда только сообщает статус. */
    private final class PythonCommand extends Command {

        PythonCommand() {
            super("python", "Python-системы (core-python)", "/python list|load|unload|reload|info <имя>", List.of("py", "corepython"));
            this.setPermission("corepython.admin");
        }

        @Override
        public boolean execute(final CommandSender sender, final String label, final String[] args) {
            if (!this.testPermission(sender)) {
                return true;
            }
            if (CorePythonSystem.this.host instanceof TabExecutor exec) {
                return exec.onCommand(sender, this, label, args);
            }
            sender.sendMessage("core-python: GraalPy " + CorePythonSystem.this.status.toLowerCase(Locale.ROOT) + " — подождите");
            return true;
        }

        @Override
        public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args) {
            if (CorePythonSystem.this.host instanceof TabExecutor exec && this.testPermissionSilent(sender)) {
                final List<String> out = exec.onTabComplete(sender, this, alias, args);
                return out == null ? List.of() : out;
            }
            return List.of();
        }
    }
}
