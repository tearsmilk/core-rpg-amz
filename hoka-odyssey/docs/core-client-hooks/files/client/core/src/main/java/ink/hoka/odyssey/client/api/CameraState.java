package ink.hoka.odyssey.client.api;

/**
 * Камера кадра — модуль может поменять любые поля в {@link ClientContext.CameraHook}. Углы — в градусах, как у
 * Minecraft: курс (yaw) — 0 юг, 90 запад; наклон (pitch) + — вниз; крен (roll) + — по часовой (правый борт вниз).
 * Крен — настоящий: видимость (отсечение) считается уже с ним.
 */
public final class CameraState {
    public double x, y, z;
    public float yaw, pitch, roll;
    /** Камера от 3-го лица (F5) — модуль может решить, что делать. */
    public boolean detached;
    /**
     * Полный поворот камеры кватернионом (как {@code Camera.rotation()}: камера смотрит вдоль rotation·(0,0,-1),
     * верх — rotation·(0,1,0)). На входе null. Задан — берётся вместо yaw/pitch/roll (нет вырождения при наклоне
     * ±90°, можно вверх ногами); yaw и pitch стоит заполнить тоже — по ним игра считает звук и пр.
     */
    public org.joml.@org.jspecify.annotations.Nullable Quaternionf rotation;
}
