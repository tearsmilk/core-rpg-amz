package ink.hoka.odyssey.client.spaceflight;

import ink.hoka.odyssey.spacephys.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Повёрнутая рамка игрока, стоящего на грани блока (без Minecraft — проверяется тестами).
 * <p>
 * Грани блоков стоят по осям мира, поэтому рамка игрока на стене или потолке тоже ровная по осям: 1.8 вдоль нормали
 * грани n и 0.6×0.6 поперёк. Столкновения с блоками игра (и сервер Minestom) тогда считает сама, как обычно.
 * <p>
 * <b>Соглашение о позиции</b> — то же, что у игры для любой рамки: позиция сущности — середина НИЖНЕЙ грани рамки
 * (x, z — середина, y — низ). Рамка при позиции p: {@code [p.x ± sx/2] × [p.y, p.y + sy] × [p.z ± sz/2]}.
 * Почему так, а не «позиция = ноги на поверхности»:
 * <ul>
 *   <li>на полу и потолке рамка совпадает с обычной (на потолке — та же коробка, только голова внизу) — сдвигать
 *       нечего;</li>
 *   <li>позиция всегда внутри рамки, в воздухе: всё, что игра считает «по позиции» (блок под ногами, выталкивание из
 *       стены LocalPlayer.moveTowardsClosestSpace, свет), работает как у обычной сущности такого размера; при
 *       «ногах на поверхности» позиция лежала бы на грани блока стены/потолка и игра выталкивала бы игрока;</li>
 *   <li>сервер Minestom понимает его без сдвигов: {@code new BoundingBox(sx, sy, sz)} — ровно эта рамка.</li>
 * </ul>
 * Ноги — середина грани рамки, которой она касается поверхности ({@code середина − n·1.8/2}), глаза —
 * {@code середина + n·(1.62 − 0.9)}. Смена рамки сдвигает позицию (на стене — к середине длинной рамки); куда —
 * выбирает {@link #candidates}: новая рамка внутри старой по коротким осям, глаза как можно ближе к прежним.
 */
final class SurfaceBox {
    /** Грань «пол» (+Y): рамка обычная. */
    static final int FLOOR = 2;
    /** Наибольший сдвиг позиции при смене рамки, блоки (сервер даёт на него запас проверки скорости). */
    static final double MAX_SHIFT = 1.5;
    /** Сколько положений пробовать вдоль оси, где новая рамка короче или длиннее старой. */
    static final int SAMPLES = 5;

    private SurfaceBox() {}

    /** Рамка повёрнута (не как у игры) для этой грани: стены и потолок; пол и −1 (свободно) — обычная. */
    static boolean turned(int surface) {
        return surface >= 0 && surface < BodyFrame.NORMALS.length && surface != FLOOR;
    }

    /** Размеры рамки {sx, sy, sz}: h — рост (вдоль нормали грани), w — ширина. */
    static double[] size(int surface, double h, double w) {
        Vec3d n = turned(surface) ? BodyFrame.NORMALS[surface] : null;
        if (n == null || n.y() != 0) return new double[] {w, h, w};
        return n.x() != 0 ? new double[] {h, w, w} : new double[] {w, w, h};
    }

    /** Рамка {minX, minY, minZ, maxX, maxY, maxZ} при позиции (x, y, z). */
    static double[] box(int surface, double x, double y, double z, double h, double w) {
        double[] s = size(surface, h, w);
        return new double[] {x - s[0] / 2, y, z - s[2] / 2, x + s[0] / 2, y + s[1], z + s[2] / 2};
    }

    /** Сдвиг позиция → середина рамки. */
    static Vec3d mid(int surface, double h, double w) {
        return new Vec3d(0, size(surface, h, w)[1] / 2, 0);
    }

    /** Сдвиг позиция → глаза: середина + n·(eye − h/2); рамка обычная — (0, eye, 0). */
    static Vec3d eye(int surface, double h, double w, double eye) {
        Vec3d n = turned(surface) ? BodyFrame.NORMALS[surface] : new Vec3d(0, 1, 0);
        return mid(surface, h, w).add(n.scale(eye - h / 2));
    }

    /** Сдвиг позиция → ноги (середина грани рамки у поверхности). */
    static Vec3d feet(int surface, double h, double w) {
        Vec3d n = turned(surface) ? BodyFrame.NORMALS[surface] : new Vec3d(0, 1, 0);
        return mid(surface, h, w).sub(n.scale(h / 2));
    }

    /**
     * Куда поставить позицию, чтобы рамка стала рамкой грани target: варианты по порядку (сначала те, где глаза ближе
     * всего к прежним), каждый — не дальше {@link #MAX_SHIFT} от прежней позиции. Проверку «не в блоках» делает
     * вызывающий (первый свободный вариант).
     *
     * @param from    нынешняя рамка {minX, minY, minZ, maxX, maxY, maxZ}
     * @param pos     нынешняя позиция
     * @param fromEye нынешние глаза (мир)
     * @param target  грань новой рамки (−1 или {@link #FLOOR} — обычная рамка)
     * @param contact новая рамка касается грани target там же, где старая: её сторона к −n лежит на той же плоскости
     *                (встал на стену, у которой стоял, перешёл на соседнюю грань); false — без опоры (отпустил)
     */
    static List<Vec3d> candidates(double[] from, Vec3d pos, Vec3d fromEye, int target, boolean contact, double h,
                                  double w, double eyeHeight) {
        double[] s = size(target, h, w);
        Vec3d n = target >= 0 && target < BodyFrame.NORMALS.length ? BodyFrame.NORMALS[target] : null;
        Vec3d eyeOff = eye(target, h, w, eyeHeight);
        double[][] mins = new double[3][];
        for (int a = 0; a < 3; a++) {
            double lo = from[a], hi = from[a + 3], len = s[a];
            double nc = n == null ? 0 : comp(n, a);
            if (contact && nc != 0) {
                mins[a] = new double[] {nc > 0 ? lo : hi - len};   // сторона к −n — на плоскости поверхности
                continue;
            }
            double m0 = Math.min(lo, hi - len), m1 = Math.max(lo, hi - len);
            // предпочтительное: глаза новой рамки по этой оси там же, где сейчас
            double want = comp(fromEye, a) - comp(eyeOff, a) - (a == 1 ? 0 : len / 2);
            double pref = Math.max(m0, Math.min(m1, want));
            double[] v = new double[SAMPLES + 1];
            v[0] = pref;
            for (int i = 0; i < SAMPLES; i++) v[i + 1] = SAMPLES == 1 ? m0 : m0 + (m1 - m0) * i / (SAMPLES - 1);
            mins[a] = m1 - m0 < 1e-9 ? new double[] {m0} : v;
        }
        List<Vec3d> out = new ArrayList<>();
        for (double x : mins[0]) {
            for (double y : mins[1]) {
                for (double z : mins[2]) {
                    Vec3d p = new Vec3d(x + s[0] / 2, y, z + s[2] / 2);
                    if (p.distance(pos) > MAX_SHIFT + 1e-9) continue;
                    if (out.stream().anyMatch(o -> o.distance(p) < 1e-6)) continue;
                    out.add(p);
                }
            }
        }
        out.sort(Comparator.comparingDouble(p -> p.add(eyeOff).distance(fromEye)));
        return out;
    }

    static double comp(Vec3d v, int axis) {
        return axis == 0 ? v.x() : axis == 1 ? v.y() : v.z();
    }
}
