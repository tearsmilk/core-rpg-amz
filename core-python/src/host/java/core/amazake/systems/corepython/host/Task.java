package core.amazake.systems.corepython.host;

/** Задача, запущенная скриптом (то, что получает Python из run_*): её можно отменить. */
public interface Task {

    /** Отменить задачу (повторный вызов ничего не делает). */
    void cancel();

    /** Отменена ли задача (или скрипт выгружен). */
    boolean isCancelled();
}
