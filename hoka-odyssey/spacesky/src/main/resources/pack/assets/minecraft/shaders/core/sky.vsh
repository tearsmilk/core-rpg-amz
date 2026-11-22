#version 330

// spacesky: ванильный sky.vsh 1.21.11 + космос.
// 1) Направление взгляда (spaceDir): диск неба рисуется вокруг камеры без её смещения (поворот — в ModelViewMat),
//    поэтому Position — направление в мировых осях.
// 2) Верхний диск (веер на высоте 16, край — радиус 512) кончается над горизонтом, а нижний «тёмный» диск клиент
//    рисует только ниже уровня горизонта — иначе внизу видна голубая заливка. Центр веера — в зенит (512 вверх),
//    край — почти в надир: веер обнимает камеру со всех сторон. Недотяг до надира считается из ProjMat так, чтобы
//    треугольники не задевали ближнюю плоскость при любом FOV; остаётся точка в надире в 1–2 пикселя
//    (до ~20 при FOV 150° на 32:9) — видна, только если смотреть вниз в пустоту.
// 3) Области (spacesky:zones.glsl, их пишет модуль из config.yml): по позиции камеры — насколько игрок внутри
//    области каждого вида. Считается здесь, на 18 вершинах, а не на каждом пикселе; во фрагментный — flat.
// 4) Профиль неба (spacesky:profiles.glsl): по МЕТКЕ в цвете тумана (FogColor, во всех проходах неба; под водой —
//    МЕТКА ВОДЫ) или неба (ColorModulator верхнего диска) — номер профиля. Режим (spacesky:mode.glsl):
//    SPACESKY_EVERYWHERE 1 — без метки профиль space; 0 — без метки ванильный код без изменений (и без опускания
//    края веера). Номер и параметры профиля (константные массивы, индекс один на кадр) берутся здесь, на вершинах,
//    и идут во фрагментный шейдер flat — там нет ни разбора метки, ни массивов. У планеты здесь же — время суток
//    (из начала тумана, см. profiles.glsl), направление солнца, цвета неба по времени суток, яркость заката.
// 5) Параллакс галактики: туманность области — не на бесконечности, а сфера радиуса nebula-distance вокруг камеры в
//    привязанном к миру шуме (ssNebOff — смещение камеры от центра области; шум непрерывный — ничего не выскакивает).
//    Фон, звёзды фона и Млечный путь остаются на бесконечности. Ближние звёзды и пыль — частицы прохода звёзд
//    (stars.vsh). Нижний «тёмный» диск клиент рисует ПОСЛЕ звёзд (камера ниже горизонта) — он закрасил бы частицы,
//    поэтому у галактики с частицами он схлопывается: верхний веер и так закрывает всё небо, кроме точки в надире.

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <spacesky:mode.glsl>
#moj_import <spacesky:profiles.glsl>
#moj_import <spacesky:zones.glsl>

in vec3 Position;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec3 spaceDir;
// Строки xyz матрицы ProjMat * ModelViewMat (0, 1 и 3): по ним sky.fsh считает направление взгляда для каждого пикселя.
// Геометрия веера проходит в десятых долях блока от глаза, а покачивание камеры и тряска от урона сдвигают проекцию —
// направление, интерполированное по такой близкой геометрии, «плывёт» и трясёт рисунок неба. Направление по пикселю
// (точка на бесконечности) от сдвига не зависит — небо качается ровно как мир, как в ванили.
flat out vec3 ssRow0;
flat out vec3 ssRow1;
flat out vec3 ssRow3;
flat out vec4 ssZone;      // x — Млечный путь, y — туманность, z — звёздное скопление (0..1), w — зерно рисунка
flat out vec3 ssNebula;    // цвет туманности
flat out vec3 ssNebOff;    // смещение шума туманности: (камера − центр лучшей области) × 1,7 / nebula-distance
flat out int ssProf;       // номер профиля (-1 — ванильное небо)
// параметры профиля (см. sky.fsh): галактика — фон, звёзды; планета — небо по времени суток, солнце, закат, луна
flat out vec4 ssA;         // (фон у горизонта | горизонт), w — плотность звёзд
flat out vec4 ssB;         // (фон к зениту | зенит), w — яркость звёзд (у планеты — с учётом ночи; 0 — звёзд нет)
flat out vec4 ssC;         // оттенок звёзд × яркость (у планеты — с учётом ночи), w — вид (0 галактика, 1 планета)
flat out vec4 ssD;         // направление на солнце, w — угловой радиус солнца
flat out vec4 ssE;         // цвет солнца, w — день (0..1)
flat out vec4 ssF;         // цвет заката, w — сила заката (солнце у горизонта)
flat out vec4 ssG;         // направление на луну, w — угловой радиус (0 — нет)
flat out vec4 ssH;         // цвет луны

