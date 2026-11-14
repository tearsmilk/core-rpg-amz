package core.amazake.systems.coreworld.module;

import core.amazake.systems.api.SystemContext;
import java.util.List;
import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Модуль правил мира: одна возможность со своим разделом в config.yml и своим переключателем.
 *
 * <p>Жизненный цикл (всё в глобальном потоке тика): {@link #enable} один раз при включении системы, затем
 * {@link #configure} при включении и при каждом reload, {@link #disable} при выключении системы. Обработчики событий
 * регистрируются в {@code enable} всегда, а выключенный в конфиге модуль просто ничего в них не делает: снять
 * обработчик поштучно ядро не даёт, а лишний пустой обработчик ничего не стоит.</p>
 *
 * <p>Новый модуль: класс в своём пакете ({@code coreworld.<модуль>}), раздел в config.yml и строка в
 * {@code CoreWorldSystem#createModules}.</p>
 */
public interface WorldModule {

    /** Имя модуля и его раздела в config.yml, например {@code hunger}. */
    String id();

    /** Один раз при включении системы: зарегистрировать обработчики через {@code context}. */
    void enable(SystemContext context);

    /** Применить настройки (весь config.yml). При включении и каждом reload; ошибки в значениях — в {@code warn}. */
    void configure(ConfigurationSection config, Consumer<String> warn);

    /** Выключение системы: вернуть игрокам то, о чём ядро не знает, отменить свои задачи. */
    void disable();

    /** Включён ли модуль в config.yml сейчас. */
    boolean active();

    /** Строки для /coreworld status (MiniMessage). */
    List<String> status();
}
