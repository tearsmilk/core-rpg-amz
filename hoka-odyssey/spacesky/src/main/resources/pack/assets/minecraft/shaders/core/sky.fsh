#version 330

// spacesky: небо профиля (spacesky:profiles.glsl) вместо ванильного. Номер и параметры профиля считает sky.vsh
// (flat: ssProf, ssA..ssH — одно значение на кадр, ветвление по виду профиля одно на кадр):
// - галактика: всегда (дёшево) — фон и редкие звёзды профиля; в областях из config.yml (ssZone из sky.vsh) —
//   Млечный путь, туманности, густые звёзды; вне областей эта работа не делается вовсе;
// - планета: атмосфера (горизонт → зенит, цвета по времени суток), закат у солнца, своё солнце (диск и ореол),
//   ночью — звёзды профиля (вращаются вместе с солнцем), соседняя планета/луна с фазой (освещена солнцем профиля).
// ssProf < 0 (режим everywhere: false, мир без метки) — ванильный sky.fsh.
// Чёрные дыры (spacesky:holes.glsl): шар-тень, фотонное кольцо, диск и линза поверх неба — в мировых координатах,
// по позиции камеры (CameraBlockPos - CameraOffset); каждая — только в небе своего профиля (профиль её карты).
// Без дыр (SS_HOLES 0) main код дыр не вызывает — стоимость как без них.

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <spacesky:mode.glsl>
#moj_import <spacesky:profiles.glsl>
#moj_import <spacesky:holes.glsl>

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec3 spaceDir;
flat in vec3 ssRow0;
flat in vec3 ssRow1;
flat in vec3 ssRow3;
flat in vec4 ssZone;
flat in vec3 ssNebula;
flat in vec3 ssNebOff;
flat in int ssProf;
flat in vec4 ssA;
flat in vec4 ssB;
flat in vec4 ssC;
flat in vec4 ssD;
flat in vec4 ssE;
flat in vec4 ssF;
flat in vec4 ssG;
flat in vec4 ssH;

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

// Яркость звёзд фона: у дыры линза растягивает фон (ssStarDim = масштаб до линзы / после, считает main) — там звёзды
// гаснут, а не рисуются длинными штрихами. Без дыр — 1.
float ssStarDim = 1.0;
// Угловой размер пикселя по направлению фона (после линзы), считает main — fwidth только там, вне ветвлений.
float ssPix = 0.001;

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
    float r = min(max(size, ssPix * scale * 0.6), 0.35);              // не меньше пикселя — без мерцания на краю
    float dist = length(p - star);
    float lum = smoothstep(r, 0.0, dist) + 0.35 * smoothstep(r * 3.0, 0.0, dist);
    float u = h / density;                                             // 0..1 внутри «занятых» ячеек
    float twinkle = 0.75 + 0.25 * sin(t * (1.5 + 3.0 * h2) + 6.2832 * u);
    vec3 tint = mix(vec3(0.65, 0.78, 1.0), vec3(1.0, 0.86, 0.68), h2);
    return tint * lum * mix(0.45, 1.6, u * u) * twinkle * ssStarDim;
}

// Звёзды галактик: SS_STARS_PLAIN (у всех галактик звёзды по умолчанию) — константы, как до профилей; иначе —
// плотность ssA.w и оттенок × яркость ssC.rgb профиля.
#if SS_STARS_PLAIN
#define SS_GAL_DENS 1.0
#define SS_GAL_TINT
#else
#define SS_GAL_DENS ssA.w
#define SS_GAL_TINT * ssC.rgb
#endif

