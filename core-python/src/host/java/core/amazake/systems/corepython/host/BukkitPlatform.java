package core.amazake.systems.corepython.host;

import core.amazake.systems.api.SystemContext;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;

/** {@link Platform} на сервере Amazake: задачи — через SystemContext и региональные планировщики Paper. */
final class BukkitPlatform implements Platform {

    private final SystemContext context;

    BukkitPlatform(final SystemContext context) {
        this.context = context;
    }

    private Plugin plugin() {
        return this.context.plugin();
    }

    @Override
    public Logger logger() {
        return this.context.logger();
    }

    @Override
    public void registerEvent(final Class<? extends Event> type, final Listener listener, final EventPriority priority,
                              final EventExecutor executor, final boolean ignoreCancelled) {
        Bukkit.getPluginManager().registerEvent(type, listener, priority, executor, this.plugin(), ignoreCancelled);
    }

    @Override
    public void unregisterListener(final Listener listener) {
        HandlerList.unregisterAll(listener);
    }

    @Override
    public void registerCommands(final String fallbackPrefix, final List<Command> commands) {
        if (commands.isEmpty()) {
            return;
        }
        this.onGlobal(() -> {
            final CommandMap map = Bukkit.getCommandMap();
            for (final Command c : commands) {
                if (!map.register(c.getLabel(), fallbackPrefix, c)) {
                    this.logger().warning("core-python: команда /" + c.getName() + " уже занята — доступна как /" + fallbackPrefix + ":" + c.getName());
                }
            }
            syncCommands();
        });
    }

    @Override
    public void unregisterCommands(final String fallbackPrefix, final List<Command> commands) {
        if (commands.isEmpty()) {
            return;
        }
        this.onGlobal(() -> {
            final CommandMap map = Bukkit.getCommandMap();
            final Map<String, Command> known = map.getKnownCommands();
            final String prefix = fallbackPrefix.toLowerCase(Locale.ROOT) + ":";
            for (final Command command : commands) {
                final Set<String> labels = new HashSet<>();
                labels.add(command.getName());
                labels.add(command.getLabel());
                labels.addAll(command.getAliases());
                for (final String label : labels) {
                    final String lower = label.toLowerCase(Locale.ROOT).trim();
                    for (final String key : List.of(lower, prefix + lower)) {
                        if (known.get(key) == command) {
                            known.remove(key);
                        }
                    }
                }
                command.unregister(map);
            }
            syncCommands();
            refreshHelp();
        });
    }

    private void onGlobal(final Runnable r) {
        if (Bukkit.isGlobalTickThread()) {
            r.run();
        } else {
            Bukkit.getGlobalRegionScheduler().execute(this.plugin(), r);
        }
    }

    /** Новое дерево команд — всем игрокам (CraftServer#syncCommands, не в API — через отражение). */
    private void syncCommands() {
        try {
            Bukkit.getServer().getClass().getMethod("syncCommands").invoke(Bukkit.getServer());
        } catch (final ReflectiveOperationException | RuntimeException e) {
            this.logger().log(Level.FINE, "core-python: syncCommands недоступен", e);
        }
    }

    /** Темы /help держат объекты команд — пересобрать после удаления. */
    private void refreshHelp() {
        try {
            final Object help = Bukkit.getHelpMap();
            help.getClass().getMethod("clear").invoke(help);
            help.getClass().getMethod("initializeGeneralTopics").invoke(help);
            help.getClass().getMethod("initializeCommands").invoke(help);
        } catch (final ReflectiveOperationException | RuntimeException e) {
            this.logger().log(Level.FINE, "core-python: не удалось обновить /help", e);
        }
    }

    @Override
    public Task runForPlayer(final Player player, final Runnable body, final long periodTicks) {
        final ScheduledTask t = this.context.runForPlayer(player, task -> body.run(), Math.max(1L, periodTicks));
        return t == null ? Tasks.DONE : Tasks.of(t);
    }

    @Override
    public boolean runOnceForPlayer(final Player player, final Runnable body) {
        return this.context.runOnceForPlayer(player, body);
    }

    @Override
    public Task runInRegion(final Location location, final Runnable body, final long delayTicks, final long periodTicks) {
        final var s = Bukkit.getRegionScheduler();
        final ScheduledTask t;
        if (periodTicks > 0) {
            t = s.runAtFixedRate(this.plugin(), location, task -> body.run(), Math.max(1L, delayTicks), periodTicks);
        } else if (delayTicks > 0) {
            t = s.runDelayed(this.plugin(), location, task -> body.run(), delayTicks);
        } else {
            t = s.run(this.plugin(), location, task -> body.run());
        }
        return Tasks.of(t);
    }

    @Override
    public Task runGlobal(final Runnable body, final long delayTicks, final long periodTicks) {
        final var s = Bukkit.getGlobalRegionScheduler();
        final ScheduledTask t;
        if (periodTicks > 0) {
            t = s.runAtFixedRate(this.plugin(), task -> body.run(), Math.max(1L, delayTicks), periodTicks);
        } else if (delayTicks > 0) {
            t = s.runDelayed(this.plugin(), task -> body.run(), delayTicks);
        } else {
            t = s.run(this.plugin(), task -> body.run());
        }
        return Tasks.of(t);
    }

    @Override
    public Task runAsync(final Runnable body, final long delayMillis, final long periodMillis) {
        final var s = Bukkit.getAsyncScheduler();
        final ScheduledTask t;
        if (periodMillis > 0) {
            t = s.runAtFixedRate(this.plugin(), task -> body.run(), Math.max(1L, delayMillis), periodMillis, TimeUnit.MILLISECONDS);
        } else if (delayMillis > 0) {
            t = s.runDelayed(this.plugin(), task -> body.run(), delayMillis, TimeUnit.MILLISECONDS);
        } else {
            t = s.runNow(this.plugin(), task -> body.run());
        }
        return Tasks.of(t);
    }

    @Override
    public boolean owns(final Object thing) {
        return switch (thing) {
            case Entity e -> Bukkit.isOwnedByCurrentRegion(e);
            case Location l -> Bukkit.isOwnedByCurrentRegion(l);
            case Block b -> Bukkit.isOwnedByCurrentRegion(b);
            case null, default -> false;
        };
    }

    @Override
    public boolean isGlobalThread() {
        return Bukkit.isGlobalTickThread();
    }

    @Override
    public Class<?> findClass(final String name) throws ClassNotFoundException {
        try {
            return Class.forName(name, false, Bukkit.class.getClassLoader());
        } catch (final ClassNotFoundException e) {
            for (final Plugin p : Bukkit.getPluginManager().getPlugins()) {
                try {
                    return Class.forName(name, false, p.getClass().getClassLoader());
                } catch (final ClassNotFoundException | LinkageError ignored) {
                    // следующий плагин
                }
            }
            throw e;
        }
    }

    @Override
    public Player player(final java.util.UUID id) {
        return Bukkit.getPlayer(id);
    }

    @Override
    public ClassLoader pluginLoader(final String plugin) {
        final Plugin p = Bukkit.getPluginManager().getPlugin(plugin);
        return p == null || !p.isEnabled() ? null : p.getClass().getClassLoader();
    }
}
