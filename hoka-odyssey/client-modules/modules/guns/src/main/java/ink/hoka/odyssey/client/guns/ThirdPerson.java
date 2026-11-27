package ink.hoka.odyssey.client.guns;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Игрок с оружием от третьего лица (свой в F5 и чужие): поза тела по анимациям TACZ «rifle_default» (у пистолета —
 * «pistol_default»; файл у каждого оружия свой, с сервера) (формат
 * player_animation, {@link PlayerAnim}), как их проигрывает TACZ через PlayerAnimator:
 * <ul>
 *   <li>ноги — hold_lower / walk_lower / run_lower (присев — ноги игры);</li>
 *   <li>верх — hold_upper / walk_upper / run_upper / crouch_walk_upper, в прицеле aim_upper; смена — плавно, 0,4 с;</li>
 *   <li>поверх — разовые: reload_upper (растянута на время перезарядки), normal_fire_upper / aim_fire_upper;</li>
 *   <li>руки и голова наклоняются за взглядом (наклон прибавляется к xRot), а всё тело поворачивается к курсу
 *       головы — винтовка смотрит туда, куда смотрит игрок.</li>
 * </ul>
 * Части ставит крючок ядра onModelParts (после HumanoidModel.setupAnim — и для брони); без него поза игры.
 * Поза считается раз на кадр на сущность, остальные модели (броня) берут ту же.
 */
final class ThirdPerson {
    static final float FADE = 0.4f;
    private static final int HEAD = 0, TORSO = 1, RARM = 2, LARM = 3, RLEG = 4, LLEG = 5;
    private static final float DEG = (float) (Math.PI / 180);

    /** Что делает стрелок (свой — от модуля каждый кадр, чужие — от сервера). */
    static final class Signals {
        boolean aim;
        long reloadStartNs = -1;
        float reloadLen;
        long shotNs;
        long seenNs = System.nanoTime();
    }

    private static final class Layer {
        String cur;
        long start;
        long fadeStart;
        final float[][] snap = new float[6][6];
        final float[][] last = new float[6][6];
        boolean hasLast;
    }

    private static final class Ent {
        boolean holding;
        long holdNs;
        final Layer lower = new Layer();
        final Layer upper = new Layer();
        long fireStart;
        long fireSeen;
        boolean fireAim;
        final float[][] target = new float[6][6];
        float targetYaw;
        boolean hasTarget;
        float cacheAge = Float.NaN;
        final float[][] out = new float[6][6];
        float outYaw;
        boolean active;
        long seenNs;
    }

    private final Map<Integer, Signals> signals = new HashMap<>();
    private final Map<Integer, Ent> ents = new HashMap<>();
    /** Анимации тела по id оружия (файл из настроек сервера). */
    private final Map<String, PlayerAnim> byGun = new HashMap<>();
    /** Анимации оружия, позу которого сейчас считаем. */
    private PlayerAnim anim;

    void setAnim(String gun, PlayerAnim a) {
        if (a == null) byGun.remove(gun);
        else byGun.put(gun, a);
    }

    void clearAnims() {
        byGun.clear();
    }

    Signals signals(int id) {
        Signals s = signals.computeIfAbsent(id, k -> new Signals());
        s.seenNs = System.nanoTime();
        return s;
    }

    void forget(int id) {
        signals.remove(id);
    }

    /** Раз в тик: забыть тех, кого давно не рисовали. */
    void cleanup() {
        long now = System.nanoTime();
        for (Iterator<Ent> it = ents.values().iterator(); it.hasNext(); ) if (now - it.next().seenNs > 10_000_000_000L) it.remove();
        // сигналы чужих: сервер повторяет POSE раз в 2 с, пока оружие в руке; ушёл из мира / вышел — забыть
        for (Iterator<Signals> it = signals.values().iterator(); it.hasNext(); ) if (now - it.next().seenNs > 10_000_000_000L) it.remove();
    }

    // ---------------------------------------------------------------- крючок

    void apply(Entity e, HumanoidModel<?> model, HumanoidRenderState s) {
        if (byGun.isEmpty() || !(e instanceof Player p) || model == null || s == null) return;
        Ent en = ents.computeIfAbsent(p.getId(), k -> new Ent());
        long now = System.nanoTime();
        en.seenNs = now;
        if (s.ageInTicks != en.cacheAge) {                       // первая модель кадра (тело) — посчитать
            en.cacheAge = s.ageInTicks;
            en.active = compute(p, en, model, s, now);
        }
        if (en.active) put(model, en.out, en.outYaw);
    }