vec3 ss_space(vec3 d, float t) {
    // общий план — всегда: фон профиля (к зениту светлее) и редкие звёзды
    vec3 col = mix(ssA.rgb, ssB.rgb, 0.5 + 0.5 * d.y);
    col += (ss_stars(d, 160.0, 0.022 * SS_GAL_DENS, 0.06, t) * 0.8 + ss_stars(d, 55.0, 0.02 * SS_GAL_DENS, 0.05, t) * 1.3)
        SS_GAL_TINT;

    float seed = ssZone.w;
    // туманность: маска одним шумом; цветной шум — только внутри маски. Шум — в мировом поле: сфера радиуса
    // nebula-distance вокруг камеры (ssNebOff из sky.vsh), при полёте туманность сдвигается (параллакс)
    if (ssZone.y > 0.0) {
        float mask = smoothstep(0.35, 0.7, ss_noise(d * 1.7 + seed + ssNebOff));
        if (mask > 0.0) {
            float n = ss_fbm3(d * 3.2 + seed * 2.0 + ssNebOff * (3.2 / 1.7));
            col += mask * n * n * ssNebula * 0.42 * ssZone.y;
        }
    }
    // Млечный путь: полоса вдоль большого круга (наклон — от области); шум — только в полосе
    if (ssZone.x > 0.0) {
        vec3 axis = normalize(vec3(sin(seed), 2.2, cos(seed * 1.3)));
        float across = dot(d, axis);
        float qb = across / 0.16;
        float band = exp(-qb * qb);                                    // квадрат, не pow: pow(x < 0) не определён
        if (band > 0.02) {
            float clumps = ss_fbm3(d * 9.0);
            // пыль — волокна вдоль полосы: шум растянут вдоль (частота 6) и сжат поперёк (30), мягкий край
            vec3 along = d - axis * across;
            float dust = smoothstep(0.5, 0.8, ss_fbm3(along * 6.0 + axis * (across * 30.0) + 5.0));
            float clear = 1.0 - 0.55 * dust;
            col += band * (0.10 + 0.18 * clumps) * clear * vec3(0.78, 0.80, 1.0) * ssZone.x;
            // свечение неразрешённых звёзд — гладко, вместо «соли»; редкие звёзды — в прожилках пыли гаснут
            col += band * band * 0.05 * vec3(0.85, 0.85, 1.0) * (1.0 - 0.6 * dust) * ssZone.x;
            col += ss_stars(d, 420.0, 0.10 * band * ssZone.x * (1.0 - 0.8 * dust), 0.07, t) * 0.35 SS_GAL_TINT;
        }
    }
    // звёздное скопление: густые мелкие и средние звёзды
    if (ssZone.z > 0.0) {
        col += (ss_stars(d, 420.0, 0.06 * ssZone.z, 0.07, t) * 0.5 + ss_stars(d, 160.0, 0.05 * ssZone.z, 0.06, t) * 0.9)
            SS_GAL_TINT;
    }
    return col;
}

