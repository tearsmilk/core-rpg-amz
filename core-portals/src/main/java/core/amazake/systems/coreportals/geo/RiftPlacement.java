package core.amazake.systems.coreportals.geo;

import java.util.Optional;

/**
 * Лазуритовый разлом — большой вертикальный овал {@link Size#width()} × {@link Size#height()} блоков с центром на
 * {@link Size#centerUp()} над землёй, плоскость перпендикулярна взгляду игрока. Чистая математика, без Bukkit.
 *
 * <p>Место: ряд блоков на {@code distance} шагов впереди (взгляд округлён до стороны света), нечётное число столбцов
 * по ширине — средний напротив игрока (центр разлома — середина среднего блока), {@link #columnsPerSide} столбцов в
 * каждую сторону, {@link #clearHeight} блоков в высоту. Уровень земли ищется как у «Портала»: 0, −1, +1, −2, +2… до
 * {@code search}.</p>
 *
 * <p>Правило места ({@link #fits}) — разлом огромный, поэтому «всё свободно» не требуется:</p>
 * <ul>
 *   <li>под средним столбцом — твёрдая опора;</li>
 *   <li>проход — {@link #ENTRY_BLOCKS} нижних блока среднего столбца и соседних с ним (3 × 2; у разлома в 1 столбец
 *       — 1 × 2) — свободен (проходим, не лава): в разлом можно войти, и он не «проваливается» в яму;</li>
 *   <li>в овале (блоки, чей центр внутри овала разлома; углы описанного прямоугольника не считаются) занято не
 *       больше {@link #MAX_BLOCKED} блоков;</li>
 *   <li>в середине овала (центр блока внутри овала с полуосями × {@link #CORE}) занято не больше
 *       {@link #MAX_CORE_BLOCKED}.</li>
 * </ul>
 * <p>«Занято» — непроходимый блок или лава; трава, цветы, снег-слой, воздух не мешают (см. {@link BlockQuery}).
 * Блоки не меняются: разлом — только дисплеи, торчащие в нём блоки просто видны сквозь него.</p>
 *
 * <p>Вход: хитбокс игрока задевает овал входа (полуоси {@link #ENTRY_SCALE} от полуосей разлома) в слое
 * ±{@link #ENTRY_HALF_DEPTH} по нормали.</p>
 */
public final class RiftPlacement {

    /** Полутолщина объёма входа по нормали. */
    public static final double ENTRY_HALF_DEPTH = 0.35;
    /** Овал входа — чуть меньше видимого (зубчатый край не телепортирует). */
    public static final double ENTRY_SCALE = 0.92;
    /** Допуск: край разлома может заходить в соседний блок на столько, не требуя его свободным. */
    static final double TOLERANCE = 0.2;
    /** Свободных блоков прохода от земли (в среднем столбце и по одному соседнему с каждой стороны). */
    public static final int ENTRY_BLOCKS = 2;
    /** Доля занятых блоков в овале, которую ещё терпим. */
    public static final double MAX_BLOCKED = 0.2;
    /** Середина овала — доля полуосей. */
    public static final double CORE = 0.5;
    /** Доля занятых блоков в середине овала, которую ещё терпим. */
    public static final double MAX_CORE_BLOCKED = 0.1;

    /**
     * Размер разлома.
     *
     * @param width    ширина овала, блоков
     * @param height   высота овала, блоков
     * @param centerUp высота центра над землёй, блоков
     */
    public record Size(double width, double height, double centerUp) {

        /** По умолчанию: огромный 9 × 13, центр на 6,6 (низ — на 0,1 над землёй, верх — 13,1). */
        public static final Size DEFAULT = new Size(9.0, 13.0, 6.6);

        /** Размер, под который нарисованы частицы и звуки (старый 5 × 7). */
        public static final Size REFERENCE = new Size(5.0, 7.0, 3.6);

        public double halfWidth() {
            return this.width / 2;
        }

        public double halfHeight() {
            return this.height / 2;
        }

        /** Низ овала над землёй (может быть чуть меньше 0: край уходит в пол). */
        public double bottom() {
            return this.centerUp - this.height / 2;
        }

