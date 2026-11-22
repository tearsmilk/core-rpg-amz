#version 330

// spacesky: проход звёзд (pipeline stars) — частицы параллакса галактик: ближние звёзды и космическая пыль.
// Как «окна» coreshaders: бесконечный фон (sky.fsh) стоит, а перед ним — настоящая геометрия на настоящих расстояниях,
// поэтому при полёте ближние звёзды расходятся от точки, куда летишь, сбоку уходят назад, пыль пролетает мимо.
// - Клиент рисует статический буфер ванильных звёзд: ~780 квадов по 4 вершины подряд (gl_VertexID / 4 — номер
//   частицы, % 4 — угол квада). Ванильные позиции не нужны: каждый квад ставится заново.
// - Профиль — по МЕТКЕ в цвете тумана (Fog и Globals — «общие» uniform клиента, их можно объявить в любом шейдере).
//   Без метки: SPACESKY_EVERYWHERE 1 — ничего (звёзды уже в sky.fsh), 0 — ванильная звезда без изменений.
//   Планета — ничего (её проход звёзд выключен типом мира). Параметры — SS_PROF_9 (profiles.glsl).
// - Частицы лежат в плитке T блоков, повторённой по всему миру; вокруг камеры — ближайшая копия каждой частицы
//   (|смещение| <= T/2 по каждой оси). Перенос на другую копию — ровно на грани, а там частица уже погашена
//   (гаснет с 0,4 T) — ничего не выскакивает. Номер копии (плитки) входит в хэш: в соседних плитках частицы разные
//   (~10% пустые, другая яркость и цвет) — раскладка не повторяется на глаз. Позиция камеры в плитке — целыми из
//   CameraBlockPos (точно и на 30 млн блоков от 0) минус CameraOffset (в 1.21.11 это блок - камера).
// - Поворот: модель-вид прохода звёзд = поворот камеры · rotY(−90°) · rotX(угол звёзд); тип мира галактики задаёт угол 0,
//   поэтому мировое смещение w переводится в координаты буфера как (w.z, w.y, −w.x). Покачивание камеры и тряска от
//   урона — в ProjMat: частицы качаются ровно как мир.
// - Звёздное скопление (область stars): больше видимых ближних звёзд. Чёрные дыры профиля: частица за дырой
//   смещается линзой точечной массы (та же модель, что у фона в sky.fsh) и гаснет в тени и за диском; ближе дыры —
//   рисуется поверх неё.

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <spacesky:mode.glsl>
#moj_import <spacesky:profiles.glsl>
#moj_import <spacesky:zones.glsl>
#moj_import <spacesky:holes.glsl>

in vec3 Position;

out vec2 ssLoc;              // точка внутри квада, -1..1
out vec3 ssCol;              // цвет × яркость частицы
flat out int ssVanilla;      // 1 — ванильная звезда (мир без метки, режим everywhere: false)

#ifndef SS_HOLES
#define SS_HOLES 0
#endif

// Первые SS_NEAR_N квадов — ближние звёзды, остальные (~340) — пыль.
#define SS_NEAR_N 440

uint ss_pcg(uint v) {
    uint s = v * 747796405u + 2891336453u;
    uint w = ((s >> ((s >> 28u) + 4u)) ^ s) * 277803737u;
    return (w >> 22u) ^ w;
}

// 0..1 из хэша
float ss_u(uint v) {
    return float(ss_pcg(v) >> 8) * (1.0 / 16777216.0);
}

// Звёздное скопление: насколько камера внутри области вида stars этого профиля (0..1), как в sky.vsh.
float ss_cluster(int prof) {
    float c = 0.0;
#if SS_ZONES > 0
    vec2 cam = vec2(CameraBlockPos.xz) - CameraOffset.xz;          // CameraOffset = блок - камера (см. sky.fsh)
    for (int i = 0; i < SS_ZONES; i++) {
        int kp = int(SS_ZONE_KIND[i].x + 0.5);
        int zp = kp / 4 - 1;
        if ((zp >= 0 && zp != prof) || kp - (kp / 4) * 4 != 2) continue;
        vec4 z = SS_ZONE_POS[i];
        c = max(c, 1.0 - smoothstep(z.z * (1.0 - z.w), z.z, distance(cam, z.xy)));
    }
#endif
    return c;
}

