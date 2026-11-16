package core.amazake.systems.corepython.host;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

/**
 * Python-системы: поиск в scripts/, загрузка (свой контекст на каждую, общий движок), выгрузка, перезагрузка.
 * Без Bukkit — всё серверное через {@link Platform}, поэтому проверяется юнит-тестами.
 *
 * <p>Потоки: загрузка/выгрузка — из любого не-тикового потока (их сериализует замок); сам Python потом вызывается из
 * тех потоков, где случилось событие / задача (регионы, глобальный, асинхронные) — GraalPy пускает в контекст несколько
 * потоков по очереди (GIL на каждый контекст).</p>
 */
public final class ScriptManager {

    /** Настройки хоста. */
    public record Options(boolean warnInterpreterOnly) {
    }

    private final Platform platform;
    private final Path scriptsDir;
    private final Path dataRoot;
    private final Path runtimeDir;
    private final Options options;
    private final ClassLoader hostLoader;
    private final Map<String, PyScript> scripts = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Listener quitListener = new Listener() {
    };
    private volatile Engine engine;
    private volatile PyScript loading;
    private volatile boolean closing;

    public ScriptManager(final Platform platform, final Path root, final Options options, final ClassLoader hostLoader) {
        this.platform = platform;
        this.scriptsDir = root.resolve("scripts").toAbsolutePath().normalize();
        this.dataRoot = root.resolve("data").toAbsolutePath().normalize();
        this.runtimeDir = root.resolve("runtime").toAbsolutePath().normalize();
        this.options = options;
        this.hostLoader = hostLoader;
    }

    public Path scriptsDir() {
        return this.scriptsDir;
    }

    // ------------------------------------------------------------------------------------------
    // Движок
    // ------------------------------------------------------------------------------------------

    /** Движок (создаётся при первом вызове; один на все Python-системы — общий кэш разобранного кода). */
    public Engine engine() throws IOException {
        Engine e = this.engine;
        if (e != null) {
            return e;
        }
        this.lock.lock();
        try {
            if (this.engine == null) {
                this.writeRuntime();
                final OutputStream log = new LogStream(this.platform.logger(), "[python] ");
                this.engine = this.withLoader(() -> Engine.newBuilder("python")
                    .option("engine.WarnInterpreterOnly", String.valueOf(this.options.warnInterpreterOnly()))
                    .out(log)
                    .err(log)
                    .build());
                this.platform.registerEvent(PlayerQuitEvent.class, this.quitListener, EventPriority.MONITOR, (l, ev) -> {
                    if (ev instanceof PlayerQuitEvent q) {
                        for (final PyScript s : this.scripts.values()) {
                            s.tracker.forget(q.getPlayer().getUniqueId());
                        }
                    }
                }, false);
            }
            return this.engine;
        } finally {
            this.lock.unlock();
        }
    }

    /** «Interpreted» — без JIT (обычный JDK), иначе имя оптимизирующего рантайма. */
    public String runtimeName() {
        final Engine e = this.engine;
        return e == null ? "—" : e.getImplementationName();
    }

    public String pythonVersion() {
        final Engine e = this.engine;
        return e == null ? "—" : e.getLanguages().get("python").getVersion();
    }

    /** Модули Python из ресурсов хоста, которые пишутся в runtime/: API и переводчик японского режима. */
    static final List<String> RUNTIME_MODULES = List.of("amazake.py", "amazake_ja.py");

    /** runtime/amazake.py, runtime/amazake_ja.py — из ресурсов хоста; перезаписываются при каждом запуске. */
    private void writeRuntime() throws IOException {
        Files.createDirectories(this.runtimeDir);
        for (final String module : RUNTIME_MODULES) {
            try (InputStream in = ScriptManager.class.getClassLoader().getResourceAsStream("python/" + module)) {
                if (in == null) {
                    throw new IOException("в хосте нет python/" + module);
                }
                final byte[] bytes = in.readAllBytes();
                final Path f = this.runtimeDir.resolve(module);
                if (!Files.exists(f) || !java.util.Arrays.equals(Files.readAllBytes(f), bytes)) {
                    Files.write(f, bytes);
                }
            }
        }
    }

