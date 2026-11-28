package ink.hoka.odyssey.client.spaceflight;

import ink.hoka.odyssey.spacephys.Quat;
import ink.hoka.odyssey.spacephys.Vec3d;

import java.util.function.IntPredicate;

/**
 * Тяготение у блоков в вакууме (без Minecraft — проверяется тестами): ближе {@link #REACH} блоков (настройка
 * gravity_reach) к любому блоку невесомости нет — тянет к поверхности с силой обычной земли. Ходишь по любой грани
 * блока — полу, стене, потолку — как по земле.
 * <ul>
 *   <li><b>вход</b> — касание грани стороной ног (нормаль ближе 60° к верху камеры) или приземление на неё
 *       ({@link #entry}); из свободного полёта без клавиш ранца — сразу, как только блок ближе reach (притянуло:
 *       падаешь на ближайшую поверхность);</li>
 *   <li><b>куда тянет</b> ({@link #pull}) — к своей грани (−n), пока под рамкой вдоль −n есть блок в пределах reach
 *       (прыжок, ступенька, уступ — падаешь обратно или ниже, как на земле); опоры вдоль −n нет (сошёл с края
 *       астероида) — к ближайшей точке блоков: обходишь ребро и падаешь на боковую грань. Верх камеры и тела в
 *       воздухе плавно доворачивается против тяжести ({@link #tilt}); коснулся грани, к которой тянет, — встал на неё;</li>
 *   <li><b>грань заперта</b> — поворот головы её не меняет. Сменить — шагнуть в соседнюю (внутренний угол, стена
 *       выше шага), прыгнуть в стену с «вперёд» ({@link #innerCorner} в воздухе — переворот на стену) или
 *       обойти внешнее ребро (см. выше); камера и тело переезжают плавно;</li>
 *   <li><b>движение</b> — как vanilla на земле, только «вниз» — против верха: тяжесть 0,08 блока/тик² и
 *       сопротивление 0,98 вдоль него, разгон шага и трение блока вдоль грани, прыжок 0,42 ({@link #step});</li>
 *   <li><b>взгляд</b> — как на земле, но в системе грани: курс вокруг верха, наклон ±90°, без крена
 *       ({@link #target}); при входе и смене грани камера доворачивается плавно ({@link #shown});</li>
 *   <li><b>выход</b> — Shift+пробел или двойной пробел (второе нажатие в прыжке) отталкивают от грани, притяжение
 *       выключено, пока не улетишь дальше reach от всех блоков (или ~2 с); ушёл дальше reach от всех блоков — свободный
 *       полёт.</li>
 * </ul>
 * Система грани {@code frame}: поворот, переводящий «свою» систему (верх +Y) в мир; курс и наклон взгляда — в ней
 * (как yaw/pitch Minecraft). Стоя — верх = нормаль грани; в воздухе у ребра верх идёт за направлением тяжести. При
 * смене грани система поворачивается кратчайшим поворотом, курс и наклон остаются — шагнул с пола на стену, и взгляд
 * «вперёд по стене», а не в небо.
 */
final class SurfaceGravity {
    /** Тяжесть vanilla, блоки/тик². */
    static final double GRAVITY = 0.08;
    /** Сопротивление vanilla вдоль «вертикали» (нормали грани), доля за тик. */
    static final double DRAG = 0.98;
    /** Множитель трения vanilla: трение блока × 0,91 (в воздухе — 0,91). */
    static final double FRICTION_SCALE = 0.91;
    /** Прыжок vanilla, блоки/тик, и прибавка вперёд при прыжке с бегом. */
    static final double JUMP = 0.42;
    static final double SPRINT_JUMP = 0.2;
    /** Разгон в воздухе vanilla, блоки/тик² (с бегом ×1,3). */
    static final double AIR_ACCEL = 0.02;
    /** Ввод клавиатуры vanilla — 0,98 от единичного. */
    static final double INPUT = 0.98;
    /** Бег — +30 % скорости (как модификатор атрибута vanilla). */
    static final double SPRINT = 1.3;
    /** Пауза между прыжками при зажатом пробеле, тики (vanilla noJumpDelay). */
    static final int JUMP_DELAY = 10;
    /** Второе нажатие пробела в прыжке не позже стольких тиков после первого — оттолкнуться (выход). */
    static final int DOUBLE_TAP = 7;
    /** Опора под ногами: блок ближе этого вдоль −n (стыки блоков и плит, округление позиции), блоки. */
    static final double SEAM = 0.06;
    /** Невесомости нет, пока блок ближе стольких блоков к рамке (по умолчанию; настройка gravity_reach). */
    static final double REACH = 3.0;
    /** «Ногами к грани»: нормаль ближе к верху камеры, чем на 60°. */
    static final double FEET_DOT = 0.5;
    /** Приземление: летишь к грани быстрее этого (блоки/тик) и не вниз головой. */
    static final double LANDING_SPEED = 0.02;
    /** Внутренний угол: доля шага в соседнюю грань не меньше этой. */
    static final double INTO = 0.5;
    /** Камера и тело доворачиваются к системе грани за столько секунд (по умолчанию; настройка surface_turn_time). */
    static final double SNAP_SECONDS = 0.55;