// Небо планеты: ssA — горизонт, ssB — зенит (уже по времени суток), ssD — солнце (направление, угловой радиус),
// ssE — цвет солнца и день, ssF — цвет и сила заката, ssG/ssH — луна. pix — угловой размер пикселя (сглаживание краёв дисков).
vec3 ss_planet(vec3 d, float t, float pix) {
    float y = d.y;
    float h = 1.0 - clamp(y, 0.0, 1.0);
    vec3 col = mix(ssA.rgb, ssB.rgb, 1.0 - h * h * h);                // от горизонта к зениту — плавно
    // ниже горизонта (пустота) — темнее до надира, без круга; спад начинается сразу с наклоном (у smoothstep наклон 0 —
    // над горизонтом градиент есть, под ним плато, и на горизонте видна светлая черта)
    col *= 1.0 - 0.45 * (1.0 - exp(-2.5 * max(-y, 0.0)));
    // звёзды ночью: небо вращается вместе с солнцем (вокруг оси z), у горизонта гаснут в дымке
    if (ssB.w > 0.003) {
        vec3 ds = vec3(dot(d.xy, ssD.xy), d.y * ssD.x - d.x * ssD.y, d.z);
        float fade = smoothstep(-0.05, 0.3, y);
        col += (ss_stars(ds, 160.0, 0.022 * ssA.w, 0.06, t) * 0.8 + ss_stars(ds, 55.0, 0.02 * ssA.w, 0.05, t) * 1.3)
            * ssC.rgb * fade;
    }
    float mu = dot(d, ssD.xyz);
    // закат: у горизонта небо уходит в цвет заката — сильнее в сторону солнца
    if (ssF.w > 0.004) {
        float m = max(mu, 0.0);
        float m2 = m * m;
        float m8 = m2 * m2;
        m8 *= m8;
        float m64 = m8 * m8;
        m64 *= m64;
        m64 *= m64;
        // ореол вокруг солнца (рассеяние вперёд, ~10–15°) и слабая полоса у горизонта; над горизонтом спадает
        // медленно, под ним — быстро (не симметричный эллипс)
        float g = (2.0 * m64 + 0.6 * m8 + 0.12 * m2) * exp(-max(y, 0.0) * 2.0) * exp(min(y, 0.0) * 8.0);
        col = mix(col, ssF.rgb, 0.85 * (1.0 - exp(-1.4 * g * ssF.w)));   // мягкое насыщение — без плоского пятна
    }
    // солнце: диск со сглаженным краем и ореол (угол — без acos: у малых углов sqrt(2(1 - cos)))
    if (ssD.w > 0.0) {
        float ang = sqrt(max(2.0 - 2.0 * mu, 0.0));
        float r = ssD.w;
        float disk = smoothstep(r + pix, r - pix, ang);
        float glow = exp(-ang / (r * 2.2)) * 0.35 + exp(-ang / (r * 9.0)) * 0.12;
        // ниже горизонта солнце закрыто планетой: диск срезается по горизонту, ореол гаснет чуть ниже
        // (иначе в полночь при взгляде вниз из пустоты в надире — яркий диск)
        disk *= smoothstep(-2.0 * pix, 3.0 * pix, y);
        glow *= smoothstep(-0.12, 0.0, y);
        // ореол — добавкой к небу; диск — поверх неба цветом солнца (самый яркий канал = 1): иначе небо + солнце
        // упираются в 1 по всем каналам, и солнце любого цвета (и на закате) — белое
        col += ssE.rgb * glow;
        vec3 sc = ssE.rgb / max(max(ssE.r, max(ssE.g, ssE.b)), 1e-4);
        col = mix(col, sc, clamp(disk, 0.0, 1.0));
    }
    // луна/соседняя планета: шар, освещённый солнцем профиля (фаза — от положения солнца); днём сквозь атмосферу
    if (ssG.w > 0.0) {
        float c = dot(d, ssG.xyz);
        float sr = sin(ssG.w);
        if (c > 0.0 && 1.0 - c * c < sr * sr * 1.1) {
            vec3 q = (d - ssG.xyz * c) / sr;                           // смещение по диску, |q| <= 1
            float q2 = dot(q, q);
            float mask = smoothstep(1.0 + pix / sr, 1.0 - pix / sr, sqrt(q2)) * smoothstep(-pix, pix, y);  // за горизонтом не видна
            float mz = sqrt(max(1.0 - q2, 0.0));
            vec3 n = q - ssG.xyz * mz;                                   // нормаль поверхности (к камере)
            float lit = smoothstep(-0.12, 0.25, dot(n, ssD.xyz));       // мягкий терминатор
            vec3 moon = ssH.rgb * (lit * (0.35 + 0.65 * max(dot(n, ssD.xyz), 0.0)) + 0.03);
            moon *= mix(0.55, 1.0, mz);                                  // потемнение к краю диска
            moon *= 0.85 + 0.3 * ss_noise(q * 5.0 + 3.1);               // рельеф: пятна «морей»
            // днём сквозь атмосферу: небо поверх слабее, своя яркость чуть ниже — без пересвета, цвет тот же, что ночью
            col = mix(col, col * mix(0.12, 0.45, ssE.w) + moon * mix(1.0, 0.8, ssE.w), mask);
        }
    }
    return col;
}

