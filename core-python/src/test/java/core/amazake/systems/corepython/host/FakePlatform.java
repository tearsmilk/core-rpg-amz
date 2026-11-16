package core.amazake.systems.corepython.host;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;

/** Платформа для тестов: записывает регистрации, задачи выполняет сразу (повторяющиеся — по runPending). */
final class FakePlatform implements Platform {

    record Reg(Class<? extends Event> type, Listener listener, EventPriority priority, EventExecutor executor) {
    }

    final List<Reg> listeners = new CopyOnWriteArrayList<>();
    final List<Command> commands = new CopyOnWriteArrayList<>();
    final List<Runnable> repeating = new CopyOnWriteArrayList<>();
    final List<String> log = new CopyOnWriteArrayList<>();
    /** Задачи планировщиков сущностей (выполняются по runEntityTasks). */
    final List<Runnable> entityTasks = new CopyOnWriteArrayList<>();
    /** Владеет ли текущий поток всем (по умолчанию — да: задачи выполняются сразу). */
    volatile boolean ownsAll = true;
    volatile boolean stopping;
    final Plugin plugin = (Plugin) java.lang.reflect.Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class},
        (p, m, a) -> switch (m.getName()) {
            case "getName" -> "core-python";
            case "isEnabled" -> true;
            case "hashCode" -> 7;
            case "equals" -> p == a[0];
            case "toString" -> "FakePlugin";
            default -> null;
        });
    private final Logger logger = Logger.getLogger("fake-" + UUID.randomUUID());

    FakePlatform() {
        this.logger.setUseParentHandlers(false);
        this.logger.addHandler(new Handler() {
            @Override
            public void publish(final LogRecord r) {
                FakePlatform.this.log.add(r.getLevel() + " " + r.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
    }

    /** Вызвать все слушатели события (как Bukkit). */
    void fire(final Event e) throws Exception {
        for (final Reg r : this.listeners) {
            if (r.type().isInstance(e)) {
                r.executor().execute(r.listener(), e);
            }
        }
    }

    Command command(final String name) {
        return this.commands.stream().filter(c -> c.getName().equals(name)).findFirst().orElse(null);
    }

    @Override
    public Logger logger() {
        return this.logger;
    }

    @Override
    public void registerEvent(final Class<? extends Event> type, final Listener listener, final EventPriority priority, final EventExecutor executor,
                              final boolean ignoreCancelled) {
        this.listeners.add(new Reg(type, listener, priority, executor));
    }

    @Override
    public void unregisterListener(final Listener listener) {
        this.listeners.removeIf(r -> r.listener() == listener);
    }

    @Override
    public void registerCommands(final String fallbackPrefix, final List<Command> commands) {
        this.commands.addAll(commands);
    }

    @Override
    public void unregisterCommands(final String fallbackPrefix, final List<Command> commands) {
        this.commands.removeAll(commands);
    }

    private Task repeating(final Runnable body) {
        final boolean[] cancelled = {false};
        final Runnable guarded = () -> {
            if (!cancelled[0]) {
                body.run();
            }
        };
        this.repeating.add(guarded);
        return new Task() {
            @Override
            public void cancel() {
                cancelled[0] = true;
                FakePlatform.this.repeating.remove(guarded);
            }

            @Override
            public boolean isCancelled() {
                return cancelled[0];
            }
        };
    }

    void tick() {
        for (final Runnable r : new ArrayList<>(this.repeating)) {
            r.run();
        }
    }

    @Override
    public Task runForPlayer(final Player player, final Runnable body, final long periodTicks) {
        return this.repeating(body);
    }

    @Override
    public boolean runOnceForPlayer(final Player player, final Runnable body) {
        body.run();
        return true;
    }

    @Override
    public Task runInRegion(final Location location, final Runnable body, final long delayTicks, final long periodTicks) {
        return this.runGlobal(body, delayTicks, periodTicks);
    }

    @Override
    public Task runGlobal(final Runnable body, final long delayTicks, final long periodTicks) {
        if (periodTicks > 0) {
            return this.repeating(body);
        }
        body.run();
        return Tasks.DONE;
    }

    @Override
    public Task runAsync(final Runnable body, final long delayMillis, final long periodMillis) {
        return this.runGlobal(body, 0, periodMillis);
    }

    @Override
    public boolean owns(final Object thing) {
        return this.ownsAll;
    }

    @Override
    public boolean isGlobalThread() {
        return true;
    }

    @Override
    public Class<?> findClass(final String name) throws ClassNotFoundException {
        return Class.forName(name, false, FakePlatform.class.getClassLoader());
    }

    @Override
    public Player player(final UUID id) {
        return null;
    }

    @Override
    public ClassLoader pluginLoader(final String plugin) {
        return null;
    }

    @Override
    public Plugin plugin() {
        return this.plugin;
    }

    @Override
    public boolean isStopping() {
        return this.stopping;
    }

    @Override
    public boolean executeForEntity(final Entity entity, final Runnable body, final long delayTicks) {
        this.entityTasks.add(body);
        return true;
    }

    void runEntityTasks() {
        for (final Runnable r : new ArrayList<>(this.entityTasks)) {
            this.entityTasks.remove(r);
            r.run();
        }
    }
}