// Линза чёрных дыр профиля для частицы rel (от камеры, блоки): за дырой — сдвиг от центра, в тени и за диском — гаснет.
void ss_lens(inout vec3 rel, inout float lum, int prof) {
#if SS_HOLES > 0
    for (int h = 0; h < SS_HOLES; h++) {
        if (SS_HOLE_PROF[h] != prof) continue;
        // центр дыры от камеры: целые части — в int (вдали от 0 у float шаг до 2 блоков)
        vec3 O = vec3(SS_HOLE_IPOS[h] - CameraBlockPos) + (SS_HOLE_FRAC[h] + CameraOffset);
        float hr = SS_HOLE_POS[h].w;
        float Dl = length(O);
        if (Dl <= hr) {                                                // камера внутри горизонта — всё чёрное
            lum = 0.0;
            return;
        }
        vec3 oh = O / Dl;
        float Ds = length(rel);
        float along = dot(rel, oh);
        if (along <= Dl) continue;                                     // ближе дыры — поверх неё, без линзы
        vec3 s = rel / Ds;
        float cb = clamp(dot(s, oh), -1.0, 1.0);
        float beta = acos(cb);                                         // угол от центра дыры
        float ts = asin(hr / Dl);                                      // угловой радиус тени
        vec4 dk = SS_HOLE_DISK[h];
        float U = max(10.0, dk.w * 1.6) * ts;                          // радиус влияния (как SSBH_U в sky.fsh)
        if (beta > U) continue;
        // точечная масса: α = 0,77·h/b; образ θ = (β + √(β² + 4θE²)) / 2, θE² = 0,77·h·Dls / (Dl·Ds); издалека
        // ослаблено как у фона (min(1, 8·ts)) и плавно сходит на нет к краю влияния
        float tE2 = 0.77 * hr * (along - Dl) / (Dl * Ds) * min(1.0, 8.0 * ts);
        float th = 0.5 * (beta + sqrt(beta * beta + 4.0 * tE2));
        float q = th / U;
        float W = 1.0 - min(q * q, 1.0);
        th = beta + (th - beta) * W * W;
        lum *= smoothstep(ts, ts * 1.15, th);                          // образ в тени — не виден
        vec3 e = s - oh * cb;
        float el = length(e);
        e = el > 1e-6 ? e / el : normalize(cross(oh, abs(oh.y) < 0.9 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0)));
        vec3 ns = oh * cos(th) + e * sin(th);
        rel = ns * Ds;
        // диск (плоскость через центр, нормаль dk.xyz): луч к образу проходит сквозь диск перед частицей — гаснет
        float dn = dot(ns, dk.xyz);
        if (dk.w > 0.0 && abs(dn) > 1e-4) {
            float t = dot(O, dk.xyz) / dn;
            if (t > 0.0 && t < Ds) {
                float R = length(ns * t - O) / hr;
                lum *= 1.0 - 0.9 * smoothstep(dk.w, dk.w * 0.85, R) * step(1.0, R);
            }
        }
    }
#endif
}