// ===== чёрные дыры =====
// Дыры — из spacesky:holes.glsl (его пишет модуль по дырам gravity; запасной — SS_HOLES 0): для каждой i
//   SS_HOLE_POS[i]  = vec4(x, y, z, horizon)       центр в мире и радиус горизонта, блоков
//   SS_HOLE_DISK[i] = vec4(nx, ny, nz, outer)      нормаль диска (нормированная; наклон/поворот) и внешний радиус диска
//                                                  в горизонтах (0 — без диска; обычно 4..7)
//   SS_HOLE_LOOK[i] = vec4(spin, bright, temp, seed)  spin — скорость вращения (знак — направление, 1 — обычно),
//                                                  bright — яркость (1), temp — «температура» (1; >1 белее/голубее,
//                                                  <1 краснее), seed — зерно рисунка диска
// Модель (дёшево, без raymarch): луч от камеры — прямая до точки наибольшего сближения P с центром, там излом на
// угол отклонения α(b) (b — прицельный параметр); диск ищется на обоих отрезках: на первом — диск перед дырой,
// на втором — «изображение» дальней части диска над/под шаром (как в Interstellar). Фон считается по отклонённому
// направлению. Тень = горизонт: угловой радиус asin(horizon / dist). Всё в единицах горизонта.
#ifndef SS_HOLES
#define SS_HOLES 0
#endif

#define SSBH_U 10.0          // радиус влияния, в угловых радиусах тени (дальше — ни работы, ни искажений)
#define SSBH_RIN 1.1         // внутренний край диска, в горизонтах (у самого шара)
#define SSBH_LMAX 0.7        // предел углового радиуса линзы, рад (~40°): вблизи U радиусов тени — почти всё небо,
                             // а линза там слабая; без предела вблизи дыры каждый пиксель экрана считал дыру целиком

// Угловой радиус влияния линзы (рад) с расстояния D (в горизонтах): U радиусов тени, но не больше SSBH_LMAX.
float ssbh_lens(float D, float outer) {
    float U = max(SSBH_U, outer * 1.6);
    return min(U * asin(min(1.0 / D, 1.0)), SSBH_LMAX);
}

// Угол отклонения луча с прицельным параметром x (в горизонтах, x >= 1; тень = горизонт, т.е. b_crit = 1):
// слабое поле 0,77/x (= 2 r_s / b при b_crit = 2,6 r_s) + логарифмический рост у фотонного кольца.
float ssbh_defl(float x) {
    float x2 = x * x;
    return min(0.77 / x - log(max(1.0 - 1.0 / (x2 * x2), 1e-4)), 3.0);
}

// «Чёрное тело»: T ~ 0.3 тёмно-красный, 0.7 оранжевый, 1.1 жёлто-белый, 1.6+ бело-голубой
vec3 ssbh_bb(float T) {
    vec3 c = vec3(1.0,
                  pow(clamp(T * 0.95 - 0.12, 0.0, 1.0), 1.35),
                  pow(clamp(T * 0.85 - 0.42, 0.0, 1.0), 1.7));
    c.r -= 0.35 * smoothstep(1.3, 2.4, T);
    c.g -= 0.10 * smoothstep(1.6, 2.6, T);
    return c;
}

// Рисунок диска: полосы вдоль орбит (шум растянут по азимуту), спираль, дифференциальное вращение (Кеплер);
// время — два сдвинутых на полпериода слоя с перетеканием, поэтому сдвиг не копится и нет скачков.
float ssbh_pattern(float phi, float lr, float om, float t, float seed) {
    const float P = 30.0;                                    // 1200 с (игровые сутки) делятся нацело — без скачка
    float f = fract(t / P);
    float w = 1.0 - abs(2.0 * f - 1.0);
    float a1 = phi - om * f * P + 2.2 * lr;
    float a2 = phi - om * fract(f + 0.5) * P + 2.2 * lr;
    vec3 q1 = vec3(cos(a1), sin(a1), 0.0);
    vec3 q2 = vec3(cos(a2), sin(a2), 0.0);
    float n1 = 0.62 * ss_noise(q1 * 1.4 + vec3(0.0, 0.0, lr * 9.0 + seed))
             + 0.38 * ss_noise(q1 * 3.3 + vec3(5.1, 2.3, lr * 26.0 + seed * 1.7));
    float n2 = 0.62 * ss_noise(q2 * 1.4 + vec3(3.7, 1.9, lr * 9.0 + seed + 11.0))
             + 0.38 * ss_noise(q2 * 3.3 + vec3(8.4, 6.6, lr * 26.0 + seed * 1.7 + 7.0));
    return mix(n2, n1, w);
}

