// spacesky: профили неба — файл пишет модуль spacesky из config.yml, руками не менять.
#ifndef SPACESKY_PROFILES_GLSL
#define SPACESKY_PROFILES_GLSL
// профили: 0=space(galaxy)
#define SS_PROFILES 1
// планет среди профилей (0 — кода неба планеты в sky.fsh нет вовсе)
#define SS_PLANETS 0
// 1 — у всех галактик звёзды по умолчанию (плотность, яркость, оттенок): sky.fsh считает их константами
#define SS_STARS_PLAIN 1
// МЕТКА профиля i — цвет неба и тумана (0, 24, i) / 255, МЕТКА ВОДЫ — (0, 24, 16 + i) / 255. У ванильных цветов
// тумана и неба красный не меньше 12% зелёного — с меткой не спутать. Номер — синий / зелёный: не меняется от
// затемнения у дна мира, ночного зрения (умножение на число) и затемнения экрана (зелёный и синий — одинаково);
// само затемнение — в яркости метки (spacesky_mark_level).
// Код: 0..15 — небо профиля, 16..31 — вода профиля, -1 — не метка.
int spacesky_code(vec3 c) {
    if (c.g < 1e-6 || c.r > c.g * 0.02) return -1;
    float k = c.b / c.g * 24.0;
    float i = floor(k + 0.5);
    if (abs(k - i) > 0.3 || i > 31.5) return -1;
    return int(i);
}

// Яркость метки (0..1): клиент умножает цвет тумана на число (слепота, тьма, затемнение у дна мира, босс) —
// во столько же раз затемняется туман профиля. Ночное зрение (больше 1) цвет профиля не осветляет.
float spacesky_mark_level(vec3 c) {
    return clamp(c.g * (255.0 / 24.0), 0.0, 1.0);
}

// Номер профиля по коду метки (-1 — не метка или такого профиля нет).
int spacesky_code_profile(int code) {
    int p = code & 15;
    return code < 0 || p >= SS_PROFILES ? -1 : p;
}

// Цвет неба или тумана — МЕТКА неба какого-нибудь профиля?
bool spacesky_marked(vec3 c) {
    int k = spacesky_code(c);
    return k >= 0 && k < 16 && k < SS_PROFILES;
}

// Цвет тумана (FogColor) — мира какого-нибудь профиля (МЕТКА или, под водой, МЕТКА ВОДЫ)?
bool spacesky_fog_marked(vec3 c) {
    return spacesky_code_profile(spacesky_code(c)) >= 0;
}

// Фаза суток планеты (0 — восход, 0.25 — полдень, 0.5 — закат, 0.75 — полночь) из начала тумана окружения:
// таймлайн ведёт его от 30000 до 31000 за сутки, под водой — от -8 до -9.
float spacesky_phase(float envStart, bool water) {
    return fract(water ? -(envStart + 8.0) : (envStart - 30000.0) / 1000.0);
}

// Направление на солнце: восход на востоке (+x), полдень в зените, закат на западе (-x) — как в ванили.
// Фаза равномерная, а ванильное солнце (sun_angle суток minecraft:day, кривая от полудня) у горизонта идёт быстрее:
// та же кривая здесь — иначе на восходе солнце планеты на ~12° ниже ванильного, а свет мира уже дневной.
vec3 spacesky_sun_dir(float phase) {
    float d = fract(phase - 0.25);                                  // доля суток от полудня
    float a = 6.2831853 * ((2.0 * d + 0.5 - 0.5 * cos(3.1415927 * d)) / 3.0 + 0.25);
    return vec3(cos(a), sin(a), 0.0);
}

// День (1) / ночь (0) по высоте солнца.
float spacesky_daylight(float sunY) {
    return smoothstep(-0.12, 0.2, sunY);
}
// SS_PROF_0 (галактика: фон у горизонта | планета: горизонт днём), w — вид (0 галактика, 1 планета)
const vec4 SS_PROF_0[SS_PROFILES] = vec4[SS_PROFILES](
    vec4(0.00600, 0.00700, 0.01800, 0.00000)
);
// SS_PROF_1 (фон к зениту | зенит днём), w — плотность звёзд
const vec4 SS_PROF_1[SS_PROFILES] = vec4[SS_PROFILES](
    vec4(0.01200, 0.01100, 0.03400, 1.00000)
);
// SS_PROF_2 оттенок звёзд, w — яркость звёзд
const vec4 SS_PROF_2[SS_PROFILES] = vec4[SS_PROFILES](
    vec4(1.00000, 1.00000, 1.00000, 1.00000)
);
// SS_PROF_3 (цвет туманностей | горизонт ночью), w — (Млечный путь 0/1 | угловой радиус солнца, рад)
const vec4 SS_PROF_3[SS_PROFILES] = vec4[SS_PROFILES](
    vec4(0.69020, 0.37647, 1.00000, 1.00000)
);
// SS_PROF_4 (- | зенит ночью), w — угловой радиус луны, рад (0 — нет)
const vec4 SS_PROF_4[SS_PROFILES] = vec4[SS_PROFILES](
    vec4(0.00000, 0.00000, 0.00000, 0.00000)
);
// SS_PROF_5 цвет заката, w — 0
const vec4 SS_PROF_5[SS_PROFILES] = vec4[SS_PROFILES](
    vec4(0.00000, 0.00000, 0.00000, 0.00000)
);
// SS_PROF_6 цвет солнца, w — 0
const vec4 SS_PROF_6[SS_PROFILES] = vec4[SS_PROFILES](
    vec4(0.00000, 0.00000, 0.00000, 0.00000)
);
// SS_PROF_7 направление на луну, w — 0
const vec4 SS_PROF_7[SS_PROFILES] = vec4[SS_PROFILES](
    vec4(0.00000, 0.00000, 0.00000, 0.0)
);
// SS_PROF_8 цвет луны, w — 0
const vec4 SS_PROF_8[SS_PROFILES] = vec4[SS_PROFILES](
    vec4(0.00000, 0.00000, 0.00000, 0.00000)
);
// SS_PROF_9 параллакс (stars.vsh): доля ближних звёзд, пыль, плитка звёзд, плитка пыли (блоки; 0 — частиц нет)
const vec4 SS_PROF_9[SS_PROFILES] = vec4[SS_PROFILES](
    vec4(1.00000, 1.00000, 256.0, 24.0)
);
// SS_PROF_10 x — даль туманностей областей, блоки (0 — на бесконечности), yzw — 0
const vec4 SS_PROF_10[SS_PROFILES] = vec4[SS_PROFILES](
    vec4(3000.00000, 0.0, 0.0, 0.0)
);

// Туман мира профиля p (fog.glsl): цвет (у планеты — по времени суток) и дымка окружения планеты.
// water — камера под водой (МЕТКА ВОДЫ); es/ee — начало и конец тумана окружения (из Fog).
vec3 spacesky_profile_fog(int p, bool water, inout float es, inout float ee) {
    if (p == 0) {  // space
        return water ? vec3(0.01961, 0.01961, 0.20000) : vec3(0.00600, 0.00700, 0.01800);
    }
    return vec3(0.00600, 0.00700, 0.01800);
}
#endif
