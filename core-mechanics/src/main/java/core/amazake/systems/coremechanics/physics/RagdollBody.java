package core.amazake.systems.coremechanics.physics;

/** Тела одного рэгдолла (6 коробок на шарнирах). Только поток физики. */
public interface RagdollBody {

    /** Прочитать позы частей после шага: центры (мир) и повороты (x, y, z, w). */
    void readBack(double[][] pos, float[][] rot);

    /** Придержать кинематикой на месте (меш мира вокруг ещё не готов) или отпустить. */
    void hold(boolean on);

    /** Задать скорость части, блоков/с. */
    void setVelocity(int part, double vx, double vy, double vz);

    /** Импульс в часть (масса × скорость). */
    void impulse(int part, double ix, double iy, double iz);

    /** Разбудить все части (под ними перестроился меш мира). */
    void wake();

    /** Убрать тела и шарниры из мира. */
    void destroy();
}