void main() {
    ssLoc = vec2(0.0);
    ssCol = vec3(0.0);
    ssVanilla = 0;
    gl_Position = vec4(2.0, 2.0, 2.0, 1.0);                            // схлопнуть: вне экрана, квад вырожден
    int prof = spacesky_code_profile(spacesky_code(FogColor.rgb));
    if (prof < 0) {
#if !SPACESKY_EVERYWHERE
        ssVanilla = 1;                                                 // ванильный stars.vsh
        gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
#endif
        return;
    }
    if (SS_PROF_0[prof].w > 0.5) return;                               // планета
    vec4 p9 = SS_PROF_9[prof];
    int id = gl_VertexID;
    int i = id >> 2;
    int c = id & 3;
    bool dust = i >= SS_NEAR_N;
    float amount = dust ? p9.y : p9.x;
    int T = int((dust ? p9.w : p9.z) + 0.5);
    if (amount <= 0.0 || T <= 0) return;

    uint ui = uint(i) * 2654435761u + (dust ? 0x68E31DA4u : 0x1B56C4E9u);
    // доля видимых из запаса: у звёзд — больше в скоплении
    float share = dust ? 0.8 * amount : 0.7 * amount * (1.0 + 0.45 * ss_cluster(prof));
    if (ss_u(ui) >= share) return;

    // позиция в плитке: камера — целыми (сдвиг на кратное T делает число положительным: % от отрицательных не определён)
    float Tf = float(T);
    ivec3 a = (CameraBlockPos + ivec3(T * (33554432 / T))) % T;
    vec3 cm = vec3(a) - CameraOffset;
    vec3 P = vec3(ss_u(ui + 1u), ss_u(ui + 2u), ss_u(ui + 3u)) * Tf;
    vec3 sh = P - cm + 0.5 * Tf;
    vec3 k = floor(sh / Tf);
    vec3 rel = sh - k * Tf - 0.5 * Tf;                                 // от камеры, |по оси| <= T/2
    // номер копии — в хэше: меняется ровно при переносе на грани (частица там погашена)
    ivec3 tile = (CameraBlockPos - a) / T - ivec3(k);
    uint th = ss_pcg(uint(tile.x) * 73856093u ^ uint(tile.y) * 19349663u ^ uint(tile.z) * 83492791u ^ ui);
    if (ss_u(th) >= 0.9) return;                                       // ~10% копий пустые
    float h1 = ss_u(th + 1u);
    float h2 = ss_u(th + 2u);

    float dist = length(rel);
    float lum = 1.0 - smoothstep(0.4, 0.5, dist / Tf);                 // к грани плитки — гаснет
    vec3 col;
    float rPhys;
    if (dust) {
        // пыль: тусклая серо-голубая, вблизи не влетаем в спрайт
        lum *= smoothstep(1.0, 3.0, dist) * 0.45 * mix(0.5, 1.0, h1) * sqrt(amount);
        col = mix(vec3(0.55, 0.62, 0.80), vec3(0.80, 0.76, 0.70), h2);
        rPhys = 0.02 + 0.025 * h1;
    } else {
        // звёзды: яркость падает с расстоянием, оттенок — как у звёзд фона (голубой — тёплый) × оттенок профиля
        float twinkle = 0.82 + 0.18 * sin(GameTime * 1200.0 * (1.5 + 3.0 * h2) + 6.2832 * h1);
        lum *= smoothstep(4.0, 14.0, dist) * mix(0.4, 1.8, h1 * h1) * min(1.0, 110.0 / dist) * twinkle * sqrt(amount);
        col = mix(vec3(0.65, 0.78, 1.0), vec3(1.0, 0.86, 0.68), h2) * SS_PROF_2[prof].rgb * SS_PROF_2[prof].w;
        rPhys = 0.05 + 0.07 * h1;                                      // точка: вблизи — несколько пикселей
    }
    ss_lens(rel, lum, prof);
    if (lum <= 0.002) return;

    dist = length(rel);
    vec3 f = rel / dist;
    vec3 r = normalize(cross(f, abs(f.y) < 0.99 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0)));
    vec3 u = cross(r, f);
    // размер: не меньше ~1,6 пикселя (иначе квад мерцает); пыль мельче пикселя тускнеет пропорционально площади
    float pixA = 2.0 / (ProjMat[1][1] * ScreenSize.y);
    float sz = max(rPhys, dist * pixA * 1.6);
    if (dust) {
        float kk = rPhys / sz;
        lum *= kk * kk;
    }
    // углы квада по кругу (против часовой с камеры — не отсекаются)
    vec2 kc = vec2(c == 1 || c == 2 ? 1.0 : -1.0, c >= 2 ? 1.0 : -1.0);
    vec3 w = rel + (r * kc.x + u * kc.y) * sz;
    ssLoc = kc;
    ssCol = col * lum;
    gl_Position = ProjMat * ModelViewMat * vec4(w.z, w.y, -w.x, 1.0);
}