    private int face = -1;
    private Quat frame = Quat.IDENTITY;
    /** Курс и наклон взгляда в системе грани, градусы (как у Minecraft: наклон + — вниз). */
    float yaw;
    float pitch;
    /** Плавный доворот камеры: поворот (мир) от системы грани к тому, что было видно, и начало перехода. */
    private Quat blend;
    private long blendStart;
    /** Длительность доворота, с (настройка). */
    private double turnSeconds = SNAP_SECONDS;
    /** Доворот верха в воздухе ({@link #tilt}): поворот (мир) от системы к тому, что было видно, гаснет за тик. */
    private Quat lag;
    private long lagStart;
    /** Тик, нс: доворот за тик ({@link #tilt}) камера проходит плавно за это время. */
    static final long TICK_NS = 50_000_000L;

    /** Длительность доворота камеры и тела при входе и смене грани, с. */
    void turnSeconds(double s) { turnSeconds = Math.max(0.01, s); }

    boolean active() { return face >= 0; }

    /** Грань (0..5, {@link BodyFrame#NORMALS}) или −1 — режим выключен. */
    int face() { return face; }

    Vec3d normal() { return BodyFrame.normal(face); }

    /** Верх системы (мир): стоя — нормаль грани, в воздухе у ребра — против тяжести (с доворотом). */
    Vec3d up() { return frame.up(); }

    /**
     * Встать на грань: система грани — верх по нормали, «вперёд» — взгляд вдоль грани (смотришь прямо в неё —
     * по верху камеры), наклон — угол взгляда к грани. Направление взгляда не меняется, крен уходит плавно.
     */
    void enter(int f, Quat look, long now) {
        enter(f, BodyFrame.normal(f), look, now);
    }

    /**
     * Войти в воздухе (притянуло из свободного полёта): верх системы — up (против тяжести, не обязательно по оси),
     * своя грань — f (ближайшая к up по оси: на неё и упадёшь, если под ногами окажется блок).
     */
    void enter(int f, Vec3d up, Quat look, long now) {
        if (BodyFrame.normal(f) == null || up == null || up.lengthSquared() < 1e-12) return;
        Vec3d n = up.normalize();
        face = f;
        frame = BodyFrame.onSurface(look, n);
        yaw = 0;
        pitch = (float) Math.toDegrees(Math.asin(FreeLook.clamp(-look.forward().dot(n), -1, 1)));
        lag = null;
        startBlend(look, now);
    }

    /** Перейти на соседнюю грань: система поворачивается кратчайшим поворотом n → n', курс и наклон те же. */
    void transfer(int to, Quat shown, long now) {
        Vec3d n1 = BodyFrame.normal(to);
        if (face < 0 || n1 == null) return;
        Quat r = between(up(), n1);                                // в воздухе верх мог уйти от своей грани
        if (to == face && FreeLook.angle(r, Quat.IDENTITY) < 1e-9) return;
        frame = r.mul(frame).normalize();
        face = to;
        lag = null;
        startBlend(shown, now);
    }

    /**
     * Довернуть верх системы к up (в воздухе — против тяжести; стоя — к нормали грани), не больше чем на maxRad за
     * вызов (раз в тик). Курс и наклон взгляда в системе те же — взгляд поворачивается вместе с ней. Камера и тело
     * проходят этот поворот плавно за тик ({@link #TICK_NS}), без рывков 20 раз в секунду.
     *
     * @return угол, на который ещё осталось довернуть, рад
     */
    double tilt(Vec3d up, double maxRad, long now) {
        if (face < 0 || up == null || up.lengthSquared() < 1e-12) return 0;
        Vec3d u0 = up(), u1 = up.normalize();
        double angle = Math.acos(FreeLook.clamp(u0.dot(u1), -1, 1));
        if (angle < 1e-6) return 0;
        Quat r = between(u0, u1);
        if (angle > maxRad) r = FreeLook.slerp(Quat.IDENTITY, r, maxRad / angle);
        Quat seen = shown(now);
        frame = r.mul(frame).normalize();
        // то, что видно, не скачет: остаток поворота (мир) от новой системы (с доворотом грани) к прежней картинке
        Quat turn = turning(now);
        Quat t = turn == null ? target() : turn.mul(target()).normalize();
        lag = seen.mul(t.conjugate()).normalize();
        lagStart = now;
        return Math.max(0, angle - maxRad);
    }