    /**
     * Контекстный загрузчик потока на время создания движка/контекста — загрузчик библиотек GraalPy (родитель
     * хоста), а не сам хост: потоки, которые кто-то создаст в этот момент (пулы сервера, Truffle), наследуют его
     * навсегда, и с загрузчиком хоста они удержали бы в памяти выключенную версию core-python.
     */
    private <T> T withLoader(final java.util.function.Supplier<T> body) {
        final Thread t = Thread.currentThread();
        final ClassLoader old = t.getContextClassLoader();
        final ClassLoader graal = this.hostLoader.getParent() != null ? this.hostLoader.getParent() : this.hostLoader;
        t.setContextClassLoader(graal);
        try {
            return body.get();
        } finally {
            t.setContextClassLoader(old);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Поиск
    // ------------------------------------------------------------------------------------------

    /** Папки scripts/* с manifest.yml: имя → включена ли (enabled). Битые манифесты — с false. */
    public Map<String, Boolean> discover() {
        final Map<String, Boolean> out = new TreeMap<>();
        if (!Files.isDirectory(this.scriptsDir)) {
            return out;
        }
        try (Stream<Path> s = Files.list(this.scriptsDir)) {
            for (final Path dir : s.filter(p -> Files.isRegularFile(p.resolve("manifest.yml"))).toList()) {
                try {
                    out.put(dir.getFileName().toString(), Manifest.read(dir).enabled());
                } catch (final Exception e) {
                    out.put(dir.getFileName().toString(), false);
                }
            }
        } catch (final IOException e) {
            this.platform.logger().log(Level.WARNING, "core-python: не читается " + this.scriptsDir, e);
        }
        return out;
    }

    public PyScript get(final String name) {
        return this.scripts.get(name);
    }

    public List<PyScript> loaded() {
        return List.copyOf(new TreeMap<>(this.scripts).values());
    }

    // ------------------------------------------------------------------------------------------
    // Загрузка / выгрузка
    // ------------------------------------------------------------------------------------------

    /** Загружает систему. Вне тиков (секунды на первую загрузку). Ошибка — исключение с понятным текстом. */
    public PyScript load(final String name) throws Exception {
        this.lock.lock();
        try {
            if (this.closing) {
                throw new IllegalStateException("core-python выключается");
            }
            if (this.scripts.containsKey(name)) {
                throw new IllegalStateException("система " + name + " уже загружена (используйте reload)");
            }
            final Path dir = this.scriptsDir.resolve(name).normalize();
            if (!dir.startsWith(this.scriptsDir) || !Files.isDirectory(dir)) {
                throw new IllegalArgumentException("нет папки scripts/" + name);
            }
            final Manifest manifest = Manifest.read(dir);
            final long t0 = System.nanoTime();
            final Engine engine = this.engine();
            final PyScript s = new PyScript(manifest, dir, this.dataRoot.resolve(name), this.platform, new Tracker());
            this.scripts.put(name, s);
            this.loading = s;
            try {
                final Context ctx = this.withLoader(() -> Context.newBuilder("python")
                    .engine(engine)
                    .allowAllAccess(true)
                    .allowExperimentalOptions(true)
                    .hostClassLoader(this.hostLoader)
                    .currentWorkingDirectory(dir)
                    .option("python.PythonPath", this.runtimeDir + File.pathSeparator + dir)
                    .option("python.PosixModuleBackend", "java")
                    .option("python.DontWriteBytecodeFlag", "true")
                    .build());
                s.context = ctx;
                ctx.getPolyglotBindings().putMember("amazake_host", new ScriptApi(s));
                ctx.eval("python", "import amazake");
                final Path mainFile = dir.resolve(manifest.main()).normalize();
                if (manifest.japanese()) {
                    // Японский режим: все .py системы — через переводчик (импорт-хук), главный файл — так же;
                    // code object получает путь исходного файла, строки и столбцы совпадают с ним.
                    ctx.eval("python", "__import__('amazake_ja')").getMember("run_main")
                        .execute(dir.toString(), mainFile.toString());
                } else {
                    ctx.eval(Source.newBuilder("python", mainFile.toFile()).build());
                }
                if (this.closing) {
                    throw new IllegalStateException("core-python выключается");
                }
                s.activate();
                for (final Value hook : List.copyOf(s.loadHooks)) {
                    s.call("on_load", hook);
                }
                s.loadMillis = (System.nanoTime() - t0) / 1_000_000;
                return s;
            } catch (final Throwable e) {
                // Текст ошибки — до закрытия контекста: трасса и поля SyntaxError читаются из объектов Python.
                final String text = e instanceof PolyglotException pe && !pe.isCancelled() ? ErrorLog.format(pe, dir) : null;
                this.scripts.remove(name, s);
                s.shutdown();
                if (e instanceof PolyglotException pe) {
                    if (pe.isCancelled()) {
                        throw new IllegalStateException("загрузка " + name + " прервана");
                    }
                    throw new IllegalStateException(text, pe);
                }
                throw e;
            } finally {
                this.loading = null;
            }
        } finally {
            this.lock.unlock();
        }
    }

    /** Выгружает систему (из любого потока). false — не была загружена. */
    public boolean unload(final String name) {
        this.lock.lock();
        try {
            final PyScript s = this.scripts.remove(name);
            if (s == null) {
                return false;
            }
            s.shutdown();
            return true;
        } finally {
            this.lock.unlock();
        }
    }

    public PyScript reload(final String name) throws Exception {
        this.lock.lock();
        try {
            this.unload(name);
            return this.load(name);
        } finally {
            this.lock.unlock();
        }
    }

    /** Загружает все включённые (enabled: true). Ошибки — в лог. Возвращает число загруженных. */
    public int loadEnabled() {
        int n = 0;
        for (final Map.Entry<String, Boolean> e : this.discover().entrySet()) {
            if (!e.getValue() || this.scripts.containsKey(e.getKey())) {
                continue;
            }
            try {
                final PyScript s = this.load(e.getKey());
                this.platform.logger().info("core-python: Python-система " + e.getKey() + " " + s.manifest.version()
                    + " загружена за " + s.loadMillis + " мс");
                n++;
            } catch (final Exception ex) {
                this.platform.logger().severe("core-python: не удалось загрузить " + e.getKey() + ": " + ex.getMessage());
            }
        }
        return n;
    }

    /** Выгружает всё и закрывает движок (выключение core-python). */
    public void close() {
        this.closing = true;
        final PyScript l = this.loading;
        if (l != null && l.context != null) {
            // Прервать идущую загрузку, чтобы не ждать её.
            try {
                l.context.close(true);
            } catch (final RuntimeException ignored) {
                // закроется в shutdown()
            }
        }
        this.lock.lock();
        try {
            for (final String name : List.copyOf(this.scripts.keySet())) {
                this.unload(name);
            }
            this.platform.unregisterListener(this.quitListener);
            final Engine e = this.engine;
            this.engine = null;
            if (e != null) {
                try {
                    e.close(true);
                } catch (final RuntimeException ex) {
                    this.platform.logger().log(Level.WARNING, "core-python: движок закрыт с ошибкой", ex);
                }
            }
        } finally {
            this.lock.unlock();
        }
    }

    /** Список для /python list. */
    public List<String> describe() {
        final List<String> out = new ArrayList<>();
        final Map<String, Boolean> found = this.discover();
        for (final String name : new java.util.TreeSet<>(java.util.stream.Stream.concat(found.keySet().stream(), this.scripts.keySet().stream()).toList())) {
            final PyScript s = this.scripts.get(name);
            if (s == null) {
                out.add(name + " — не загружена" + (Boolean.TRUE.equals(found.get(name)) ? "" : " (enabled: false)"));
            } else {
                out.add(name + " " + s.manifest.version() + " — " + switch (s.state) {
                    case ACTIVE -> "работает";
                    case LOADING -> "загружается";
                    case CLOSING, CLOSED -> "выгружается";
                } + ": слушателей " + s.listeners.get() + ", команд " + s.commands.size() + ", задач " + s.tasks.size()
                    + ", вызовов " + s.calls.get() + ", игроков с изменениями " + s.tracker.players() + ", загрузка " + s.loadMillis + " мс");
            }
        }
        return out;
    }

    /** Поток → лог построчно. */
    static final class LogStream extends OutputStream {
        private final Logger logger;
        private final String prefix;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();

        LogStream(final Logger logger, final String prefix) {
            this.logger = logger;
            this.prefix = prefix;
        }

        @Override
        public synchronized void write(final int b) {
            if (b == '\n') {
                this.flushLine();
            } else {
                this.line.write(b);
            }
        }

        @Override
        public synchronized void flush() {
            if (this.line.size() > 0) {
                this.flushLine();
            }
        }

        private void flushLine() {
            final String s = this.line.toString(StandardCharsets.UTF_8).stripTrailing();
            this.line.reset();
            if (!s.isEmpty()) {
                this.logger.info(this.prefix + s);
            }
        }
    }
}
