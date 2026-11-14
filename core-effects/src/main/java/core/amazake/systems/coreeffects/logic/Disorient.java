package core.amazake.systems.coreeffects.logic;

/**
 * Дезориентация паники: направление, куда игрок хотел идти (WASD относительно взгляда), повёрнутое на угол «скрутки».
 * Ось Minecraft: yaw 0 — на юг (+Z), вперёд = (−sin yaw, cos yaw), влево = (cos yaw, sin yaw).
 */
public final class Disorient {

    private Disorient() {
    }

    /**
     * Единичный горизонтальный вектор {x, z} или {@code null}, если игрок не жмёт WASD.
     *
     * @param forward  +1 W, −1 S, 0
     * @param left     +1 A, −1 D, 0
     * @param yawDeg   поворот взгляда
     * @param twistDeg на сколько повернуть (по часовой, если смотреть сверху)
     */
    public static double[] direction(final int forward, final int left, final float yawDeg, final double twistDeg) {
        if (forward == 0 && left == 0) {
            return null;
        }
        final double yaw = Math.toRadians(yawDeg);
        double x = forward * -Math.sin(yaw) + left * Math.cos(yaw);
        double z = forward * Math.cos(yaw) + left * Math.sin(yaw);
        final double len = Math.hypot(x, z);
        x /= len;
        z /= len;
        final double t = Math.toRadians(twistDeg);
        final double c = Math.cos(t);
        final double s = Math.sin(t);
        return new double[] {x * c - z * s, x * s + z * c};
    }

    /** Смешать текущую горизонтальную скорость с желаемой: {@code strength} 0 — как было, 1 — только желаемая. */
    public static double[] blend(final double vx, final double vz, final double[] dir, final double speed,
                                 final double strength) {
        return new double[] {vx * (1 - strength) + dir[0] * speed * strength, vz * (1 - strength) + dir[1] * speed * strength};
    }
}
