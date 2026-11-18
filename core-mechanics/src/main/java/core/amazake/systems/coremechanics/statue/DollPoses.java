package core.amazake.systems.coremechanics.statue;

import core.amazake.systems.coremechanics.doll.PoseMath;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Готовые позы и анимации кукол-статуй (перенесено из MagicRuruka DollPoses без изменений чисел). Поза —
 * {@link PoseMath#POSE_LEN} чисел (см. {@link PoseMath#full}): у рук x — вперёд, z — в сторону (правая наружу +,
 * левая наружу −); у тела x — назад (+) / вперёд (−).
 */
public final class DollPoses {

    private DollPoses() {
    }

    /**
     * Анимация: кадры по кругу, тиков на кадр, ходьба (скорость, бл/тик; 0 — на месте), звук на кадре 1 (ключ звука
     * Minecraft или null).
     */
    public record Anim(String title, List<float[]> frames, int ticks, double walk, String sound) {
    }

    static final class B {
        final float[] v = new float[PoseMath.POSE_LEN];

        B() {
            this.v[0] = 0.75f;
        }

        B(final float[] from) {
            System.arraycopy(from, 0, this.v, 0, this.v.length);
        }

        B hip(final double h) {
            this.v[0] = (float) h;
            return this;
        }

        private B set(final int i, final double x, final double y, final double z) {
            this.v[i] = (float) x;
            this.v[i + 1] = (float) y;
            this.v[i + 2] = (float) z;
            return this;
        }

        B body(final double x, final double y, final double z) {
            return this.set(1, x, y, z);
        }

        B head(final double x, final double y, final double z) {
            return this.set(4, x, y, z);
        }

        B ar(final double x, final double y, final double z) {
            return this.set(7, x, y, z);
        }

        B al(final double x, final double y, final double z) {
            return this.set(10, x, y, z);
        }

        B lr(final double x, final double y, final double z) {
            return this.set(13, x, y, z);
        }

        B ll(final double x, final double y, final double z) {
            return this.set(16, x, y, z);
        }

        float[] f() {
            return this.v.clone();
        }
    }

    private static final Map<String, float[]> POSES_M = new LinkedHashMap<>();
    private static final Map<String, String> TITLES_M = new LinkedHashMap<>();
    private static final Map<String, Anim> ANIMS_M = new LinkedHashMap<>();
    /** id позы → углы (не менять массивы: брать {@link #pose}). */
    public static final Map<String, float[]> POSES = Collections.unmodifiableMap(POSES_M);
    public static final Map<String, String> POSE_TITLES = Collections.unmodifiableMap(TITLES_M);
    public static final Map<String, Anim> ANIMS = Collections.unmodifiableMap(ANIMS_M);

    /** Копия позы по id (null — нет такой). */
    public static float[] pose(final String id) {
        final float[] p = POSES_M.get(id);
        return p == null ? null : p.clone();
    }

    private static void pose(final String id, final String title, final B b) {
        POSES_M.put(id, b.f());
        TITLES_M.put(id, title);
    }

    private static void anim(final String id, final String title, final int ticks, final double walk, final String sound, final B... frames) {
        final List<float[]> l = new ArrayList<>();
        for (final B b : frames) {
            l.add(b.f());
        }
        ANIMS_M.put(id, new Anim(title, Collections.unmodifiableList(l), ticks, walk, sound));
    }