// Номер профиля: по метке тумана (во всех проходах; под водой — метка воды), иначе по метке неба (верхний диск).
// water — камера под водой. -1 — ванильное небо (только режим 0).
int ss_profile(out bool water) {
    int code = spacesky_code(FogColor.rgb);
    int p = spacesky_code_profile(code);
    water = p >= 0 && code >= 16;
    if (p < 0) {
        code = spacesky_code(ColorModulator.rgb);
        p = code < 16 ? spacesky_code_profile(code) : -1;
    }
#if SPACESKY_EVERYWHERE
    if (p < 0) p = 0;
#endif
    return p;
}

void main() {
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    ssZone = vec4(0.0);
    ssNebula = vec3(0.0);
    ssNebOff = vec3(0.0);
    spaceDir = Position;
    ssRow0 = ssRow1 = ssRow3 = vec3(0.0);
    ssA = ssB = ssC = ssD = ssE = ssF = ssG = ssH = vec4(0.0);
    bool water;
    int prof = ss_profile(water);
    ssProf = prof;
    if (prof < 0) {
        // ванильный sky.vsh
        gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
        return;
    }

    mat4 pm = ProjMat * ModelViewMat;
    ssRow0 = vec3(pm[0][0], pm[1][0], pm[2][0]);
    ssRow1 = vec3(pm[0][1], pm[1][1], pm[2][1]);
    ssRow3 = vec3(pm[0][3], pm[1][3], pm[2][3]);

    vec3 pos = Position;
    float rim = length(pos.xz);
    if (pos.y > 0.0) {
        if (rim > 1.0) {
            // Край веера — почти в надир. Недотяг eps подбирается по текущей проекции: треугольники проходят от
            // камеры на расстоянии ≈ 4,13 блока на градус eps и не должны задевать ближнюю плоскость (её угол
            // удалён на c). Иначе при широком FOV (спринт, 110°+, широкий монитор) по краям экрана — полосы неба.
            float n = ProjMat[3][2] / (ProjMat[2][2] - 1.0);
            float c = n * sqrt(1.0 + 1.0 / (ProjMat[0][0] * ProjMat[0][0]) + 1.0 / (ProjMat[1][1] * ProjMat[1][1]));
            // + 0,25 блока запаса: покачивание камеры сдвигает проекцию до ~0,1 блока — веер не должен задеть ближнюю плоскость
            float eps = clamp((2.5 * c + 0.25) / 4.13, 0.005, 5.0);
            float A = radians(-90.0 + eps);
            pos = vec3(pos.xz / rim * cos(A), sin(A)).xzy * rim;
        } else {
            // центр веера — в зенит на том же расстоянии: треугольники уходят от камеры дальше ближней плоскости
            pos = vec3(0.0, 512.0, 0.0);
        }
    }
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);
    spaceDir = pos;

    vec4 p0 = SS_PROF_0[prof];
    vec4 p1 = SS_PROF_1[prof];
    vec4 p2 = SS_PROF_2[prof];
    vec4 p3 = SS_PROF_3[prof];
    ssC = vec4(p2.rgb * p2.w, p0.w);                                  // оттенок звёзд × яркость
    if (p0.w > 0.5) {
        // планета: время суток из начала тумана окружения (под водой — воды)
        vec4 p4 = SS_PROF_4[prof];
        vec3 sun = spacesky_sun_dir(spacesky_phase(FogEnvironmentalStart, water));
        float day = spacesky_daylight(sun.y);
        // звёзды — только когда солнце ниже ~3° (гражданские сумерки), а не на светлом небе восхода
        float night = 1.0 - smoothstep(-0.20, -0.05, sun.y);
        ssA = vec4(mix(p3.rgb, p0.rgb, day), p1.w);                    // горизонт
        ssB = vec4(mix(p4.rgb, p1.rgb, day), p2.w * night);            // зенит; звёзды — ночью
        ssC.rgb *= night;
        // закат: солнце у горизонта (чуть выше и ниже); у заката и солнце окрашено в цвет заката
        // (квадрат, а не pow: pow(x < 0, y) по спецификации не определён — ночью было бы NaN на всё небо)
        float ds = (sun.y + 0.02) / 0.22;
        float dusk = exp(-ds * ds);
        vec3 sunset = SS_PROF_5[prof].rgb;
        ssD = vec4(sun, p3.w);
        ssE = vec4(mix(SS_PROF_6[prof].rgb, sunset, 0.8 * dusk), day);
        ssF = vec4(sunset, dusk);
        ssG = vec4(SS_PROF_7[prof].xyz, p4.w);
        ssH = vec4(SS_PROF_8[prof].rgb, 0.0);
        return;                                                        // областей у планеты нет
    }
    ssA = vec4(p0.rgb, p1.w);
    ssB = vec4(p1.rgb, p2.w);
    vec4 p9 = SS_PROF_9[prof];
    if (Position.y < 0.0 && (p9.x > 0.0 || p9.y > 0.0)) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);                        // нижний диск — поверх частиц, не рисуем
    }

    vec4 zone = vec4(0.0);
    vec3 neb = vec3(0.0);
    float best = 0.0;
    int bi = -1;