    private boolean compute(Player p, Ent en, HumanoidModel<?> model, HumanoidRenderState s, long now) {
        String gun = GunsClientModule.gunId(p.getMainHandItem());
        PlayerAnim a = gun == null ? null : byGun.get(gun);
        boolean holding = a != null;
        if (a != null) anim = a;
        if (holding != en.holding) {
            en.holding = holding;
            en.holdNs = now;
            if (holding) {
                en.lower.cur = en.upper.cur = null;
                en.lower.hasLast = en.upper.hasLast = false;
                en.fireStart = 0;
            }
        }
        float f = ease((now - en.holdNs) / 1e9f / FADE);
        float w = holding ? f : 1 - f;
        if (w <= 0 || !holding && !en.hasTarget) return false;
        float[][] v = new float[6][6];
        read(model, v);
        if (holding) {
            target(p, en, s, v, now);
            en.hasTarget = true;
        }
        for (int i = 0; i < 6; i++) for (int k = 0; k < 6; k++) en.out[i][k] = Mth.lerp(w, v[i][k], en.target[i][k]);
        en.outYaw = en.targetYaw * w;
        return true;
    }

    /** Поза с оружием (без смешивания с позой игры): в en.target, поворот тела — en.targetYaw (рад). */
    private void target(Player p, Ent en, HumanoidRenderState s, float[][] v, long now) {
        Signals sig = signals.get(p.getId());
        boolean aim = sig != null && sig.aim;
        boolean crouch = s.isCrouching;
        boolean sprint = p.isSprinting();
        boolean moving = s.walkAnimationSpeed > 0.05f;
        float[][] t = en.target;
        copy(v, t);

        String lower = crouch ? null : sprint ? "run_lower" : moving ? "walk_lower" : "hold_lower";
        layer(en.lower, lower, now, v, t, RLEG, LLEG);
        String upper = aim ? "aim_upper" : crouch && (moving || sprint) ? "crouch_walk_upper"
                : sprint ? "run_upper" : moving ? "walk_upper" : "hold_upper";
        layer(en.upper, upper, now, v, t, HEAD, LARM);

        // разовые: перезарядка (растянута на её время) или выстрел
        float[][] once = null;
        float ow = 0;
        if (sig != null && sig.reloadStartNs >= 0 && sig.reloadLen > 0) {
            float el = (now - sig.reloadStartNs) / 1e9f;
            PlayerAnim.Clip c = anim.clip("reload_upper");
            if (c != null && el >= 0 && el < sig.reloadLen) {
                once = copyOf(t);
                sample(c, el / sig.reloadLen * c.length(), v, once, HEAD, LARM);
                ow = Math.min(ease(el / 0.25f), ease((sig.reloadLen - el) / 0.2f));
            }
        }
        if (once == null && sig != null) {
            PlayerAnim.Clip c0 = anim.clip("normal_fire_upper");
            float len = c0 == null ? 0.35f : c0.length();
            if ((en.fireStart == 0 || (now - en.fireStart) / 1e9f > len) && sig.shotNs > en.fireSeen) {
                en.fireStart = now;
                en.fireAim = aim;
            }
            en.fireSeen = Math.max(en.fireSeen, sig.shotNs);
            float el = en.fireStart == 0 ? -1 : (now - en.fireStart) / 1e9f;
            PlayerAnim.Clip c = anim.clip(en.fireAim ? "aim_fire_upper" : "normal_fire_upper");
            if (c != null && el >= 0 && el < c.length()) {
                once = copyOf(t);
                sample(c, el, v, once, HEAD, LARM);
                ow = Math.min(ease(el / 0.05f), ease((c.length() - el) / 0.05f));
            }
        }
        if (once != null) {
            for (int i = HEAD; i <= LARM; i++) for (int k = 0; k < 6; k++) t[i][k] = Mth.lerp(ow, t[i][k], once[i][k]);
        }

        // взгляд: наклон — голове и рукам, курс головы — всему телу (как TACZ поверх PlayerAnimator)
        float pitch = s.xRot * DEG;
        t[HEAD][0] += pitch;
        t[RARM][0] += pitch;
        t[LARM][0] += pitch;
        en.targetYaw = Mth.clamp(Mth.wrapDegrees(s.yRot), -85f, 85f) * DEG;
    }

    /** Клип текущих анимаций; у пистолета (pistol_default) нет crouch_walk_upper — тогда walk_upper. */
    private PlayerAnim.Clip clip(String name) {
        PlayerAnim.Clip c = anim.clip(name);
        if (c == null && "crouch_walk_upper".equals(name)) c = anim.clip("walk_upper");
        return c;
    }

