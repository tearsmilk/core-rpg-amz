#version 330

// spacesky: космос вместо неба. Всегда (дёшево) — тёмный фон и редкие звёзды. В областях из config.yml (ssZone из
// sky.vsh) — Млечный путь, туманности, густые звёзды; вне областей эта работа не делается вовсе (ветка одна на кадр).
// Цвет неба от времени суток и биома (ColorModulator) не используется — космос одинаковый днём и ночью.

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec3 spaceDir;
flat in vec4 ssZone;
flat in vec3 ssNebula;

out vec4 fragColor;

float ss_hash(vec3 p) {
    p = fract(p * 0.3183099 + vec3(0.71, 0.113, 0.419));
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}

float ss_noise(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(ss_hash(i), ss_hash(i + vec3(1, 0, 0)), f.x),
                   mix(ss_hash(i + vec3(0, 1, 0)), ss_hash(i + vec3(1, 1, 0)), f.x), f.y),
               mix(mix(ss_hash(i + vec3(0, 0, 1)), ss_hash(i + vec3(1, 0, 1)), f.x),
                   mix(ss_hash(i + vec3(0, 1, 1)), ss_hash(i + vec3(1, 1, 1)), f.x), f.y), f.z);
}

float ss_fbm3(vec3 p) {
    return 0.5 * ss_noise(p) + 0.25 * ss_noise(p * 2.03 + 1.7) + 0.125 * ss_noise(p * 4.1 + 9.2);
}

// Слой звёзд: ячейки 3D-сетки на сфере радиуса scale, в ячейке не больше одной звезды. Плотность проверяется
// первым хэшем — пустые ячейки (большинство) стоят один хэш.
vec3 ss_stars(vec3 d, float scale, float density, float size, float t) {
    vec3 p = d * scale;
    vec3 cell = floor(p);
    float h = ss_hash(cell);
    if (h > density) {
        return vec3(0.0);
    }
    float h2 = ss_hash(cell + 19.19);
    vec3 star = cell + 0.25 + 0.5 * vec3(ss_hash(cell + 31.7), h2, ss_hash(cell + 5.53));
    float r = max(size, length(fwidth(p)) * 0.6);                    // не меньше пикселя — без мерцания на краю
    float dist = length(p - star);
    float lum = smoothstep(r, 0.0, dist) + 0.35 * smoothstep(r * 3.0, 0.0, dist);
    float u = h / density;                                             // 0..1 внутри «занятых» ячеек
    float twinkle = 0.75 + 0.25 * sin(t * (1.5 + 3.0 * h2) + 6.2832 * u);
    vec3 tint = mix(vec3(0.65, 0.78, 1.0), vec3(1.0, 0.86, 0.68), h2);
    return tint * lum * mix(0.45, 1.6, u * u) * twinkle;
}

vec3 ss_space(vec3 d, float t) {
    // общий план — всегда
    vec3 col = SPACESKY_BASE + vec3(0.006, 0.004, 0.016) * (0.5 + 0.5 * d.y);
    col += ss_stars(d, 160.0, 0.022, 0.06, t) * 0.8;
    col += ss_stars(d, 55.0, 0.02, 0.05, t) * 1.3;

    float seed = ssZone.w;
    // туманность: маска одним шумом; цветной шум — только внутри маски
    if (ssZone.y > 0.0) {
        float mask = smoothstep(0.35, 0.7, ss_noise(d * 1.7 + seed));
        if (mask > 0.0) {
            float n = ss_fbm3(d * 3.2 + seed * 2.0);
            col += mask * n * n * ssNebula * 0.42 * ssZone.y;
        }
    }
    // Млечный путь: полоса вдоль большого круга (наклон — от области); шум — только в полосе
    if (ssZone.x > 0.0) {
        vec3 axis = normalize(vec3(sin(seed), 2.2, cos(seed * 1.3)));
        float band = exp(-pow(dot(d, axis) / 0.16, 2.0));
        if (band > 0.02) {
            float clumps = ss_fbm3(d * 9.0);
            float dust = smoothstep(0.45, 0.75, ss_noise(d * 14.0 + 5.0));
            col += band * (0.10 + 0.18 * clumps) * (1.0 - 0.7 * dust) * vec3(0.78, 0.80, 1.0) * ssZone.x;
            col += ss_stars(d, 420.0, 0.25 * band * ssZone.x, 0.07, t) * 0.45;
        }
    }
    // звёздное скопление: густые мелкие и средние звёзды
    if (ssZone.z > 0.0) {
        col += ss_stars(d, 420.0, 0.06 * ssZone.z, 0.07, t) * 0.5;
        col += ss_stars(d, 160.0, 0.05 * ssZone.z, 0.06, t) * 0.9;
    }
    return col;
}

void main() {
    vec3 d = normalize(spaceDir);
    float t = GameTime * 1200.0;                                       // секунды, цикл — игровые сутки
    fragColor = vec4(ss_space(d, t), 1.0);
}
