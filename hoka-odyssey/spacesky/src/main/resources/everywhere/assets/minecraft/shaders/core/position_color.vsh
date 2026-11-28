#version 330

// spacesky (только режим everywhere: true): ванильный position_color.vsh 1.21.11 + убран закатный веер (pipeline sunrise_sunset) — в космосе
// он ложился оранжевой дымкой на нижнюю половину неба. Этим же шейдером рисуются GUI, отладка и молнии, поэтому
// веер узнаётся по своей геометрии (статический буфер клиента): центр — (0, 100, 0) белый непрозрачный, край —
// 16 точек (sin·120, cos·120, −cos·40) с прозрачностью 0; проекция перспективная (у GUI — ортографическая).
// Такие вершины схлопываются в одну точку за экраном — треугольники вырождаются и не рисуются.

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec4 Color;

out vec4 vertexColor;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    vertexColor = Color;

    if (ProjMat[3][3] == 0.0) {
        float len = length(Position);
        bool center = abs(len - 100.0) < 0.01 && all(greaterThan(Color, vec4(0.999)));
        bool ring = len > 119.99 && len < 126.5 && Color.a == 0.0;
        if (center || ring) {
            gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        }
    }
}
