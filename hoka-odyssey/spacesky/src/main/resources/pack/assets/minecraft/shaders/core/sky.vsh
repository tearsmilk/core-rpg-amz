#version 330

// spacesky: ванильный sky.vsh 1.21.11 + космос.
// 1) Направление взгляда (spaceDir): диск неба рисуется вокруг камеры без её смещения (поворот — в ModelViewMat),
//    поэтому Position — направление в мировых осях.
// 2) Верхний диск (веер на высоте 16, край — радиус 512) кончается над горизонтом, а нижний «тёмный» диск клиент
//    рисует только ниже уровня моря — иначе внизу видна голубая заливка. Край верхнего диска опущен почти до
//    надира (−89°): веер — конус вокруг камеры, космос со всех сторон. Плоскости треугольников камеру не задевают.
// 3) Области (spacesky:zones.glsl, их пишет модуль из config.yml): по позиции камеры — насколько игрок внутри
//    области каждого вида. Считается здесь, на 18 вершинах, а не на каждом пикселе; во фрагментный — flat.

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <spacesky:zones.glsl>

in vec3 Position;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec3 spaceDir;
flat out vec4 ssZone;      // x — Млечный путь, y — туманность, z — звёздное скопление (0..1), w — зерно рисунка
flat out vec3 ssNebula;    // цвет туманности

void main() {
    vec3 pos = Position;
    float rim = length(pos.xz);
    if (pos.y > 0.0 && rim > 1.0) {
        const float A = radians(-89.0);
        pos = vec3(pos.xz / rim * cos(A), sin(A)).xzy * rim;
    }
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    spaceDir = pos;

    vec4 zone = vec4(0.0);
    vec3 neb = vec3(0.0);
    float best = 0.0;
#if SS_ZONES > 0
    vec2 cam = vec2(CameraBlockPos.xz) + CameraOffset.xz;
    for (int i = 0; i < SS_ZONES; i++) {
        vec4 z = SS_ZONE_POS[i];                                       // x, z, радиус, доля края с плавным входом
        vec4 k = SS_ZONE_KIND[i];                                      // вид (0, 1, 2), цвет rgb
        float w = 1.0 - smoothstep(z.z * (1.0 - z.w), z.z, distance(cam, z.xy));
        if (w <= 0.0) continue;
        int kind = int(k.x + 0.5);
        if (kind == 0) zone.x = max(zone.x, w);
        else if (kind == 1) { neb += k.yzw * w; zone.y = max(zone.y, w); }
        else zone.z = max(zone.z, w);
        if (w > best) { best = w; zone.w = float(i) * 7.31 + 1.7; }
    }
#endif
    ssZone = zone;
    ssNebula = zone.y > 0.0 ? neb / max(neb.r + neb.g + neb.b, 1e-4) * 1.6 : vec3(0.0);
}
