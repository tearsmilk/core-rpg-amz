package core.amazake.systems.coremechanics.physics;

/**
 * Снимок коллизии одной секции 16×16×16 плюс по слою сверху и снизу (чтобы не строить грани, закрытые соседом по Y).
 * Делается в потоке региона, которому принадлежит чанк секции ({@link SectionSampler}); дальше — неизменяемые данные,
 * мешер читает их в потоке физики. Соседние чанки по X/Z не читаются (они могут принадлежать другому региону):
 * на границе чанка грани строятся всегда — лишние внутренние грани безвредны.
 *
 * @param loaded false — чанк не загружен: секция считается пустой (тела там всё равно не рисуются)
 */
public record SectionSample(int sx, int sy, int sz, boolean loaded, float[][] boxes) implements BlockSampler {

    /** Слоёв по Y в снимке: 16 + по одному сверху и снизу. */
    public static final int LAYERS = 18;

    public static SectionSample unloaded(final int sx, final int sy, final int sz) {
        return new SectionSample(sx, sy, sz, false, null);
    }

    /** Индекс в {@link #boxes}: lx, lz 0..15, ly −1..16. */
    public static int index(final int lx, final int ly, final int lz) {
        return ((ly + 1) * 16 + lz) * 16 + lx;
    }

    @Override
    public float[] boxes(final int x, final int y, final int z) {
        if (this.boxes == null) {
            return SectionMesher.EMPTY;
        }
        final int lx = x - (this.sx << 4), ly = y - (this.sy << 4), lz = z - (this.sz << 4);
        if (lx < 0 || lx > 15 || lz < 0 || lz > 15 || ly < -1 || ly > 16) {
            return SectionMesher.EMPTY;
        }
        final float[] b = this.boxes[index(lx, ly, lz)];
        return b == null ? SectionMesher.EMPTY : b;
    }

    /** Меш этой секции. */
    public SectionMesher.Result mesh() {
        return SectionMesher.mesh(this, this.sx << 4, this.sy << 4, this.sz << 4);
    }
}
