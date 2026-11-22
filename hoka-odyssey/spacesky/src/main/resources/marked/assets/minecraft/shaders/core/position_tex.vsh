#version 330

// spacesky (только режим everywhere: false): ванильный position_tex.vsh 1.21.11 + в мире hoka:space спрятаны
// солнце и луна. Текстуры солнца и луны общие для всех миров — прозрачными их делать нельзя, поэтому квад прячется
// здесь (pipeline celestial), и только когда цвет тумана — МЕТКА мира hoka:space (под водой — МЕТКА ВОДЫ: туман
// тогда цвета воды, а солнце и луна клиент рисует и под водой). Этим же шейдером рисуются GUI и
// прочее, поэтому солнце и луна узнаются по геометрии (клиент, sky renderer): статический квад (±1, 0, ±1),
// модель-вид — перенос (0, 100, 0) и масштаб 30 (солнце) или 20 (луна) по x/z, проекция перспективная (у GUI —
// ортографическая). Такие вершины схлопываются в одну точку за экраном — треугольники вырождаются и не рисуются.

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:fog.glsl>
#moj_import <spacesky:mode.glsl>

in vec3 Position;
in vec2 UV0;

out vec2 texCoord0;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    texCoord0 = UV0;

#if SPACESKY_HIDE_SUN || SPACESKY_HIDE_MOON
    bool quad = abs(Position.y) < 1e-4 && all(lessThan(abs(abs(Position.xz) - 1.0), vec2(1e-4)));
    if (ProjMat[3][3] == 0.0 && quad && spacesky_fog_marked(FogColor.rgb)) {
        float scale = length(ModelViewMat[0].xyz);
        bool sun = abs(scale - 30.0) < 0.5;
        bool moon = abs(scale - 20.0) < 0.5;
#if SPACESKY_HIDE_SUN && SPACESKY_HIDE_MOON
        bool hide = true;
#elif SPACESKY_HIDE_SUN
        bool hide = sun;
#else
        bool hide = moon;
#endif
        if (hide) {
            gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        }
    }
#endif
}
