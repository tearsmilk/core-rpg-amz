package core.amazake.systems.corepython.host;

import java.util.List;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;

/**
 * Всё, что хост делает с сервером. На сервере — {@link BukkitPlatform} (правила потоков Amazake), в тестах — подделка,
 * которая записывает регистрации и выполняет задачи сразу.
 */
public interface Platform {

    Logger logger();

    void registerEvent(Class<? extends Event> type, Listener listener, EventPriority priority, EventExecutor executor, boolean ignoreCancelled);

    void unregisterListener(Listener listener);

    /** Регистрирует команды (в глобальном потоке) и рассылает дерево команд игрокам. */
    void registerCommands(String fallbackPrefix, List<Command> commands);

    /** Убирает команды из карты команд (в глобальном потоке) и рассылает дерево команд. */
    void unregisterCommands(String fallbackPrefix, List<Command> commands);

    Task runForPlayer(Player player, Runnable body, long periodTicks);

    /** Один раз в потоке игрока (через тик или сразу, если система выключается в его потоке). */
    boolean runOnceForPlayer(Player player, Runnable body);

    Task runInRegion(Location location, Runnable body, long delayTicks, long periodTicks);

    Task runGlobal(Runnable body, long delayTicks, long periodTicks);

    Task runAsync(Runnable body, long delayMillis, long periodMillis);

    /** Владеет ли текущий поток сущностью / местом / блоком (Bukkit.isOwnedByCurrentRegion). */
    boolean owns(Object thing);

    /** Глобальный поток тиков? */
    boolean isGlobalThread();

    /** Класс по имени: сервер, затем загрузчики плагинов. */
    Class<?> findClass(String name) throws ClassNotFoundException;

    /** Игрок онлайн по UUID, или null. */
    Player player(java.util.UUID id);

    /** Загрузчик классов плагина (для его API), или null. */
    ClassLoader pluginLoader(String plugin);
}