// Точка диска h (от центра, в горизонтах), луч rd (от камеры); результат — rgb (с учётом альфы) и альфа.
vec4 ssbh_disk(vec3 h, vec3 rd, vec3 N, float rout, vec4 look, float t) {
    float R = length(h);
    if (R < 1.0 || R > rout) {
        return vec4(0.0);
    }
    vec3 e1 = normalize(abs(N.y) < 0.9 ? cross(N, vec3(0.0, 1.0, 0.0)) : cross(N, vec3(1.0, 0.0, 0.0)));
    vec3 e2 = cross(N, e1);
    float phi = atan(dot(h, e2), dot(h, e1));
    float x = min(SSBH_RIN / R, 1.0);
    float om = 0.35 * look.x * pow(x, 1.5);
    float n = ssbh_pattern(phi, log(R), om, t, look.w);

    // профиль: резкий яркий внутренний край, мягкий внешний
    float prof = smoothstep(1.0, 1.06, R) * smoothstep(rout, rout * 0.55, R);
    // базовая плотность не слишком мала: иначе сквозь ближний диск на средних радиусах просвечивает край шара («чаша»)
    float dens = prof * (0.45 + 3.0 * n * n * n) * (0.55 + 0.9 * x);
    // Доплер + гравитационное красное смещение
    vec3 v = cross(N, h) / R * sign(look.x);
    float beta = 0.42 * sqrt(x);
    float g = sqrt(1.0 - beta * beta) / (1.0 - beta * dot(v, -rd));
    g *= sqrt(1.0 - 0.3 / R);
    float T = look.z * (0.35 + 0.85 * pow(x, 0.85)) * g;
    float I = look.y * (0.25 + 3.0 * x * x * x) * g * g * g * (0.3 + 1.5 * n);
    // оптически тонкий слой: под острым углом — плотнее и ярче (диск ребром — яркая нить)
    float a = 1.0 - exp(-dens * 1.6 / max(abs(dot(rd, N)), 0.06));
    return vec4(ssbh_bb(T) * I * a, a);
}

