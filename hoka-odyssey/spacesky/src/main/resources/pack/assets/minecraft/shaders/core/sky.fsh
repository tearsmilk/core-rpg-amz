#version 330

// spacesky: вместо цвета неба — космос: тёмный фон, туманности, Млечный путь, звёзды трёх размеров с мерцанием.
// Всё считается здесь же (без текстур и лишних проходов): шум — дешёвые хэши, 3 слоя звёзд — по одной ячейке.
// Цвет неба от времени суток и биома (ColorModulator, FogColor) не используется — космос одинаковый днём и ночью.

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec3 spaceDir;

out vec4 fragColor;

float ss_hash(vec3 p) {
    p = fract(p * 0.3183099 + vec3(0.71, 0.113, 0.419));
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}

vec3 ss_hash3(vec3 p) {
    return vec3(ss_hash(p), ss_hash(p + vec3(19.19, 7.31, 3.77)), ss_hash(p + vec3(5.53, 41.17, 13.13)));
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

float ss_fbm(vec3 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 4; i++) {
        v += a * ss_noise(p);
        p = p * 2.03 + vec3(1.7, 9.2, 3.1);
        a *= 0.5;
    }
    return v;
}

// Один слой звёзд: ячейки 3D-сетки на сфере радиуса scale, в ячейке не больше одной звезды.
vec3 ss_stars(vec3 d, float scale, float density, float size, float t) {
    vec3 p = d * scale;
    vec3 cell = floor(p);
    vec3 h = ss_hash3(cell);
    if (h.x > density) {
        return vec3(0.0);
    }
    vec3 star = cell + 0.25 + 0.5 * ss_hash3(cell + 31.7);
    float r = max(size, length(fwidth(p)) * 0.6);                    // не меньше пикселя — без мерцания на краю
    float dist = length(p - star);
    float core = smoothstep(r, 0.0, dist);
    float glow = 0.35 * smoothstep(r * 3.0, 0.0, dist);
    float twinkle = 0.75 + 0.25 * sin(t * (1.5 + 3.0 * h.y) + 6.2832 * h.z);
    vec3 tint = mix(vec3(0.65, 0.78, 1.0), vec3(1.0, 0.86, 0.68), h.y);
    float bright = mix(0.45, 1.6, h.z * h.z);
    return tint * (core + glow) * bright * twinkle;
}

vec3 ss_space(vec3 d, float t) {
    vec3 col = SPACESKY_BASE + vec3(0.006, 0.004, 0.016) * (0.5 + 0.5 * d.y);

    // туманности: два цвета, маска — крупный шум
    float mask = smoothstep(0.42, 0.85, ss_fbm(d * 1.6 + vec3(3.0, 1.0, 7.0)));
    float n1 = ss_fbm(d * 3.2);
    float n2 = ss_fbm(d * 5.0 + vec3(11.0));
    col += mask * (vec3(0.30, 0.08, 0.42) * n1 * n1 + vec3(0.04, 0.22, 0.38) * n2 * n2) * 0.55;

    // Млечный путь: полоса вдоль наклонного большого круга, рваная шумом, с тёмными прожилками пыли
    vec3 axis = normalize(vec3(0.35, 0.82, 0.45));
    float band = exp(-pow(dot(d, axis) / 0.16, 2.0));
    float clumps = ss_fbm(d * 9.0);
    float dust = smoothstep(0.45, 0.75, ss_fbm(d * 14.0 + vec3(5.0)));
    col += band * (0.10 + 0.18 * clumps) * (1.0 - 0.7 * dust) * vec3(0.78, 0.80, 1.0);

    // звёзды: мелкие частые (гуще в Млечном пути), средние, редкие крупные
    col += ss_stars(d, 420.0, 0.035 + 0.2 * band, 0.07, t) * 0.45;
    col += ss_stars(d, 160.0, 0.04, 0.06, t) * 0.9;
    col += ss_stars(d, 55.0, 0.03, 0.05, t) * 1.4;
    return col;
}

void main() {
    vec3 d = normalize(spaceDir);
    float t = GameTime * 1200.0;                                       // секунды, цикл — игровые сутки
    fragColor = vec4(ss_space(d, t), 1.0);
}
