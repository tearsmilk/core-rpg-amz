package ink.hoka.odyssey.spacesky;

import java.util.ArrayList;
import java.util.List;

/**
 * Режим неба (config.yml, {@code everywhere}) и что от него зависит: файл spacesky:mode.glsl для шейдеров и набор
 * частей пака из jar модуля. Профили неба (spacesky:profiles.glsl, {@link Profiles}) действуют в обоих режимах.
 *
 * <ul>
 *   <li>everywhere: true — космос во всех мирах с обычным небом: миры без метки — профиль space (как до профилей),
 *       миры профилей — свой профиль. Ванильные звёзды и закатный веер выключены шейдерами (stars.fsh,
 *       position_color.vsh), солнце/луна/облака — прозрачные текстуры (hide/*, по настройкам hide-sun|moon|clouds).</li>
 *   <li>everywhere: false — небо профиля только в мирах профилей (шейдеры узнают их по МЕТКЕ в цвете тумана/неба),
 *       в остальных мирах — ванильное небо. Облака, звёзды, веер — ванильные (их выключают атрибуты типа мира);
 *       текстуры не трогаются (они общие для всех миров).</li>
 * </ul>
 * В обоих режимах в паке fog.glsl (туман миров профилей — цветом профиля, в остальных мирах — как было) и
 * position_tex.vsh (в мирах профилей прячет ванильные солнце и луну: у планет всегда — у них своё солнце в sky.fsh).
 */
final class SkyMode {
    static final String PATH = "assets/spacesky/shaders/include/mode.glsl";

    /** Части пака внутри jar. */
    static final String ALWAYS = "pack";
    static final String EVERYWHERE = "everywhere";
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

    /** Текст spacesky:mode.glsl. */
    static String glsl(boolean everywhere, boolean hideSun, boolean hideMoon) {
        return "// spacesky: режим неба — файл пишет модуль spacesky из config.yml, руками не менять.\n"
            + "#ifndef SPACESKY_MODE_GLSL\n"
            + "#define SPACESKY_MODE_GLSL\n"
            + "// 1 — космос во всех мирах с обычным небом (миры без метки — профиль space); 0 — только в мирах профилей\n"
            + "#define SPACESKY_EVERYWHERE " + (everywhere ? 1 : 0) + "\n"
            + "// прятать солнце / луну в мирах профилей-галактик (шейдер position_tex; у планет — всегда)\n"
            + "#define SPACESKY_HIDE_SUN " + (hideSun ? 1 : 0) + "\n"
            + "#define SPACESKY_HIDE_MOON " + (hideMoon ? 1 : 0) + "\n"
            + "// цвет космоса у горизонта по умолчанию (профиль space)\n"
            + "#define SPACESKY_BASE vec3(0.006, 0.007, 0.018)\n"
            + "#endif\n";
    }
}
