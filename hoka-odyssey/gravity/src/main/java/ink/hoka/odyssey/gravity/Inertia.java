package ink.hoka.odyssey.gravity;

import ink.hoka.odyssey.spacephys.Vec3d;

/**
 * Чистая математика «настоящего» вакуума для обычного (vanilla) клиента, без Minestom.
 * <p>
 * Как клиент Minecraft 1.21 двигает игрока в воздухе за тик: прибавляет к скорости ввод WASD (moveRelative,
 * 0,02 блока/тик, с бегом 0,026), сдвигается на эту скорость (move, с коллизиями) и только потом гасит её:
 * горизонталь ×0,91, вертикаль ×0,98 (гравитация у нас 0). Компоненту меньше 0,003 блока/тик он обнуляет до сдвига.
 * <p>
 * Сервер ведёт «настоящую» скорость w (блоки/с, без затухания) и шлёт клиенту абсолютную скорость пакетом
 * EntityVelocity. Чтобы сдвиг клиента за n тиков до следующего пакета был ровно w·n·dt, а не меньше из-за его
 * затухания, скорость делится на средний множитель затухания за эти тики ({@link #compensation}); при пакете
 * каждый тик (n = 1) множитель 1 — клиент затухает уже после сдвига, а следующий пакет снова ставит скорость.
 * Ввод WASD клиента вычитается: в вакууме не от чего отталкиваться, двигает только ранец (тяга сервера).
 */
final class Inertia {
    /** Затухание горизонтальной скорости у клиента за тик (в воздухе). */
    static final double CLIENT_H_DRAG = 0.91;
    /** Затухание вертикальной скорости у клиента за тик. */
    static final double CLIENT_V_DRAG = 0.98;
    /** Клиент обнуляет компоненту скорости меньше этой (блоков/тик). */
    static final double CLIENT_NEGLIGIBLE = 0.003;
    /** Ускорение ввода в воздухе: обычное и с бегом, блоков/тик за тик. */
    static final double AIR_INPUT = 0.02;
    static final double AIR_INPUT_SPRINT = 0.026;
    /** Клиент умножает ввод одной клавиши на 0,98 (LocalPlayer.aiStep). */
    static final double INPUT_SCALE = 0.98;

    private Inertia() {}

    /**
     * Во сколько раз поднять скорость, чтобы за n тиков без новых пакетов клиент сдвинулся ровно на v·n:
     * n / (1 + d + d² + … + d^(n−1)). n = 1 — 1.
     */
    static double compensation(double drag, int n) {
        if (n <= 1) {
            return 1;
        }
        double sum = 0;
        double k = 1;
        for (int i = 0; i < n; i++) {
            sum += k;
            k *= drag;
        }
        return n / sum;
    }

    /**
     * Ввод WASD, который клиент сам прибавит к скорости в воздухе за тик, {x, z} блоков/тик (moveRelative).
     *
     * @param yaw     поворот игрока (градусы Minecraft)
     * @param forward 1 — W, −1 — S
     * @param strafe  1 — A (влево), −1 — D (вправо)
     */
    static double[] airInput(float yaw, int forward, int strafe, boolean sprint) {
        if (forward == 0 && strafe == 0) {
            return new double[] {0, 0};
        }
        double fx = strafe * INPUT_SCALE;
        double fz = forward * INPUT_SCALE;
        double len2 = fx * fx + fz * fz;
        double speed = sprint ? AIR_INPUT_SPRINT : AIR_INPUT;
        double k = len2 > 1 ? speed / Math.sqrt(len2) : speed;   // диагональ клиент нормирует
        fx *= k;
        fz *= k;
        double r = Math.toRadians(yaw);
        double sin = Math.sin(r);
        double cos = Math.cos(r);
        return new double[] {fx * cos - fz * sin, fz * cos + fx * sin};
    }

    /**
     * Скорость для пакета клиенту, блоков/тик: настоящая w (блоки/с) с компенсацией затухания за n тиков
     * до следующего пакета и без ввода WASD, который клиент прибавит сам.
     *
     * @param input {x, z} из {@link #airInput} (null — без ввода)
     */
    static Vec3d toClient(double wx, double wy, double wz, double[] input, int n) {
        double h = compensation(CLIENT_H_DRAG, n);
        double v = compensation(CLIENT_V_DRAG, n);
        double ix = input == null ? 0 : input[0];
        double iz = input == null ? 0 : input[1];
        return new Vec3d(wx / Settings.TPS * h - ix, wy / Settings.TPS * v, wz / Settings.TPS * h - iz);
    }

    /**
     * Куда толкнуться от блока: взгляд, если он смотрит от поверхности (угол с нормалью меньше ~78°), иначе по
     * нормали. Обе — единичные; нормаль ZERO — толкнуться не от чего (ZERO).
     */
    static Vec3d pushDirection(Vec3d normal, Vec3d look) {
        Vec3d n = normal.normalize();
        if (n.lengthSquared() == 0) {
            return Vec3d.ZERO;
        }
        Vec3d l = look.normalize();
        return l.dot(n) > 0.2 ? l : n;
    }

    /**
     * Проверка скорости игрока, который считает физику сам (мод spaceflight): за тик ему «начисляется» путь
     * по наибольшей скорости, которую разрешает физика, а каждый пакет движения тратит его. Пакеты, пришедшие
     * пачкой после лага, проходят — запас копится до {@link #BURST} тиков.
     */
    static final class MoveGuard {
        /** Сколько тиков пути копить (лаг сети). */
        static final int BURST = 20;
        /** Запас скорости на неточность и толчки, блоки/с. */
        static final double SLACK = 3.0;

        /** Наибольшая скорость, которую ещё допускаем, блоки/с. */
        double speed;
        /** Накопленный допустимый путь, блоки. */
        double credit;

        /**
         * Тик сервера.
         *
         * @param accel наибольшее ускорение сейчас (поле + ранец), блоки/с²
         * @param push  скорость толчка от блока, блоки/с
         */
        void tick(double accel, double push) {
            double allowed = speed + SLACK + push;
            credit = Math.min(credit + allowed / Settings.TPS, allowed * BURST / Settings.TPS + 1);
            speed += accel / Settings.TPS;
        }

        /**
         * Пакет движения сдвинул игрока на dist блоков. true — допустимо; false — быстрее, чем позволяет физика
         * (вернуть назад).
         */
        boolean accept(double dist) {
            if (dist <= credit) {
                credit -= dist;
                return true;
            }
            return false;
        }

        /** Замеренная скорость (блоки/с): допустимая подтягивается к ней (и вниз — медленно, чтобы не ловить лаги). */
        void measured(double v) {
            speed = v > speed ? v : speed * 0.98 + v * 0.02;
        }

        void reset(double v) {
            speed = v;
            credit = 0;
        }
    }
}