    /** Повернуть вектор (мир) тем же поворотом, что и систему при переходе с грани from на to. */
    static Vec3d carry(int from, int to, Vec3d v) {
        Vec3d n0 = BodyFrame.normal(from), n1 = BodyFrame.normal(to);
        if (n0 == null || n1 == null) return v;
        return between(n0, n1).rotate(v);
    }

    void leave() {
        face = -1;
        blend = null;
        lag = null;
    }

    /** Мышь: курс вокруг нормали, наклон от грани (±90°). */
    void turn(double dYawDeg, double dPitchDeg) {
        yaw = (float) wrap(yaw + dYawDeg);
        pitch = (float) FreeLook.clamp(pitch + dPitchDeg, -90, 90);
    }

    /** Взгляд без перехода: система грани × курс × наклон, без крена. */
    Quat target() {
        return frame.mul(Quat.fromYawPitchRoll(yaw, pitch, 0)).normalize();
    }

    /** Взгляд, который видно сейчас: после входа и смены грани — плавно от прежнего, мышь — без задержки. */
    Quat shown(long now) {
        return lagged(now, target());
    }

    /**
     * Тело на грани, которое видно сейчас: стоит на грани (верх по нормали, лицом по взгляду) и во время доворота
     * повёрнуто тем же поворотом, что и камера, — тело и камера переезжают на новую грань вместе.
     */
    Quat body(long now) {
        return lagged(now, bodyTarget());
    }

    /** Тело без перехода: верх — верх системы, лицом по взгляду (его и шлём серверу — так видят другие). */
    Quat bodyTarget() {
        return BodyFrame.onSurface(target(), up());
    }

    /** Поворот q (мир) с остатками доворотов: смены грани (плавно) и доворота верха в воздухе (за тик). */
    private Quat lagged(long now, Quat q) {
        Quat r = turning(now);
        Quat out = r == null ? q : r.mul(q).normalize();
        if (lag != null) {
            double s = (now - lagStart) / (double) TICK_NS;
            if (s >= 1 || s < 0) lag = null;
            else out = FreeLook.slerp(lag, Quat.IDENTITY, s).mul(out).normalize();
        }
        return out;
    }

    /** Остаток доворота (мир): от показанного к целевому, плавно (smootherstep) к нулю; null — доворота нет. */
    private Quat turning(long now) {
        if (blend == null) return null;
        double s = (now - blendStart) / (turnSeconds * 1e9);
        if (s >= 1 || s < 0) {
            blend = null;
            return null;
        }
        return FreeLook.slerp(blend, Quat.IDENTITY, ease(s));
    }

    /** Плавный ход 0..1 (smootherstep): нулевые скорость и ускорение в начале и в конце. */
    static double ease(double s) {
        if (s <= 0) return 0;
        if (s >= 1) return 1;
        return s * s * s * (s * (s * 6 - 15) + 10);
    }

    private void startBlend(Quat shown, long now) {
        blend = shown.mul(target().conjugate()).normalize();
        blendStart = now;
    }

    /** «Вперёд» по грани (курс без наклона), мир. */
    Vec3d forward() {
        return frame.mul(Quat.fromYawPitchRoll(yaw, 0, 0)).forward();
    }

    /** «Вправо» по грани, мир. */
    Vec3d right() {
        return frame.mul(Quat.fromYawPitchRoll(yaw, 0, 0)).right();
    }

    /** Куда шагать по WASD (единичный вдоль грани или ZERO). fwd/side — −1..1 (side + — вправо). */
    Vec3d wish(int fwd, int side) {
        Vec3d w = forward().scale(fwd).add(right().scale(side));
        double l = w.length();
        return l < 1e-9 ? Vec3d.ZERO : w.scale(1 / l);
    }

