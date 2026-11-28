package ink.hoka.odyssey.client.guns;

import java.util.List;
import java.util.Random;

/**
 * Отдача камеры одного выстрела: естественный кубический сплайн через (0, 0) и ключевые точки оружия
 * (время × 1000 + 30 мс, случайное значение в [мин; макс]). Камера каждый кадр сдвигается на изменение сплайна с
 * прошлого кадра; новый выстрел начинает новый сплайн, а уже сделанный сдвиг остаётся — в очереди ствол «ползёт»
 * вверх и плавно возвращается после неё. Чистая математика.
 */
final class RecoilSpline {
    private double[] xs = {0};
    private double[] ys = {0};
    private double[] m = {0};
    private long start = -1;
    private double applied;

    /** Новый выстрел в момент now (мс). */
    void shot(List<Proto.Key> keys, double scale, long now, Random rnd) {
        int n = keys.size() + 1;
        double[] x = new double[n];
        double[] y = new double[n];
        for (int i = 1; i < n; i++) {
            Proto.Key k = keys.get(i - 1);
            x[i] = k.time() * 1000.0 + 30;
            y[i] = (k.min() + (k.max() - k.min()) * rnd.nextDouble()) * scale;
            if (x[i] <= x[i - 1]) x[i] = x[i - 1] + 1;
        }
        xs = x;
        ys = y;
        m = secondDerivatives(x, y);
        start = now;
        applied = 0;
    }

    /** Сколько градусов добавить к камере сейчас (изменение с прошлого вызова). */
    double delta(long now) {
        if (start < 0) return 0;
        double t = now - start;
        double v = value(t);
        double d = v - applied;
        applied = v;
        if (t > xs[xs.length - 1]) start = -1;          // сплайн кончился (последняя точка обычно 0)
        return d;
    }

    double value(double t) {
        int n = xs.length;
        if (n < 2 || t <= 0) return 0;
        if (t >= xs[n - 1]) return ys[n - 1];
        int i = 0;
        while (i < n - 2 && t > xs[i + 1]) i++;
        double h = xs[i + 1] - xs[i];
        double a = (xs[i + 1] - t) / h;
        double b = (t - xs[i]) / h;
        return a * ys[i] + b * ys[i + 1] + ((a * a * a - a) * m[i] + (b * b * b - b) * m[i + 1]) * h * h / 6.0;
    }

    /** Вторые производные естественного кубического сплайна (на концах 0), прогонкой. */
    static double[] secondDerivatives(double[] x, double[] y) {
        int n = x.length;
        double[] m = new double[n];
        if (n < 3) return m;
        double[] u = new double[n];
        for (int i = 1; i < n - 1; i++) {
            double sig = (x[i] - x[i - 1]) / (x[i + 1] - x[i - 1]);
            double p = sig * m[i - 1] + 2;
            m[i] = (sig - 1) / p;
            double dd = (y[i + 1] - y[i]) / (x[i + 1] - x[i]) - (y[i] - y[i - 1]) / (x[i] - x[i - 1]);
            u[i] = (6 * dd / (x[i + 1] - x[i - 1]) - sig * u[i - 1]) / p;
        }
        m[n - 1] = 0;
        for (int k = n - 2; k >= 0; k--) m[k] = m[k] * m[k + 1] + u[k];
        return m;
    }
}