// Одна дыра. d — направление (меняется: отклонение для фона), O — камера относительно центра (в горизонтах),
// pix — угловой размер пикселя. Возвращает emission (над фоном) и пропускание фона в tr.
vec3 ssbh_one(inout vec3 d, vec3 O, vec4 disk, vec4 look, float pix, float t, inout float tr) {
    float D = length(O);
    float ts = asin(1.0 / D);                                    // угловой радиус тени
    float t0 = -dot(O, d);                                       // до точки наибольшего сближения (вдоль луча)
    vec3 Pl = O + d * t0;
    float bl = length(Pl);                                       // прицельный параметр
    vec3 n = Pl / max(bl, 1e-5);
    float ang = acos(clamp(t0 / D, -1.0, 1.0));
    float L = ssbh_lens(D, disk.w);                              // радиус влияния линзы, рад
    float W = 1.0 - min(ang * ang / (L * L), 1.0);
    W *= W;
    float frac = 0.5 * (1.0 + t0 / sqrt(t0 * t0 + bl * bl));     // доля пути (и отклонения) впереди камеры
    float x = max(bl, 1.0);
    float alpha = ssbh_defl(x) * frac;                           // настоящее (для диска)
    // для фона издалека — уменьшенное: кольцо Эйнштейна ~2.5 радиуса тени на любом расстоянии
    float aBg = alpha * W * min(1.0, 8.0 * ts);
    bool hitH = t0 > 0.0 && bl < 1.0;
    float aa = pix * sqrt(max(D * D - 1.0, 0.0)) * 0.7 + 1e-4;
    // луч почти по касательной к горизонту (bl в последних ~2 пикселях под краем тени): переход «в шар» → «мимо»
    // плавный — иначе у края тени точка входа скачет (бесконечная производная sqrt) и там пунктир чёрных пикселей
    float graze = hitH ? smoothstep(1.0 - 2.0 * aa, 1.0, bl) : 1.0;
    float shadow = t0 > 0.0 ? smoothstep(1.0 - aa, 1.0 + aa, bl) : 1.0;

    vec3 col = vec3(0.0);
    float trans = 1.0;
    vec3 N = disk.xyz;
    vec4 F = vec4(0.0);
    vec4 B = vec4(0.0);
    if (disk.w > 0.0) {
        // отрезок 1: камера -> P (или -> вход в горизонт)
        float tEnd = hitH ? mix(t0 - sqrt(max(1.0 - bl * bl, 0.0)), t0, graze) : max(t0, 0.0);
        float dn = dot(d, N);
        float th = -dot(O, N) / (abs(dn) < 1e-6 ? 1e-6 : dn);
        // Луч в шар, ближний диск перед ним: там, где диск режет шар, его прозрачная внутренняя кромка (R 1..1,06)
        // ложится на чёрный шар линией тоньше пикселя — пунктир чёрных точек по краю тени. Поэтому перед шаром диск
        // плотный до самой поверхности (точка — не ближе 1,06 к центру), с запасом ~2 пикселя за точкой входа.
        float slack = hitH ? 2.0 * aa * D : 0.0;
        if (th > 0.0 && th < tEnd + slack) {
            vec3 h1 = O + d * th;
            F = ssbh_disk(hitH ? h1 * max(1.0, 1.06 / max(length(h1), 1e-4)) : h1, d, N, disk.w, look, t);
        } else if (abs(dn) < 0.15 && tEnd > 0.0) {
            // почти по касательной: у диска есть толщина (~5% радиуса) — берём точку наибольшего сближения P
            vec3 hc = O + d * tEnd;
            float y = dot(hc, N);
            vec3 hp = hc - N * y;
            hp *= max(1.0, 1.25 / max(length(hp), 1e-4));            // луч в шар: нить диска перед ним
            float hs = 0.05 * length(hp);
            float wgt = exp(-y * y / (hs * hs));
            if (wgt > 0.01) {
                F = ssbh_disk(hp, d, N, disk.w, look, t) * wgt;
            }
        }
        // отрезок 2: от P по отклонённому направлению (дальняя часть диска над/под шаром)
        if (graze > 0.0 && F.a < 0.995) {
            // меньше настоящего у самой тени: дальняя часть диска (дуга) прижимается к шару; предел — мягкий (без складки)
            float aD = 0.72 * tanh(alpha * W / 0.72);
            vec3 d2 = d * cos(aD) - n * sin(aD);
            vec3 P = O + d * max(t0, 0.0);
            float th2 = -dot(P, N) / dot(d2, N);
            if (th2 > 0.0) {
                vec3 h2 = P + d2 * th2;
                float R2 = length(h2);
                // попадание внутрь горизонта при грубом изломе — берём внутренний край (горячая кромка вокруг шара)
                B = ssbh_disk(R2 < 1.07 ? h2 * (1.07 / max(R2, 1e-4)) : h2, d2, N, disk.w, look, t) * graze;
            }
        }
    }
    // фотонное кольцо и свечение — тонкая яркая кромка у тени, со стороны приближения ярче
    float dop = disk.w > 0.0 ? dot(cross(N, n), -d) * sign(look.x) : 0.0;
    float e = max(bl - 1.0, 0.0) / max(aa * 2.0, 0.012);
    // кольцо — только у дыры ВПЕРЕДИ (t0 > 0): за спиной bl тоже меньше 1 (прямая луча проходит у центра позади
    // камеры), и без этого в точке, противоположной дыре, светился жёлтый шар без тени (призрак-антипод)
    float front = smoothstep(0.0, 0.1, t0) * shadow;
    float thin = exp(-e) * front;                                    // кольцо поверх дальнего диска
    float ring = (exp(-(bl - 1.0) * 5.0) * 0.42 + exp(-(bl - 1.0) * 1.6) * 0.05) * front;
    vec3 ringCol = ssbh_bb(look.z * (1.05 + 0.35 * dop)) * look.y * (0.8 + 0.5 * dop);
    vec3 E = ring * ringCol;
    E = B.rgb + (1.0 - B.a) * E + thin * 0.9 * ringCol;
    E = F.rgb + (1.0 - F.a) * E;
    E = vec3(1.0) - exp(-E * 1.6);                               // мягкое насыщение ярких мест
    // у самой тени фон — небо, сжатое линзой в тонкое кольцо: приглушить, чтобы не было «каши» из звёзд
    float near = t0 > 0.0 ? mix(0.15, 1.0, smoothstep(1.0, 3.0, bl)) : 1.0;
    tr *= (1.0 - F.a) * (1.0 - B.a) * shadow * near;
    // отклонение фона
    vec3 dB = d * cos(aBg) - n * sin(aBg);
    d = normalize(dB);
    return E;
}