#if SS_ZONES > 0
    vec2 cam = vec2(CameraBlockPos.xz) + CameraOffset.xz;
    for (int i = 0; i < SS_ZONES; i++) {
        vec4 z = SS_ZONE_POS[i];                                       // x, z, радиус, доля края с плавным входом
        vec4 k = SS_ZONE_KIND[i];                                      // вид + 4 * (профиль + 1), цвет rgb (-1 — профиля)
        int kp = int(k.x + 0.5);
        int zp = kp / 4 - 1;
        if (zp >= 0 && zp != prof) continue;                           // область другого профиля
        int kind = kp - (kp / 4) * 4;
        if (kind == 0 && p3.w < 0.5) continue;                          // Млечный путь выключен в профиле
        float w = 1.0 - smoothstep(z.z * (1.0 - z.w), z.z, distance(cam, z.xy));
        if (w <= 0.0) continue;
        if (kind == 0) zone.x = max(zone.x, w);
        else if (kind == 1) { neb += (k.y < 0.0 ? p3.rgb : k.yzw) * w; zone.y = max(zone.y, w); }
        else zone.z = max(zone.z, w);
        if (w > best) { best = w; bi = i; zone.w = float(i) * 7.31 + 1.7; }
    }
    // параллакс туманности: целые части вычитаются в int (вдали от 0 у float шаг до 2 блоков — рисунок дрожал бы)
    float L = SS_PROF_10[prof].x;
    if (bi >= 0 && zone.y > 0.0 && L > 0.0) {
        ivec2 c = SS_ZONE_IPOS[bi];
        ssNebOff = (vec3(float(CameraBlockPos.x - c.x), float(CameraBlockPos.y) - 64.0, float(CameraBlockPos.z - c.y))
            + CameraOffset) * (1.7 / L);
    }
#endif
    ssZone = zone;
    ssNebula = zone.y > 0.0 ? neb / max(neb.r + neb.g + neb.b, 1e-4) * 1.6 : vec3(0.0);
}