        /** Верх овала над землёй. */
        public double top() {
            return this.centerUp + this.height / 2;
        }

        /** Во сколько раз разлом больше эталонного 5 × 7 (среднее по осям): им масштабируются частицы и звук. */
        public double scale() {
            return (this.width / REFERENCE.width + this.height / REFERENCE.height) / 2;
        }
    }

    private RiftPlacement() {
    }

    /** Столбцов по каждую сторону от среднего: 5 блоков ширины → 2, 6 → 3. */
    public static int columnsPerSide(final Size size) {
        return Math.max(0, (int) Math.ceil(size.halfWidth() - 0.5 - TOLERANCE));
    }

    /** Свободных блоков в высоту над землёй: 7,1 → 7. */
    public static int clearHeight(final Size size) {
        return Math.max(1, (int) Math.ceil(size.top() - TOLERANCE));
    }

    /**
     * @param px       x игрока
     * @param py       y ног игрока
     * @param pz       z игрока
     * @param yaw      yaw взгляда игрока
     * @param distance сколько рядов блоков вперёд
     * @param search   на сколько блоков вниз и вверх искать землю
     * @param size     размер разлома
     * @return место: {@code (x, y, z)} — точка земли под центром разлома, {@code blocks} — столбцы
     *         {@code {x1, z1, x2, z2, …}} слева направо (средний — посередине)
     */
    public static Optional<Spot> find(final double px, final double py, final double pz, final float yaw,
                                      final int distance, final int search, final Size size, final BlockQuery blocks) {
        final Facing f = Facing.fromYaw(yaw);
        final int bx = (int) Math.floor(px);
        final int bz = (int) Math.floor(pz);
        final int side = columnsPerSide(size);
        final int n = 2 * side + 1;
        final int[] cols = new int[2 * n];
        final double cx;
        final double cz;
        if (f.alongX()) {
            final int row = bx + f.dx() * distance;
            for (int i = 0; i < n; i++) {
                cols[2 * i] = row;
                cols[2 * i + 1] = bz - side + i;
            }
            cx = row + 0.5;
            cz = bz + 0.5;
        } else {
            final int row = bz + f.dz() * distance;
            for (int i = 0; i < n; i++) {
                cols[2 * i] = bx - side + i;
                cols[2 * i + 1] = row;
            }
            cx = bx + 0.5;
            cz = row + 0.5;
        }
        final int height = clearHeight(size);
        final int y0 = (int) Math.floor(py + 1.0e-6);
        for (int i = 0; i <= search * 2; i++) {
            final int y = y0 + Placement.order(i);
            if (fits(blocks, cols, side, height, y, size)) {
                return Optional.of(new Spot(cx, y, cz, f, cols));
            }
        }
        return Optional.empty();
    }

    /** Подсчёт блоков места на одном уровне земли (для правила и тестов). */
    record Count(int oval, int blocked, int core, int coreBlocked) {

        boolean ok() {
            return this.blocked <= Math.floor(this.oval * MAX_BLOCKED + 1e-9)
                && this.coreBlocked <= Math.floor(this.core * MAX_CORE_BLOCKED + 1e-9);
        }
    }

    /** Помещается ли разлом на уровне земли {@code y}: правило — в описании класса. */
    static boolean fits(final BlockQuery q, final int[] cols, final int side, final int height, final int y,
                        final Size size) {
        final int mx = cols[2 * side];
        final int mz = cols[2 * side + 1];
        if (!q.solid(mx, y - 1, mz)) {
            return false;
        }
        final int entrySide = Math.min(1, side);
        for (int i = side - entrySide; i <= side + entrySide; i++) {
            for (int h = 0; h < Math.min(ENTRY_BLOCKS, height); h++) {
                if (!q.passable(cols[2 * i], y + h, cols[2 * i + 1])) {
                    return false;
                }
            }
        }
        return count(q, cols, side, height, y, size).ok();
    }

