package core.amazake.systems.rezusmenu.screen;

/**
 * Плоскость экрана перед игроком. Координаты на экране — (u, v) в блоках: u вправо, v вверх, (0, 0) — центр, куда
 * игрок смотрит прямо. Слой ({@code layer}) отодвигает элемент к игроку, чтобы верхние элементы не мерцали с нижними.
 *
 * <p>Экран всегда смотрит по одной из четырёх сторон света: так проще и ровнее.</p>
 *
 * @param cx центр экрана
 * @param fx направление «от игрока к экрану» (единичный, горизонтальный)
 * @param rx направление «вправо» на экране
 */
public record ScreenFrame(double cx, double cy, double cz, double fx, double fz, double rx, double rz) {

    /** Насколько слой ближе к игроку, блоков. */
    public static final double LAYER_STEP = 0.004;

    /**
     * Экран на расстоянии {@code distance} от глаз, перпендикулярно взгляду с поворотом {@code yaw}, округлённым до
     * стороны света.
     */
    public static ScreenFrame facing(final double eyeX, final double eyeY, final double eyeZ, final float yaw,
                                     final double distance) {
        final int quarter = Math.floorMod(Math.round(yaw / 90f), 4);
        // yaw 0 — юг (+Z), 90 — запад (−X), 180 — север (−Z), 270 — восток (+X)
        final double fx = switch (quarter) {
            case 1 -> -1;
            case 3 -> 1;
            default -> 0;
        };
        final double fz = switch (quarter) {
            case 0 -> 1;
            case 2 -> -1;
            default -> 0;
        };
        // вправо = f × вверх
        final double rx = -fz;
        final double rz = fx;
        return new ScreenFrame(eyeX + fx * distance, eyeY, eyeZ + fz * distance, fx, fz, rx, rz);
    }

    /** Сторона света экрана в градусах yaw. */
    public float yaw() {
        if (this.fz > 0.5) {
            return 0f;
        }
        if (this.fx < -0.5) {
            return 90f;
        }
        if (this.fz < -0.5) {
            return 180f;
        }
        return 270f;
    }

    /** Точка экрана в мире: {x, y, z}. */
    public double[] point(final double u, final double v, final int layer) {
        final double toward = -layer * LAYER_STEP;
        return new double[] {
            this.cx + this.rx * u + this.fx * toward,
            this.cy + v,
            this.cz + this.rz * u + this.fz * toward};
    }

    /**
     * Куда на экране смотрит игрок: {u, v}; null — смотрит не в сторону экрана.
     */
    public double[] hit(final double eyeX, final double eyeY, final double eyeZ, final double dx, final double dy,
                        final double dz) {
        final double denom = dx * this.fx + dz * this.fz;
        if (denom <= 1e-6) {
            return null;
        }
        final double t = ((this.cx - eyeX) * this.fx + (this.cz - eyeZ) * this.fz) / denom;
        if (t <= 0) {
            return null;
        }
        final double px = eyeX + dx * t - this.cx;
        final double py = eyeY + dy * t - this.cy;
        final double pz = eyeZ + dz * t - this.cz;
        return new double[] {px * this.rx + pz * this.rz, py};
    }
}