    /**
     * Скорость на следующий тик (блоки/тик), как у vanilla: после шага — трение вдоль грани (блок × 0,91 на земле,
     * 0,91 в воздухе) и тяжесть с сопротивлением вдоль n; затем разгон по вводу на следующий шаг (на земле —
     * speed·0,216/трение³, в воздухе — 0,02; бег ×1,3). Шаг по обычному блоку — 0,216 блока/тик (4,3 б/с), как у игры.
     *
     * @param v           скорость после шага (с ударами о блоки)
     * @param n           нормаль грани
     * @param grounded    стоит на грани (после шага)
     * @param blockFriction трение блока под ногами (0,6 обычно, лёд 0,98)
     * @param wish        куда шагать (единичный вдоль грани или ZERO)
     * @param moveSpeed   скорость игрока (атрибут, 0,1), без бега
     * @param sprint      бег
     */
    static Vec3d step(Vec3d v, Vec3d n, boolean grounded, double blockFriction, Vec3d wish, double moveSpeed,
                      boolean sprint) {
        double vn = v.dot(n);
        Vec3d vt = v.sub(n.scale(vn));
        double f = (grounded ? blockFriction : 1.0) * FRICTION_SCALE;
        vt = vt.scale(f);
        vn = (vn - GRAVITY) * DRAG;
        double bf = blockFriction;
        double accel = grounded ? moveSpeed * (sprint ? SPRINT : 1) * (0.21600002 / (bf * bf * bf))
                : AIR_ACCEL * (sprint ? SPRINT : 1);
        vt = vt.add(wish.scale(accel * INPUT));
        return vt.add(n.scale(vn));
    }

    /** Прыжок vanilla вдоль n (с бегом — ещё рывок вперёд по взгляду вдоль грани). */
    static Vec3d jump(Vec3d v, Vec3d n, Vec3d facing, boolean sprint) {
        double vn = v.dot(n);
        Vec3d out = v.add(n.scale(Math.max(JUMP, vn) - vn));
        return sprint ? out.add(facing.scale(SPRINT_JUMP)) : out;
    }

    /** Оттолкнуться от грани (выход в свободный полёт): вдоль n со скоростью push (блоки/тик), вдоль грани — как было. */
    static Vec3d pushOff(Vec3d v, Vec3d n, double push) {
        double vn = v.dot(n);
        return v.add(n.scale(Math.max(vn, 0) + push - vn));
    }

    /**
     * Встать ли на грань из свободного полёта: из касаний — грань стороной ног (нормаль ближе 60° к верху камеры;
     * из нескольких — ближайшая к верху), иначе — та, к которой летишь (быстрее {@link #LANDING_SPEED}), если не
     * вниз головой. −1 — нет.
     *
     * @param touching касание грани i (блок со стороны −NORMALS[i])
     * @param up       верх камеры
     * @param v        скорость, с которой летел (до удара), блоки/тик
     */
    static int entry(IntPredicate touching, Vec3d up, Vec3d v) {
        int best = -1;
        double bestDot = FEET_DOT;
        int land = -1;
        double landSpeed = LANDING_SPEED;
        for (int i = 0; i < BodyFrame.NORMALS.length; i++) {
            if (!touching.test(i)) continue;
            Vec3d n = BodyFrame.NORMALS[i];
            double d = n.dot(up);
            if (d > bestDot) {
                best = i;
                bestDot = d;
            }
            double into = -v.dot(n);
            if (d > 0 && into > landSpeed) {
                land = i;
                landSpeed = into;
            }
        }
        return best >= 0 ? best : land;
    }

    /**
     * Внутренний угол: шагаешь в соседнюю (перпендикулярную) грань, и она выше шага (касание у головы) — её номер,
     * иначе −1.
     *
     * @param wall касание грани i на уровне головы (блок со стороны −NORMALS[i] выше шага)
     */
    static int innerCorner(int face, Vec3d wish, IntPredicate wall) {
        Vec3d n = BodyFrame.normal(face);
        double wl = wish.length();
        if (n == null || wl < 1e-9) return -1;
        int best = -1;
        double bestDot = INTO;
        for (int i = 0; i < BodyFrame.NORMALS.length; i++) {
            Vec3d ni = BodyFrame.NORMALS[i];
            if (Math.abs(ni.dot(n)) > 1e-9) continue;
            double d = -ni.dot(wish) / wl;
            if (d >= bestDot && wall.test(i)) {
                best = i;
                bestDot = d;
            }
        }
        return best;
    }