#if SS_HOLES > 0
// Отбор: может ли дыра задеть этот пиксель (ssbh_near ниже). inside — камера внутри горизонта.
// Камера относительно центра дыры i, в горизонтах: целые части вычитаются в int (точно на любом удалении от 0 — в float
// шаг вдали от 0 до 2 блоков, дыра дрожала бы), остаток мал и точен в float.
// CameraOffset в 1.21.11 — НЕ дробная часть позиции камеры, а CameraBlockPos - камера (минус дробная часть; клиент
// пишет floor(cam) - cam, ванильный terrain.vsh прибавляет его к позиции вершины). Камера = CameraBlockPos - CameraOffset.
// С «+» дыра внутри блока ползла в обратную сторону, а на каждой границе блока прыгала на 2 блока параллакса.
#define SSBH_O(i) ((vec3(camBlock - SS_HOLE_IPOS[i]) - (camOff + SS_HOLE_FRAC[i])) / SS_HOLE_POS[i].w)

// Отбор и вблизи (раньше ближе 2U горизонтов — 60 блоков при горизонте 3 — дыру считал КАЖДЫЙ пиксель неба,
// даже за спиной): дыра задевает луч, только если он проходит ближе max(диск, 4) горизонтов от центра впереди камеры
// (шар, кольцо, диск), камера внутри радиуса диска (диск вокруг, виден и сзади) или луч в конусе линзы.
bool ssbh_near(vec3 O, float outer, vec3 d, inout bool inside) {
    float D2 = dot(O, O);
    inside = inside || D2 <= 1.0;
    float t0 = -dot(O, d);
    float R = max(outer, 4.0);
    if (t0 > 0.0 && D2 - t0 * t0 < R * R) return true;          // шар, кольцо, диск впереди
    if (D2 < outer * outer) return true;                         // камера в пределах диска
    // конус линзы (ssbh_lens), без корней и тригонометрии на пиксель: ближе dc горизонтов радиус конуса — SSBH_LMAX
    // (cos² — константа), дальше — U·asin(1/D) ≈ U/D, т.е. прицельный параметр луча меньше ~U горизонтов (с запасом)
    if (t0 <= 0.0) return false;
    float U = max(SSBH_U, outer * 1.6);
    float dc = U / sin(SSBH_LMAX);
    return D2 < dc * dc ? t0 * t0 > D2 * (cos(SSBH_LMAX) * cos(SSBH_LMAX)) : D2 - t0 * t0 < 1.1 * U * U;
}

// Шаг отбора на КОНСТАНТНОМ индексе (развёрнуто макросами, без цикла по массивам): компилятор сворачивает
// координаты дыры в константы. Найденные — биты mask (без локальных массивов: они дороги). Дыра другого профиля
// (номер — константа, профиль — flat) пропускается одним сравнением.
#define SSBH_CULL(i) if (SS_HOLE_PROF[i] == prof && ssbh_near(SSBH_O(i), SS_HOLE_DISK[i].w, d, inside)) mask |= 1 << i;
#endif

