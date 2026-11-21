#version 330

// spacesky: ванильный sky.vsh 1.21.11 + направление взгляда для космоса во фрагментном шейдере.
// Диск неба рисуется вокруг камеры без её смещения (поворот — в ModelViewMat), поэтому Position — направление в
// мировых осях: небо не «едет» за камерой при поворотах.

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec3 spaceDir;

void main() {
    vec3 pos = Position;
    // Верхний диск неба (веер на высоте 16, край — радиус 512) кончается над горизонтом, а нижний «тёмный» диск
    // клиент рисует только когда камера ниже уровня моря — в остальном внизу видна голубая заливка экрана.
    // Опускаем край верхнего диска почти до надира (−89°): веер становится конусом вокруг камеры и закрывает
    // космосом всё, кроме крошечного пятна прямо под ногами. Плоскости треугольников не проходят через камеру.
    float rim = length(pos.xz);
    if (pos.y > 0.0 && rim > 1.0) {
        const float A = radians(-89.0);
        pos = vec3(pos.xz / rim * cos(A), sin(A)).xzy * rim;
    }
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    spaceDir = pos;
}