    /**
     * Куда тянет в воздухе: верх (против тяжести, единичный) или null — блоков ближе reach нет, невесомость.
     * Под рамкой вдоль −n своей грани есть блок в пределах reach (support) — к своей грани, как на земле (прыжок,
     * ступенька, уступ); нет — от ближайшей точки блоков к середине рамки: сошёл с края — тянет к ребру, обходишь
     * его и падаешь на боковую грань.
     *
     * @param n       нормаль своей грани
     * @param support под рамкой вдоль −n в пределах reach есть блок
     * @param box     рамка игрока {minX, minY, minZ, maxX, maxY, maxZ}
     * @param blocks  рамки блоков рядом (те же 6 чисел)
     */
    static Vec3d pull(Vec3d n, boolean support, double[] box, Iterable<double[]> blocks, double reach) {
        if (!within(box, blocks, reach)) return null;
        if (support && n != null) return n;
        Vec3d c = new Vec3d((box[0] + box[3]) / 2, (box[1] + box[4]) / 2, (box[2] + box[5]) / 2);
        Vec3d up = nearestUp(c, blocks);
        return up != null ? up : n;
    }

    /** Есть ли блок не дальше reach от рамки (расстояние между рамками). */
    static boolean within(double[] box, Iterable<double[]> blocks, double reach) {
        double r2 = reach * reach;
        for (double[] b : blocks) {
            if (gap2(box, b) <= r2) return true;
        }
        return false;
    }

    /** Квадрат расстояния между рамками (0 — касаются или пересекаются). */
    static double gap2(double[] a, double[] b) {
        double s = 0;
        for (int k = 0; k < 3; k++) {
            double d = Math.max(0, Math.max(b[k] - a[k + 3], a[k] - b[k + 3]));
            s += d * d;
        }
        return s;
    }

    /** От ближайшей к точке c точки блоков — к c (единичный); null — блоков нет или c внутри блока. */
    static Vec3d nearestUp(Vec3d c, Iterable<double[]> blocks) {
        double best = Double.POSITIVE_INFINITY;
        Vec3d up = null;
        for (double[] b : blocks) {
            double px = FreeLook.clamp(c.x(), b[0], b[3]), py = FreeLook.clamp(c.y(), b[1], b[4]),
                    pz = FreeLook.clamp(c.z(), b[2], b[5]);
            Vec3d d = new Vec3d(c.x() - px, c.y() - py, c.z() - pz);
            double l = d.lengthSquared();
            if (l > 1e-12 && l < best) {
                best = l;
                up = d;
            }
        }
        return up == null ? null : up.normalize();
    }

    /** Грань (0..5) по оси, ближайшая к направлению up. */
    static int faceOf(Vec3d up) {
        int best = 2;
        double bd = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < BodyFrame.NORMALS.length; i++) {
            double d = BodyFrame.NORMALS[i].dot(up);
            if (d > bd) {
                bd = d;
                best = i;
            }
        }
        return best;
    }

    /** Наибольший доворот верха в воздухе за тик, рад: 90° — за время доворота камеры (turnSeconds). */
    static double tiltPerTick(double turnSeconds) {
        return Math.PI / 2 / Math.max(1, turnSeconds * 20);
    }

    /** Координата ног вдоль n (сторона рамки к −n), блоки: рамка [lo, hi] по осям X, Y, Z. */
    static double feet(double[] lo, double[] hi, Vec3d n) {
        int i = BodyFrame.normalIndex(n);
        if (i < 0) return 0;
        int axis = i / 2;
        double along = n.x() + n.y() + n.z();
        return along > 0 ? lo[axis] : -hi[axis];
    }

    /** Кратчайший поворот единичного a в единичный b (противоположные — 180° вокруг любой перпендикулярной оси). */
    static Quat between(Vec3d a, Vec3d b) {
        double c = FreeLook.clamp(a.dot(b), -1, 1);
        Vec3d axis = a.cross(b);
        double len = axis.length();
        if (len < 1e-9) {
            if (c > 0) return Quat.IDENTITY;
            Vec3d any = Math.abs(a.x()) < 0.9 ? new Vec3d(1, 0, 0) : new Vec3d(0, 1, 0);
            return Quat.axisAngle(a.cross(any).normalize(), Math.PI);
        }
        return Quat.axisAngle(axis.scale(1 / len), Math.acos(c));
    }

    static double wrap(double deg) {
        double d = deg % 360;
        if (d >= 180) d -= 360;
        if (d < -180) d += 360;
        return d;
    }
}
