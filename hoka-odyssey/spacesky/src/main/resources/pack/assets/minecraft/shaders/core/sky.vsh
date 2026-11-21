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
    // Верхний диск неба (y > 0) не доходит до горизонта — в щели виден голубой цвет тумана. Опускаем край диска
    // ниже горизонта (получается конус), мир рисуется поверх, а щель закрыта космосом.
    if (pos.y > 0.0 && length(pos.xz) > 1.0) {
        pos.y = -0.25 * length(pos.xz);
    }
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    spaceDir = pos;
}
