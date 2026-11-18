package core.amazake.systems.coremechanics.physics;

/** Физ-система одного мира Bukkit. Только поток физики. */
public interface PhysicsWorld {

    /** Есть место ещё под несколько тел (лимит max-bodies). */
    boolean hasRoom();

    /**
     * Статичное меш-тело секции: треугольники в локальных координатах (по 9 float, CCW снаружи), тело стоит в мировой
     * точке (ox, oy, oz). -1 — нет места или меш не собрался.
     */
    int addStaticMesh(float[] triangles, int ox, int oy, int oz);

    /** Убрать статичное тело (секцию). */
    void removeStatic(int id);

    /**
     * Рэгдолл стоя: ступни в (fx, fy, fz), лицом по yaw (градусы Minecraft), начальная скорость в блоках/тик.
     * null — нет места.
     */
    RagdollBody createRagdoll(double fx, double fy, double fz, float yawDeg, double vx, double vy, double vz);

    /** Шаг 1/20 с. Возвращает код ошибки Jolt (0 — ок). */
    int step();

    int bodyCount();

    int activeBodyCount();

    long lastStepNanos();

    /** Уничтожить систему со всеми телами. */
    void destroy();
}
