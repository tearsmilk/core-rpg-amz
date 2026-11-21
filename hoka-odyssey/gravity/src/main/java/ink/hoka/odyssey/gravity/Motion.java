package ink.hoka.odyssey.gravity;

/**
 * Чистая математика тяги (без Minestom). Скорости — блоков за тик, как у клиента. Клиент Minecraft 1.21 в воздухе
 * каждый тик: прибавляет гравитацию (у нас 0), сдвигается, затем умножает вертикальную скорость на 0.98,
 * горизонтальную — на 0.91 (трение воздуха). Импульс сервера прибавляется к скорости клиента (пакет взрыва с
 * отдачей игроку), поэтому считаем его так, чтобы после клиентского затухания получилась нужная скорость.
 */
final class Motion {
    /** Затухание вертикальной скорости у клиента за тик. */
    static final double CLIENT_VERTICAL_DRAG = 0.98;
    /** Трение воздуха по горизонтали у клиента за тик. */
    static final double CLIENT_AIR_FRICTION = 0.91;
    /** Клиент обнуляет компоненту скорости меньше этой (блоков/тик). */
    static final double CLIENT_NEGLIGIBLE = 0.003;
    /** Импульсы меньше этого не шлём. */
    static final double MIN_IMPULSE = 1.0e-4;
    /** Предел «поддержки инерции» по горизонтали за тик — защита от разгона по устаревшим замерам. */
    static final double MAX_GLIDE_IMPULSE = 0.05;

    private Motion() {}

    /** Какая доля скорости остаётся за тик, если за halfLifeSec секунд она падает вдвое. 0 и меньше — 0. */
    static double keepPerTick(double halfLifeSec) {
        if (!(halfLifeSec > 0)) {
            return 0;
        }
        return Math.pow(0.5, 1.0 / (halfLifeSec * Settings.TPS));
    }

    /**
     * Вертикальный импульс на этот тик.
     *
     * @param v   текущая вертикальная скорость клиента (модель), блоков/тик
     * @param dir 1 — вверх (пробел), -1 — вниз (Shift), 0 — без клавиш
     */
    static double verticalImpulse(double v, int dir, Settings s) {
        double want;
        if (dir != 0) {
            want = dir * s.vMax() / CLIENT_VERTICAL_DRAG - v;      // после затухания клиента — ровно ±vMax
        } else {
            want = v * (s.vKeep() / CLIENT_VERTICAL_DRAG - 1);      // гасим до доли vKeep за тик
        }
        double imp = clamp(want, -s.vThrust(), s.vThrust());
        return Math.abs(imp) < MIN_IMPULSE ? 0 : imp;
    }

    /** Вертикальная скорость клиента через тик после импульса. */
    static double nextVertical(double v, double impulse) {
        double n = v + impulse;
        if (Math.abs(n) < CLIENT_NEGLIGIBLE) {
            return 0;
        }
        return n * CLIENT_VERTICAL_DRAG;
    }

    /**
     * Горизонтальный импульс {x, z} на этот тик.
     *
     * @param yaw      поворот игрока (градусы Minecraft: 0 — на юг, +z)
     * @param forward  1 — W, -1 — S, 0
     * @param strafe   1 — A (влево), -1 — D (вправо), 0
     * @param mx       замеренная скорость по x, блоков/тик
     * @param mz       замеренная скорость по z, блоков/тик
     * @param onGround на земле — не вмешиваемся, там обычная ходьба
     */
    static double[] horizontalImpulse(float yaw, int forward, int strafe, double mx, double mz, boolean onGround,
                                      Settings s) {
        double[] zero = {0, 0};
        if (onGround) {
            return zero;
        }
        if (forward != 0 || strafe != 0) {
            if (s.hThrust() <= 0) {
                return zero;
            }
            double[] d = direction(yaw, forward, strafe);
            if (mx * d[0] + mz * d[1] >= s.hMax()) {
                return zero;                                    // и так быстро летит в эту сторону
            }
            return new double[] {d[0] * s.hThrust(), d[1] * s.hThrust()};
        }
        if (s.hKeep() <= 0) {
            return zero;
        }
        double k = s.hKeep() / CLIENT_AIR_FRICTION - 1;
        double ix = mx * k;
        double iz = mz * k;
        double len = Math.hypot(ix, iz);
        if (len < MIN_IMPULSE) {
            return zero;
        }
        if (len > MAX_GLIDE_IMPULSE) {
            ix *= MAX_GLIDE_IMPULSE / len;
            iz *= MAX_GLIDE_IMPULSE / len;
        }
        return new double[] {ix, iz};
    }

    /** Единичное направление {x, z} для клавиш, как у клиента (moveRelative). */
    static double[] direction(float yaw, int forward, int strafe) {
        double r = Math.toRadians(yaw);
        double sin = Math.sin(r);
        double cos = Math.cos(r);
        double x = strafe * cos - forward * sin;
        double z = forward * cos + strafe * sin;
        double len = Math.hypot(x, z);
        return len < 1e-9 ? new double[] {0, 0} : new double[] {x / len, z / len};
    }

    static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
