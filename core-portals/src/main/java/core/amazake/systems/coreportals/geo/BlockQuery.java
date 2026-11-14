package core.amazake.systems.coreportals.geo;

/** Что нужно знать о блоках, чтобы поставить портал. В игре — мир Bukkit, в тестах — своя сетка. */
public interface BlockQuery {

    /** Твёрдая опора: на блоке можно стоять. */
    boolean solid(int x, int y, int z);

    /** Блок не мешает порталу: воздух, трава, цветы (но не лава). */
    boolean passable(int x, int y, int z);
}