    /** Слой петлёй со сменой клипа через плавный переход от последней своей позы. */
    private void layer(Layer l, String name, long now, float[][] v, float[][] t, int from, int to) {
        if (!java.util.Objects.equals(name, l.cur)) {
            if (l.hasLast) copy(l.last, l.snap, from, to);
            else copy(v, l.snap, from, to);
            l.cur = name;
            l.start = now;
            l.fadeStart = now;
        }
        PlayerAnim.Clip c = name == null ? null : clip(name);
        if (c != null) sample(c, c.local((now - l.start) / 1e9f), v, t, from, to);
        float f = ease((now - l.fadeStart) / 1e9f / FADE);
        if (f < 1) for (int i = from; i <= to; i++) for (int k = 0; k < 6; k++) t[i][k] = Mth.lerp(f, l.snap[i][k], t[i][k]);
        copy(t, l.last, from, to);
        l.hasLast = true;
    }

    /**
     * Клип в момент time в out (кости from..to): поворот — градусы в радианы, как есть (тело — поверх позы игры:
     * присед), положение — к месту части в позе игры, y с обратным знаком (правила PlayerAnimator).
     */
    static void sample(PlayerAnim.Clip c, float time, float[][] v, float[][] out, int from, int to) {
        float[] tmp = new float[3];
        for (int i = from; i <= to; i++) {
            PlayerAnim.Track tr = c.tracks()[i];
            if (tr == null) continue;
            if (PlayerAnim.sample(tr.rot(), time, tmp)) {
                float bx = i == TORSO ? v[i][0] : 0, by = i == TORSO ? v[i][1] : 0, bz = i == TORSO ? v[i][2] : 0;
                out[i][0] = bx + tmp[0] * DEG;
                out[i][1] = by + tmp[1] * DEG;
                out[i][2] = bz + tmp[2] * DEG;
            }
            if (PlayerAnim.sample(tr.pos(), time, tmp)) {
                out[i][3] = v[i][3] + tmp[0];
                out[i][4] = v[i][4] - tmp[1];
                out[i][5] = v[i][5] + tmp[2];
            }
        }
    }

    // ---------------------------------------------------------------- части модели

    private static ModelPart[] parts(HumanoidModel<?> m) {
        return new ModelPart[] {m.head, m.body, m.rightArm, m.leftArm, m.rightLeg, m.leftLeg};
    }

    private static void read(HumanoidModel<?> m, float[][] v) {
        ModelPart[] ps = parts(m);
        for (int i = 0; i < 6; i++) {
            ModelPart p = ps[i];
            v[i][0] = p.xRot;
            v[i][1] = p.yRot;
            v[i][2] = p.zRot;
            v[i][3] = p.x;
            v[i][4] = p.y;
            v[i][5] = p.z;
        }
    }

    /** Поставить части; yaw — поворот всего тела вокруг вертикали модели (рад, как yRot головы у игры). */
    static void put(HumanoidModel<?> m, float[][] v, float yaw) {
        ModelPart[] ps = parts(m);
        Quaternionf ry = new Quaternionf().rotationY(yaw);
        Quaternionf q = new Quaternionf();
        Vector3f e = new Vector3f();
        for (int i = 0; i < 6; i++) {
            ModelPart p = ps[i];
            float[] x = v[i];
            if (yaw == 0) {
                p.xRot = x[0];
                p.yRot = x[1];
                p.zRot = x[2];
                p.x = x[3];
                p.y = x[4];
                p.z = x[5];
                continue;
            }
            q.set(ry).mul(new Quaternionf().rotationZYX(x[2], x[1], x[0])).getEulerAnglesZYX(e);
            p.xRot = e.x;
            p.yRot = e.y;
            p.zRot = e.z;
            ry.transform(e.set(x[3], x[4], x[5]));
            p.x = e.x;
            p.y = e.y;
            p.z = e.z;
        }
    }

    static float ease(float x) {
        if (x <= 0) return 0;
        if (x >= 1) return 1;
        return (float) (-(Math.cos(Math.PI * x) - 1) / 2);         // in-out sine, как фейды TACZ
    }

    private static void copy(float[][] a, float[][] b) {
        for (int i = 0; i < 6; i++) System.arraycopy(a[i], 0, b[i], 0, 6);
    }

    private static void copy(float[][] a, float[][] b, int from, int to) {
        for (int i = from; i <= to; i++) System.arraycopy(a[i], 0, b[i], 0, 6);
    }

    private static float[][] copyOf(float[][] a) {
        float[][] b = new float[6][6];
        copy(a, b);
        return b;
    }
}
