package ink.hoka.odyssey.client.spaceflight;

import ink.hoka.odyssey.spacephys.Vec3d;

import java.util.function.IntPredicate;

/**
 * Грань, к которой прижимают магнитные ботинки (без Minecraft — проверяется тестами).
 * <p>
 * Выбирается один раз — при включении ботинок (Shift) у блока: грань, чья нормаль ближе к верху камеры. Дальше она
 * <b>заперта</b>: поворот головы её не меняет (раньше грань каждый тик выбиралась заново по камере, и, повернув
 * голову, игрок «перескакивал» на другую стену). Меняется она только так:
 * <ul>
 *   <li>шагнул в соседнюю грань (внутренний угол: идёшь по полу в стену — встаёшь на стену);</li>
 *   <li>сошёл с ребра (внешний угол): ботинки ещё {@link #LOST_TICKS} тиков тянут к старой грани, и если за это время
 *       рамка коснулась другой (обогнул ребро) — она;</li>
 *   <li>отпустил Shift, оттолкнулся (прыжок) или потерял опору дольше {@link #LOST_TICKS} — грань снимается.</li>
 * </ul>
 * Новая грань принимается, только если на неё удалось встать (повёрнутая рамка влезла — {@code canSwitch}).
 */
final class SurfaceLock {
    /** Без опоры столько тиков — ботинки отпускают. */
    static final int LOST_TICKS = 10;
    /** Шаг «в грань»: доля направления шага к ней не меньше этой. */
    static final double INTO = 0.5;

    private int surface = -1;
    private int lost;

    int surface() { return surface; }

    /** Грань заперта, но опоры под ней сейчас нет (сошёл с ребра) — ждём соседнюю грань. */
    boolean lost() { return surface >= 0 && lost > 0; }

    void reset() {
        surface = -1;
        lost = 0;
    }

    /**
     * Тик ботинок.
     *
     * @param grip      ботинки включены (Shift и не сразу после прыжка)
     * @param touching  touching(i) — блок вплотную со стороны −NORMALS[i] (на грани i можно стоять)
     * @param pick      грань по верху камеры среди касаний (для первого выбора), −1 — касаний нет
     * @param wish      куда игрок шагает (мир, вдоль поверхности; ZERO — стоит)
     * @param canSwitch перейти на грань i (повёрнутая рамка влезла и поставлена); false — остаться на прежней
     * @return грань, к которой ботинки прижимают сейчас, или −1
     */
    int tick(boolean grip, IntPredicate touching, int pick, Vec3d wish, IntPredicate canSwitch) {
        if (!grip) {
            reset();
            return -1;
        }
        if (surface < 0) {
            if (pick >= 0) {
                surface = pick;                                // первый выбор — по камере; рамку поставит вызывающий
                lost = 0;
            }
            return surface;
        }
        Vec3d n = BodyFrame.NORMALS[surface];
        double wl = wish == null ? 0 : wish.length();
        if (wl > 1e-9) {                                       // внутренний угол: шагнул в соседнюю грань
            int best = -1;
            double bestDot = INTO;
            for (int i = 0; i < BodyFrame.NORMALS.length; i++) {
                Vec3d ni = BodyFrame.NORMALS[i];
                if (Math.abs(ni.dot(n)) > 1e-9) continue;      // только перпендикулярные грани
                double d = -ni.dot(wish) / wl;                 // блок этой грани — по ходу шага
                if (d >= bestDot && touching.test(i)) {
                    best = i;
                    bestDot = d;
                }
            }
            if (best >= 0 && canSwitch.test(best)) {
                surface = best;
                lost = 0;
                return surface;
            }
        }
        if (touching.test(surface)) {
            lost = 0;
            return surface;
        }
        // опоры нет: сошёл с ребра — может быть, рамка уже у другой грани (обогнул ребро)
        int opposite = surface ^ 1;
        int best = -1;
        double bestDot = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < BodyFrame.NORMALS.length; i++) {
            if (i == surface || i == opposite || !touching.test(i)) continue;
            double d = wl > 1e-9 ? BodyFrame.NORMALS[i].dot(wish) / wl : 0;   // грань позади шага — та, с которой сошёл
            if (d > bestDot) {
                best = i;
                bestDot = d;
            }
        }
        if (best >= 0 && canSwitch.test(best)) {
            surface = best;
            lost = 0;
            return surface;
        }
        if (++lost > LOST_TICKS) reset();
        return surface;
    }
}
