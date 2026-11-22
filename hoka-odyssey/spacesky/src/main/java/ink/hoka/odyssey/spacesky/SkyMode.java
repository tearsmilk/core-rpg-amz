package ink.hoka.odyssey.spacesky;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Режим неба (config.yml, {@code everywhere}) и что от него зависит: файл spacesky:mode.glsl для шейдеров и набор
 * частей пака из jar модуля.
 *
 * <ul>
 *   <li>everywhere: true — космос во всех мирах с обычным небом: свой туман (fog.glsl), ванильные звёзды и закатный
 *       веер выключены шейдерами (stars.fsh, position_color.vsh), солнце/луна/облака — прозрачные текстуры
 *       (hide/*, по настройкам hide-sun|moon|clouds).</li>
 *   <li>everywhere: false — космос только в мирах типа hoka:space: шейдер неба узнаёт их по МЕТКЕ в цвете тумана/неба,
 *       в остальных мирах — ванильное небо. Туман, облака, звёзды, веер — ванильные (их выключают атрибуты
 *       типа мира); текстуры не трогаются (они общие для всех миров) — солнце и луну прячет шейдер celestial
 *       (position_tex.vsh) только при МЕТКЕ.</li>
 * </ul>
 */
final class SkyMode {
    static final String PATH = "assets/spacesky/shaders/include/mode.glsl";

    /** Части пака внутри jar. */
    static final String ALWAYS = "pack";
    static final String EVERYWHERE = "everywhere";
    static final String MARKED = "marked";
    static final String HIDE_SUN = "hide/sun";
    static final String HIDE_MOON = "hide/moon";
    static final String HIDE_CLOUDS = "hide/clouds";

    private SkyMode() {
    }

    /** Части пака по настройкам. hide-* в режиме everywhere: false действуют через шейдер, а не текстуры. */
    static List<String> parts(boolean everywhere, boolean sun, boolean moon, boolean clouds) {
        List<String> out = new ArrayList<>();
        out.add(ALWAYS);
        if (!everywhere) {
            out.add(MARKED);
            return out;
        }
        out.add(EVERYWHERE);
        if (sun) {
            out.add(HIDE_SUN);
        }
        if (moon) {
            out.add(HIDE_MOON);
        }
        if (clouds) {
            out.add(HIDE_CLOUDS);
        }
        return out;
    }

    private static String f(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static String vec(int c) {
        return "(vec3(" + f((c >> 16) & 255) + ", " + f((c >> 8) & 255) + ", " + f(c & 255) + ") / 255.0)";
    }

    /** Текст spacesky:mode.glsl. */
    static String glsl(boolean everywhere, boolean hideSun, boolean hideMoon) {
        int m = SpaceDimension.MARK;
        int w = SpaceDimension.WATER_MARK;
        return "// spacesky: режим неба — файл пишет модуль spacesky из config.yml, руками не менять.\n"
            + "#ifndef SPACESKY_MODE_GLSL\n"
            + "#define SPACESKY_MODE_GLSL\n"
            + "// 1 — космос во всех мирах с обычным небом; 0 — только в мирах типа hoka:space (по МЕТКЕ)\n"
            + "#define SPACESKY_EVERYWHERE " + (everywhere ? 1 : 0) + "\n"
            + "// режим 0: прятать солнце / луну в мире hoka:space (шейдер position_tex)\n"
            + "#define SPACESKY_HIDE_SUN " + (hideSun ? 1 : 0) + "\n"
            + "#define SPACESKY_HIDE_MOON " + (hideMoon ? 1 : 0) + "\n"
            + "// МЕТКА — цвет неба и тумана мира hoka:space (" + Zones.hex(m) + "), в ванили такого нет\n"
            + "#define SPACESKY_MARK " + vec(m) + "\n"
            + "// МЕТКА ВОДЫ — цвет тумана под водой в мире hoka:space (" + Zones.hex(w) + ")\n"
            + "#define SPACESKY_WATER_MARK " + vec(w) + "\n"
            + "// цвет космоса у горизонта — общий для неба (sky.fsh) и тумана дальности (fog.glsl режима 1)\n"
            + "#define SPACESKY_BASE vec3(0.006, 0.007, 0.018)\n"
            + "\n"
            + "// Цвет c — метка mark? Ровно (допуск 0,6/255: клиент передаёт цвет как байты /255) или метка, умноженная на\n"
            + "// число: так клиент затемняет туман у дна мира (пустота) и осветляет ночным зрением.\n"
            + "bool spacesky_is(vec3 c, vec3 mark) {\n"
            + "    if (all(lessThan(abs(c - mark), vec3(0.6 / 255.0)))) return true;\n"
            + "    float l = length(c);\n"
            + "    return l > 1e-5 && distance(c / l, normalize(mark)) < 0.004;\n"
            + "}\n"
            + "\n"
            + "// Цвет неба или тумана — МЕТКА мира hoka:space?\n"
            + "bool spacesky_marked(vec3 c) {\n"
            + "    return spacesky_is(c, SPACESKY_MARK);\n"
            + "}\n"
            + "\n"
            + "// Цвет тумана (FogColor) — мира hoka:space? МЕТКА или, когда камера под водой, МЕТКА ВОДЫ.\n"
            + "bool spacesky_fog_marked(vec3 c) {\n"
            + "    return spacesky_marked(c) || spacesky_is(c, SPACESKY_WATER_MARK);\n"
            + "}\n"
            + "#endif\n";
    }
}
