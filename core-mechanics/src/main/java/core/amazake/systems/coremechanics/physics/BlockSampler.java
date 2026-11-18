package core.amazake.systems.coremechanics.physics;

/** Источник геометрии мира для {@link SectionMesher} (не зависит от Bukkit — мешер тестируется без сервера). */
public interface BlockSampler {

    /**
     * Коллизионные боксы блока в его координатах [0..1]: по 6 чисел на бокс (minX, minY, minZ, maxX, maxY, maxZ).
     * Пустой массив — без коллизии. Полный куб — именно {@link SectionMesher#FULL} (по ссылке).
     */
    float[] boxes(int x, int y, int z);

    /** Блок — полный куб (соседние грани можно не строить). */
    default boolean full(final int x, final int y, final int z) {
        return this.boxes(x, y, z) == SectionMesher.FULL;
    }
}
