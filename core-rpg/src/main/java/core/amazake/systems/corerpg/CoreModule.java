package core.amazake.systems.corerpg;

import core.amazake.systems.api.SystemContext;

/**
 * Модуль системы CoreRpg. Порядок жизненного цикла задаёт {@link CoreRpgSystem}: enable — по порядку, disable — в
 * обратном. Все методы вызываются в глобальном потоке тика.
 */
public interface CoreModule {

    /** Читает свои настройки, регистрирует слушатели, команды и задачи через context. */
    void enable(SystemContext context) throws Exception;

    /** Перечитывает свои настройки без потери данных игроков. */
    void reload(SystemContext context) throws Exception;

    /** Возвращает игрокам показанное (меню, модификаторы), сохраняет данные. Ядро снимет регистрации само. */
    void disable(SystemContext context);
}
