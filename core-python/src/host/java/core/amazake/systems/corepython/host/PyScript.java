package core.amazake.systems.corepython.host;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import org.bukkit.command.Command;
import org.bukkit.event.Listener;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;

/**
 * Одна загруженная Python-система: свой polyglot-контекст (свои модули, свои глобальные переменные, свой GIL) и всё,
 * что она зарегистрировала через API. Выгрузка снимает регистрации, отменяет задачи, откатывает атрибуты/эффекты и
 * закрывает контекст.
 */
public final class PyScript {

    public enum State { LOADING, ACTIVE, CLOSING, CLOSED }

    final String name;
    final Path dir;
    final Path dataDir;
    final Manifest manifest;
    final Platform platform;
    final Tracker tracker;
    final ErrorLog errors;
    /** Владелец всех слушателей скрипта: HandlerList.unregisterAll(owner) снимает их разом. */
    final Listener owner = new Listener() {
    };

    volatile Context context;
    volatile State state = State.LOADING;
    volatile long loadMillis;

    /** Слушатели, объявленные во время загрузки: регистрируются, когда модуль выполнен целиком. */
    private final List<Runnable> pending = new ArrayList<>();
    final List<Command> commands = new CopyOnWriteArrayList<>();
    final Set<Tasks.Owned> tasks = ConcurrentHashMap.newKeySet();
    final List<Value> loadHooks = new CopyOnWriteArrayList<>();
    final List<Value> unloadHooks = new CopyOnWriteArrayList<>();
    final AtomicLong calls = new AtomicLong();
    final java.util.concurrent.atomic.AtomicInteger listeners = new java.util.concurrent.atomic.AtomicInteger();

    PyScript(final Manifest manifest, final Path dir, final Path dataDir, final Platform platform, final Tracker tracker) {
        this.name = manifest.name();
        this.manifest = manifest;
        this.dir = dir;
        this.dataDir = dataDir;
        this.platform = platform;
        this.tracker = tracker;
        this.errors = new ErrorLog(platform.logger(), "[py:" + this.name + "] ", dir);
    }

    public String name() {
        return this.name;
    }

    public State state() {
        return this.state;
    }

    /** Можно ли сейчас звать Python (активна или ещё грузится). */
    boolean isLive() {
        final State s = this.state;
        return s == State.ACTIVE || s == State.LOADING;
    }

    String prefix() {
        return "py-" + this.name;
    }

    // ------------------------------------------------------------------------------------------
    // Регистрации
    // ------------------------------------------------------------------------------------------

    /** Во время загрузки — откладывает, после — выполняет сразу. */
    synchronized void whenActive(final Runnable registration) {
        if (this.state == State.LOADING) {
            this.pending.add(registration);
        } else if (this.state == State.ACTIVE) {
            registration.run();
        } else {
            throw new IllegalStateException("система " + this.name + " выгружается");
        }
    }

    /** Модуль выполнен: включить отложенные регистрации и команды. */
    void activate() {
        final List<Runnable> todo;
        synchronized (this) {
            this.state = State.ACTIVE;
            todo = List.copyOf(this.pending);
            this.pending.clear();
        }
        for (final Runnable r : todo) {
            r.run();
        }
        this.platform.registerCommands(this.prefix(), List.copyOf(this.commands));
    }

    void track(final Tasks.Owned task) {
        this.tasks.add(task);
        if (!this.isLive()) {
            task.cancel();
        }
    }

    void forget(final Tasks.Owned task) {
        this.tasks.remove(task);
    }

    // ------------------------------------------------------------------------------------------
    // Вызовы Python
    // ------------------------------------------------------------------------------------------

    /** Вызвать функцию Python, если система жива. Ошибки Python пишутся в лог (не чаще раза в 30 с на одинаковую). */
    Value call(final String where, final Value fn, final Object... args) {
        if (!this.isLive()) {
            return null;
        }
        return this.invoke(where, fn, args);
    }

    /** Вызвать независимо от состояния (хуки выгрузки). */
    Value invoke(final String where, final Value fn, final Object... args) {
        this.calls.incrementAndGet();
        try {
            return fn.execute(args);
        } catch (final PolyglotException e) {
            if (e.isCancelled() || this.state == State.CLOSED) {
                return null;
            }
            this.errors.report(where, e);
            return null;
        } catch (final IllegalStateException e) {
            // Контекст закрыт (выгрузка в другом потоке) — молча.
            if (this.state == State.CLOSED || this.state == State.CLOSING) {
                return null;
            }
            this.errors.report(where, e);
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Выгрузка
    // ------------------------------------------------------------------------------------------

    /**
     * Снять всё. Можно звать из любого потока; команды убираются в глобальном потоке, атрибуты/эффекты игроков — в их
     * потоках (только Java-код: к этому моменту Python уже закрыт).
     */
    void shutdown() {
        synchronized (this) {
            if (this.state == State.CLOSING || this.state == State.CLOSED) {
                return;
            }
            final boolean wasActive = this.state == State.ACTIVE;
            this.state = State.CLOSING;
            this.pending.clear();
            if (!wasActive) {
                this.loadHooks.clear();
            }
        }
        this.platform.unregisterListener(this.owner);
        for (final Tasks.Owned t : List.copyOf(this.tasks)) {
            t.cancel();
        }
        this.tasks.clear();
        for (final Value hook : this.unloadHooks) {
            this.invoke("on_unload", hook);
        }
        this.unloadHooks.clear();
        this.loadHooks.clear();
        // Хуки могли что-то запланировать — ещё раз.
        for (final Tasks.Owned t : List.copyOf(this.tasks)) {
            t.cancel();
        }
        this.tracker.cleanup(this.platform);
        this.platform.unregisterCommands(this.prefix(), List.copyOf(this.commands));
        this.commands.clear();
        this.state = State.CLOSED;
        this.closeContext();
    }

    private void closeContext() {
        final Context c = this.context;
        this.context = null;
        if (c == null) {
            return;
        }
        try {
            c.close(true);
        } catch (final IllegalStateException inside) {
            // Выгрузка изнутри самого скрипта (команда из Python): закрыть, когда поток из него выйдет.
            this.platform.runAsync(() -> closeQuietly(c, this), 50, 0);
        } catch (final RuntimeException e) {
            this.platform.logger().log(Level.WARNING, "[py:" + this.name + "] контекст закрыт с ошибкой", e);
        }
    }

    private static void closeQuietly(final Context c, final PyScript s) {
        try {
            c.close(true);
        } catch (final RuntimeException e) {
            s.platform.logger().log(Level.WARNING, "[py:" + s.name + "] контекст закрыт с ошибкой", e);
        }
    }
}