// Все дыры профиля prof: d — направление взгляда (меняется на направление для фона ss_space), camBlock/camOff —
// позиция камеры (CameraBlockPos, CameraOffset), pix — угловой размер пикселя. Возвращает emission поверх фона,
// tr — сколько фона видно (0 — шар или плотный диск: ss_space можно не считать). Рисуются все отобранные дыры
// (их не больше SS_HOLES = 16, дальние отсеяны отбором).
vec3 ss_holes(inout vec3 d, ivec3 camBlock, vec3 camOff, float pix, float t, int prof, out float tr) {
    tr = 1.0;
    vec3 E = vec3(0.0);
#if SS_HOLES > 0
    int mask = 0;
    bool inside = false;
    SSBH_CULL(0)
#if SS_HOLES > 1
    SSBH_CULL(1)
#endif
#if SS_HOLES > 2
    SSBH_CULL(2)
#endif
#if SS_HOLES > 3
    SSBH_CULL(3)
#endif
#if SS_HOLES > 4
    SSBH_CULL(4)
#endif
#if SS_HOLES > 5
    SSBH_CULL(5)
#endif
#if SS_HOLES > 6
    SSBH_CULL(6)
#endif
#if SS_HOLES > 7
    SSBH_CULL(7)
#endif
#if SS_HOLES > 8
    SSBH_CULL(8)
#endif
#if SS_HOLES > 9
    SSBH_CULL(9)
#endif
#if SS_HOLES > 10
    SSBH_CULL(10)
#endif
#if SS_HOLES > 11
    SSBH_CULL(11)
#endif
#if SS_HOLES > 12
    SSBH_CULL(12)
#endif
#if SS_HOLES > 13
    SSBH_CULL(13)
#endif
#if SS_HOLES > 14
    SSBH_CULL(14)
#endif
#if SS_HOLES > 15
    SSBH_CULL(15)
#endif
    if (inside) {                                                // внутри горизонта — всё чёрное
        tr = 0.0;
        return vec3(0.0);
    }
    for (int i = 0; i < SS_HOLES && mask != 0; i++) {
        if ((mask & (1 << i)) == 0) {
            continue;
        }
        mask &= ~(1 << i);
        vec3 O = SSBH_O(i);
        float tri = 1.0;
        vec3 e = ssbh_one(d, O, SS_HOLE_DISK[i], SS_HOLE_LOOK[i], pix, t, tri);
        E += tr * e;
        tr *= tri;
    }
#endif
    return E;
}

// Небо профиля без дыр: ветка по виду профиля (ssC.w — flat, одна на кадр; без планет в профилях её нет вовсе).
// pix — угловой размер пикселя (сглаживание краёв дисков планеты).
vec3 ss_sky(vec3 d, float t, float pix) {
#if SS_PLANETS > 0
    if (ssC.w > 0.5) {
        return ss_planet(d, t, pix);
    }
#endif
    return ss_space(d, t);
}

void main() {
#if !SPACESKY_EVERYWHERE
    if (ssProf < 0) {
        // ванильный sky.fsh (в режиме everywhere: true такого нет — без метки профиль space)
        fragColor = apply_fog(ColorModulator, sphericalVertexDistance, cylindricalVertexDistance, 0.0, FogSkyEnd, FogSkyEnd, FogSkyEnd, FogColor);
        return;
    }
#endif
    // направление взгляда через этот пиксель — точка на бесконечности, без сдвига камеры (см. sky.vsh, ssRow*)
    vec2 ndc = gl_FragCoord.xy / ScreenSize * 2.0 - 1.0;
    vec3 d = cross(ssRow0 - ndc.x * ssRow3, ssRow1 - ndc.y * ssRow3);
    if (dot(d, ssRow3) < 0.0) d = -d;
    d = normalize(d);
    float t = GameTime * 1200.0;                                       // секунды, цикл — игровые сутки
    // угловой размер пикселя — здесь, в однородном управлении (производные внутри ветвлений не определены)
    float pix = length(fwidth(d));
    ssPix = pix;
#if SS_HOLES > 0
    float tr;
    vec3 col = ss_holes(d, CameraBlockPos, CameraOffset, pix, t, ssProf, tr);  // d -> отклонённое направление (линза)
    // масштаб фона после линзы: где она растягивает фон, звёзды гаснут (и не шире ячейки), а не тянутся штрихами
    float pixB = length(fwidth(d));
    ssPix = max(pix, pixB);
    ssStarDim = clamp(pix / max(pixB, 1e-6), 0.0, 1.0);
    if (tr > 0.002) {
        col += tr * ss_sky(d, t, pix);
    }
    fragColor = vec4(col, 1.0);
#else
    fragColor = vec4(ss_sky(d, t, pix), 1.0);
#endif
}
