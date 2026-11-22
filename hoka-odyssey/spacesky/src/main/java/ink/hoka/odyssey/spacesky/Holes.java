package ink.hoka.odyssey.spacesky;

import java.util.List;
import java.util.Locale;

/**
 * Чёрные дыры в небе: шар-тень, фотонное кольцо, аккреционный диск и линза (рисует sky.fsh). Источник — модуль gravity
 * (сервис BlackHoles, см. {@link GravityHoles}); в шейдер попадают файлом spacesky:holes.glsl.
 *
 * <p>Дыра видна в небе профиля своей карты (dimension «hoka:&lt;профиль&gt;» в map.yml карты); карты без профиля
 * (overworld и прочие) — профиль space. Миры без метки при everywhere: true — тоже space (как до профилей: там дыры
 * карт без профиля видны во всех мирах), при everywhere: false — ванильное небо без дыр.
 */
final class Holes {
    /** Не больше стольких дыр в файле: цикл в sky.fsh — на каждом пикселе неба (дальние пропускаются за пару умножений). */
    static final int MAX = 16;
    static final String PATH = "assets/spacesky/shaders/include/holes.glsl";

    /**
     * Дыра в единицах шейдера.
     *
     * @param map    карта gravity, на которой стоит дыра (для /hosky и будущих профилей неба)
     * @param name   имя
     * @param x      центр
     * @param y      центр
     * @param z      центр
     * @param horizon радиус горизонта, блоков (больше 0)
     * @param nx     нормаль диска (единичная)
     * @param ny     нормаль диска
     * @param nz     нормаль диска
     * @param outer  внешний радиус диска в горизонтах (0 — без диска)
     * @param spin   скорость вращения (знак — направление)
     * @param bright яркость
     * @param temp   «температура» цвета
     * @param seed   зерно рисунка диска — из имени
     * @param profile номер профиля неба, в котором видна дыра (профиль карты; 0 — space)
     */
    record Hole(String map, String name, double x, double y, double z, double horizon, double nx, double ny, double nz,
                double outer, double spin, double bright, double temp, double seed, int profile) {
        Hole withProfile(int p) {
            return new Hole(map, name, x, y, z, horizon, nx, ny, nz, outer, spin, bright, temp, seed, p);
        }
    }

    private Holes() {
    }

    /**
     * Из полей дыры gravity: наклон диска tilt (градусы от горизонтали) в сторону yaw (градусы по горизонту, 0 — +x,
     * 90 — +z) → нормаль диска. Значения вне пределов подрезаются, как в gravity; null — дыра непригодна (горизонт ≤ 0,
     * координаты не числа).
     */
    static Hole of(String map, String name, double x, double y, double z, double horizon, double tilt, double yaw,
                   double size, double spin, double bright, double temp) {
        if (!(horizon > 0) || !Double.isFinite(horizon) || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            return null;
        }
        double t = Math.toRadians(clamp(tilt, -90, 90, 12));
        double w = Math.toRadians(Double.isFinite(yaw) ? yaw : 0);
        double s = Math.sin(t);
        double outer = Double.isFinite(size) && size > 0 ? clamp(size, 1.5, 12, 6) : 0;
        return new Hole(map, name, x, y, z, horizon, s * Math.cos(w), Math.cos(t), s * Math.sin(w), outer,
            clamp(spin, -5, 5, 1), clamp(bright, 0, 5, 1), clamp(temp, 0.2, 3, 1), seed(name), 0);
    }

    /**
     * Профиль каждой дыры — по типу мира её карты: mapDims — карта → dimension из map.yml, profiles — id → номер.
     * Карта без профиля (или с профилем, которого нет) — 0 (space).
     */
    static List<Hole> withProfiles(List<Hole> holes, java.util.Map<String, String> mapDims,
                                   java.util.Map<String, Integer> profiles) {
        List<Hole> out = new java.util.ArrayList<>(holes.size());
        for (Hole h : holes) {
            String id = Profiles.profileOf(mapDims.get(h.map()));
            Integer i = id == null ? null : profiles.get(id);
            out.add(h.withProfile(i == null ? 0 : i));
        }
        return out;
    }

    /** Зерно рисунка диска из имени дыры: 0…64 с шагом 1/16 (в float точно; разные имена — разный рисунок). */
    static double seed(String name) {
        int h = name == null ? 0 : name.hashCode();
        h ^= h >>> 16;
        h *= 0x45d9f3b;
        h ^= h >>> 16;
        return (h & 1023) / 16.0;
    }

    private static double clamp(double v, double lo, double hi, double def) {
        return Double.isFinite(v) ? Math.max(lo, Math.min(hi, v)) : def;
    }

    private static String f(double v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }

    /** Текст spacesky:holes.glsl (берутся первые {@link #MAX}). */
    static String glsl(List<Hole> all) {
        List<Hole> holes = all.size() > MAX ? all.subList(0, MAX) : all;
        StringBuilder b = new StringBuilder();
        b.append("// spacesky: чёрные дыры — файл пишет модуль spacesky по дырам модуля gravity, руками не менять.\n");
        b.append("// SS_HOLE_POS = (x, y, z, горизонт), SS_HOLE_DISK = (нормаль диска, внешний радиус в горизонтах),\n");
        b.append("// SS_HOLE_LOOK = (вращение, яркость, температура, зерно), SS_HOLE_PROF — номер профиля неба\n");
        b.append("#define SS_HOLES ").append(holes.size()).append('\n');
        if (holes.isEmpty()) {
            return b.toString();
        }
        b.append("const vec4 SS_HOLE_POS[SS_HOLES] = vec4[SS_HOLES](\n");
        for (int i = 0; i < holes.size(); i++) {
            Hole h = holes.get(i);
            b.append("    vec4(").append(f(h.x())).append(", ").append(f(h.y())).append(", ").append(f(h.z())).append(", ")
                .append(f(h.horizon())).append(i + 1 < holes.size() ? "),  // " : ")   // ")
                .append(comment(h.map())).append(':').append(comment(h.name())).append('\n');
        }
        b.append(");\n");
        b.append("const vec4 SS_HOLE_DISK[SS_HOLES] = vec4[SS_HOLES](\n");
        for (int i = 0; i < holes.size(); i++) {
            Hole h = holes.get(i);
            b.append("    vec4(").append(f(h.nx())).append(", ").append(f(h.ny())).append(", ").append(f(h.nz())).append(", ")
                .append(f(h.outer())).append(i + 1 < holes.size() ? ")," : ")").append('\n');
        }
        b.append(");\n");
        b.append("const vec4 SS_HOLE_LOOK[SS_HOLES] = vec4[SS_HOLES](\n");
        for (int i = 0; i < holes.size(); i++) {
            Hole h = holes.get(i);
            b.append("    vec4(").append(f(h.spin())).append(", ").append(f(h.bright())).append(", ").append(f(h.temp()))
                .append(", ").append(f(h.seed())).append(i + 1 < holes.size() ? ")," : ")").append('\n');
        }
        b.append(");\n");
        b.append("const int SS_HOLE_PROF[SS_HOLES] = int[SS_HOLES](");
        for (int i = 0; i < holes.size(); i++) {
            b.append(i > 0 ? ", " : "").append(holes.get(i).profile());
        }
        b.append(");\n");
        return b.toString();
    }

    /** Имя в комментарий GLSL: только безопасные символы (имена gravity — латиница, цифры, _ и -). */
    private static String comment(String s) {
        return s == null ? "" : s.replaceAll("[^A-Za-z0-9_\\-]", "_");
    }
}
