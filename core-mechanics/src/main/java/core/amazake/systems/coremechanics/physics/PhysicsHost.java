package core.amazake.systems.coremechanics.physics;

/**
 * Физдвижок (реализация — host.JoltHost, загружается HostLoader-ом поверх общего загрузчика Jolt).
 *
 * <p>Код системы (main) не видит классов Jolt: он говорит с физикой только через эти интерфейсы. Все методы
 * {@link PhysicsHost}, {@link PhysicsWorld} и {@link RagdollBody} вызываются из ОДНОГО потока — потока физики
 * ({@code core-mechanics-physics}); сами они не синхронизированы.</p>
 */
public interface PhysicsHost {

    /**
     * Запустить: фабрика типов Jolt (раз на JVM), слои, аллокатор, пул потоков, самотест.
     *
     * @param threads потоков Jolt (0 — авто: половина ядер, не больше 4)
     * @throws Exception самотест провален или нативка не работает
     */
    void start(int threads) throws Exception;

    /** Версия Jolt (строка из нативки). */
    String version();

    /** Отчёт самотеста («box-floor y=0.500 …»). */
    String selfTest();

    /** Потоков в пуле Jolt. */
    int threads();

    /** Новая физ-система (одна на мир Bukkit). */
    PhysicsWorld newWorld(int maxBodies, float gravity, int substeps);

    /** Остановить: пул потоков, аллокатор. Миры должны быть уже уничтожены. Фабрика типов живёт до конца JVM. */
    void shutdown();
}