    static {
        pose("stand", "Стоит", new B().ar(0, 0, 4).al(0, 0, -4));
        pose("tpose", "T-поза", new B().ar(0, 0, 90).al(0, 0, -90));
        pose("wave", "Машет", new B().ar(0, -15, 155).al(0, 0, -6).head(5, 10, 0));
        pose("victory", "Победа", new B().ar(0, 0, 150).al(0, 0, -150).head(15, 0, 0));
        pose("zombie", "Зомби", new B().ar(90, 0, 0).al(90, 0, 0).head(-5, 0, 0));
        pose("point", "Показывает", new B().ar(90, 0, 0).al(0, 0, -6).head(0, 0, 0));
        pose("dab", "Дэб", new B().ar(105, 0, -70).al(10, 0, -130).head(-35, 30, 0).body(-5, 0, 0));
        pose("sit", "Сидит (на краю)", new B().hip(0.5).lr(90, 0, 3).ll(90, 0, -3).ar(25, 0, 5).al(25, 0, -5));
        pose("sitground", "Сидит на земле", new B().hip(0.13).body(15, 0, 0).lr(105, 0, 8).ll(105, 0, -8).ar(-30, 0, 12).al(-30, 0, -12));
        pose("lie", "Лежит на спине", new B().hip(0.13).body(90, 0, 0).ar(0, 0, 20).al(0, 0, -20).lr(0, 0, 4).ll(0, 0, -4));
        pose("facedown", "Лежит лицом вниз", new B().hip(0.13).body(-90, 0, 0).ar(0, 0, 160).al(0, 0, -160));
        pose("dead", "Труп", new B().hip(0.13).body(90, 0, 0).ar(0, 0, 70).al(0, 0, -70).lr(0, 0, 15).ll(0, 0, -15).head(0, 40, 0));
        pose("kneel", "На коленях", new B().hip(0.55).body(-5, 0, 0).lr(-50, 0, 2).ll(-50, 0, -2).ar(10, 0, 5).al(10, 0, -5));
        pose("salute", "Честь", new B().ar(130, -30, 60).al(0, 0, -4));
        pose("think", "Думает", new B().ar(115, 0, -40).al(40, 0, -25).head(-15, 0, 0));
        pose("facepalm", "Фейспалм", new B().ar(150, 0, -30).head(-35, 0, 0).body(-8, 0, 0));
        pose("crossarms", "Руки скрестил", new B().ar(75, 0, -50).al(75, 0, 50));
        pose("shrug", "Пожимает плечами", new B().ar(20, 0, 35).al(20, 0, -35).head(0, 0, 15));
        pose("bow", "Поклон", new B().body(-60, 0, 0).lr(60, 0, 0).ll(60, 0, 0).ar(60, 0, 5).al(60, 0, -5));
        pose("pushup", "Упор лёжа", new B().hip(0.42).body(-78, 0, 0).ar(90, 0, 10).al(90, 0, -10));
        pose("run", "Бежит", new B().body(-12, 0, 0).ar(-45, 0, 5).al(50, 0, -5).lr(45, 0, 0).ll(-35, 0, 0));
        pose("fly", "Супермен", new B().hip(1.2).body(-90, 0, 0).ar(180, 0, 8).al(180, 0, -8));

        final float[] wave = POSES_M.get("wave");
        anim("wave", "Машет рукой", 7, 0, null, new B(wave), new B(wave).ar(0, 25, 135));
        anim("dance", "Танец", 5, 0, null,
            new B().hip(0.78).body(0, 0, 8).ar(0, 0, 140).al(0, 0, -40).lr(0, 0, 10).ll(-15, 0, -5).head(0, 0, -8),
            new B().hip(0.70).ar(60, 0, 20).al(60, 0, -20).lr(10, 0, 0).ll(10, 0, 0).head(-10, 0, 0),
            new B().hip(0.78).body(0, 0, -8).ar(0, 0, 40).al(0, 0, -140).lr(-15, 0, 5).ll(0, 0, -10).head(0, 0, 8),
            new B().hip(0.70).ar(60, 0, 20).al(60, 0, -20).lr(10, 0, 0).ll(10, 0, 0).head(-10, 0, 0));
        anim("walk", "Гуляет туда-сюда", 8, 0.05, null,
            new B().hip(0.74).ar(-25, 0, 4).al(25, 0, -4).lr(25, 0, 0).ll(-25, 0, 0),
            new B().hip(0.76).ar(0, 0, 4).al(0, 0, -4),
            new B().hip(0.74).ar(25, 0, 4).al(-25, 0, -4).lr(-25, 0, 0).ll(25, 0, 0),
            new B().hip(0.76).ar(0, 0, 4).al(0, 0, -4));
        anim("zombie", "Зомби бредёт", 10, 0.025, "entity.zombie.ambient",
            new B().ar(90, 0, 0).al(90, 0, 0).head(-10, 0, 5).lr(20, 0, 0).ll(-20, 0, 0),
            new B().ar(85, 0, 0).al(95, 0, 0).head(-10, 0, -5).lr(-20, 0, 0).ll(20, 0, 0));
        anim("pushup", "Отжимается", 10, 0, null,
            new B().hip(0.42).body(-78, 0, 0).ar(90, 0, 10).al(90, 0, -10),
            new B().hip(0.24).body(-86, 0, 0).ar(60, 0, 25).al(60, 0, -25));
        anim("jacks", "Прыжки «звёздочка»", 5, 0, null,
            new B().hip(0.88).ar(0, 0, 170).al(0, 0, -170).lr(0, 0, 18).ll(0, 0, -18),
            new B().ar(0, 0, 4).al(0, 0, -4));
        anim("clap", "Хлопает", 4, 0, "block.wooden_trapdoor.close",
            new B().ar(80, 0, 15).al(80, 0, -15),
            new B().ar(80, 0, -8).al(80, 0, 8));
        anim("headbang", "Хэдбэнг", 4, 0, null,
            new B().head(30, 0, 0).body(-10, 0, 0).ar(0, 0, 160).al(0, 0, -20),
            new B().head(-25, 0, 0).body(5, 0, 0).ar(0, 0, 150).al(0, 0, -20));
        anim("floss", "Флосс", 4, 0, null,
            new B().body(0, 0, 8).ar(20, 0, -25).al(-20, 0, -35),
            new B().body(0, 0, -8).ar(-20, 0, 35).al(20, 0, 25));
        anim("fly", "Летит", 15, 0, null,
            new B().hip(1.2).body(-90, 0, 0).ar(180, 0, 8).al(180, 0, -8),
            new B().hip(1.38).body(-84, 0, 0).ar(175, 0, 12).al(175, 0, -12).lr(0, 0, 4).ll(0, 0, -4));
        anim("bow", "Кланяется", 12, 0, null,
            new B().ar(0, 0, 4).al(0, 0, -4),
            new B().body(-60, 0, 0).lr(60, 0, 0).ll(60, 0, 0).ar(60, 0, 5).al(60, 0, -5),
            new B().body(-60, 0, 0).lr(60, 0, 0).ll(60, 0, 0).ar(60, 0, 5).al(60, 0, -5),
            new B().ar(0, 0, 4).al(0, 0, -4));
        anim("sitwave", "Сидит и машет", 7, 0, null,
            new B(POSES_M.get("sit")).ar(0, -15, 155),
            new B(POSES_M.get("sit")).ar(0, 25, 135));
    }
}