    static Count count(final BlockQuery q, final int[] cols, final int side, final int height, final int y,
                       final Size size) {
        // Средний столбец всегда в овале, даже у разлома уже блока.
        final double a = Math.max(size.halfWidth(), 0.5);
        final double b = Math.max(size.halfHeight(), 0.5);
        int oval = 0;
        int blocked = 0;
        int core = 0;
        int coreBlocked = 0;
        for (int i = 0; i < cols.length / 2; i++) {
            final double u = (i - side) / a;
            for (int h = 0; h < height; h++) {
                final double v = (h + 0.5 - size.centerUp()) / b;
                final double e = u * u + v * v;
                if (e > 1) {
                    continue;
                }
                final boolean free = q.passable(cols[2 * i], y + h, cols[2 * i + 1]);
                oval++;
                if (!free) {
                    blocked++;
                }
                if (e <= CORE * CORE) {
                    core++;
                    if (!free) {
                        coreBlocked++;
                    }
                }
            }
        }
        return new Count(oval, blocked, core, coreBlocked);
    }

    /**
     * Задевает ли объём (хитбокс игрока) овал входа: объём пересекает слой ±{@link #ENTRY_HALF_DEPTH} у плоскости
     * разлома, и его проекция на плоскость пересекает овал с полуосями {@link #ENTRY_SCALE} × полуоси разлома.
     */
    public static boolean touches(final Spot spot, final Size size, final double minX, final double minY,
                                  final double minZ, final double maxX, final double maxY, final double maxZ) {
        final double[] d = local(spot, minX, minZ, maxX, maxZ);
        if (Math.max(d[0], d[1]) <= -ENTRY_HALF_DEPTH || Math.min(d[0], d[1]) >= ENTRY_HALF_DEPTH) {
            return false;
        }
        final double a = size.halfWidth() * ENTRY_SCALE;
        final double b = size.halfHeight() * ENTRY_SCALE;
        final double cy = spot.y() + size.centerUp();
        // В координатах, где овал — единичный круг, ближайшая к центру точка прямоугольника — точная проверка.
        final double u = nearestToZero(Math.min(d[2], d[3]) / a, Math.max(d[2], d[3]) / a);
        final double v = nearestToZero((minY - cy) / b, (maxY - cy) / b);
        return u * u + v * v < 1;
    }

    /**
     * Расстояние от точки до прямоугольника разлома (ширина × высота в его плоскости), блоков. От него зависят
     * свечение на экране и затягивание.
     */
    public static double distance(final Spot spot, final Size size, final double x, final double y, final double z) {
        final double[] n = nearest(spot, size, x, y, z);
        final double dx = x - n[0];
        final double dy = y - n[1];
        final double dz = z - n[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Ближайшая к точке точка прямоугольника разлома (в мире). */
    public static double[] nearest(final Spot spot, final Size size, final double x, final double y, final double z) {
        final Facing f = spot.facing();
        final double sx = -f.dz();
        final double sz = f.dx();
        final double side = (x - spot.x()) * sx + (z - spot.z()) * sz;
        final double s = clamp(side, -size.halfWidth(), size.halfWidth());
        final double up = clamp(y - spot.y(), size.bottom(), size.top());
        return spot.toWorld(s, up, 0);
    }

    /** Глубина (по нормали) и сдвиг вбок объёма относительно центра: {d0, d1, s0, s1}. */
    private static double[] local(final Spot spot, final double minX, final double minZ, final double maxX,
                                  final double maxZ) {
        final Facing f = spot.facing();
        if (f.alongX()) {
            return new double[] {
                (minX - spot.x()) * f.dx(), (maxX - spot.x()) * f.dx(),
                (minZ - spot.z()) * f.dx(), (maxZ - spot.z()) * f.dx(),
            };
        }
        return new double[] {
            (minZ - spot.z()) * f.dz(), (maxZ - spot.z()) * f.dz(),
            (minX - spot.x()) * -f.dz(), (maxX - spot.x()) * -f.dz(),
        };
    }

    private static double clamp(final double v, final double lo, final double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** Ближайшая к нулю точка отрезка {@code [lo, hi]}. */
    private static double nearestToZero(final double lo, final double hi) {
        return lo > 0 ? lo : (hi < 0 ? hi : 0);
    }
}
