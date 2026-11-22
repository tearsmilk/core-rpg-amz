#version 330

// spacesky: космос вместо неба. Всегда (дёшево) — тёмный фон и редкие звёзды. В областях из config.yml (ssZone из
// sky.vsh) — Млечный путь, туманности, густые звёзды; вне областей эта работа не делается вовсе (ветка одна на кадр).
// Цвет неба от времени суток и биома (ColorModulator) не используется — космос одинаковый днём и ночью.
// Режим (spacesky:mode.glsl): SPACESKY_EVERYWHERE 0 — космос только в мире hoka:space (МЕТКА в FogColor или в
// ColorModulator — то же условие, что в sky.vsh), иначе — ванильный sky.fsh.
// Чёрные дыры (spacesky:holes.glsl): шар-тень, фотонное кольцо, диск и линза поверх космоса — в мировых координатах,
// по позиции камеры (CameraBlockPos + CameraOffset). Шейдер не знает мира: дыры видны везде, где рисуется космос.
// Без дыр (SS_HOLES 0) main код дыр не вызывает — стоимость как без них.

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <spacesky:mode.glsl>
#moj_import <spacesky:holes.glsl>

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
    float dens = prof * (0.2 + 3.2 * n * n * n) * (0.55 + 0.9 * x);
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
    float u = ang / ts;                                          // угол от центра в радиусах тени
    float U = max(SSBH_U, disk.w * 1.6);
    float W = 1.0 - min(u * u / (U * U), 1.0);
    W *= W;
    float frac = 0.5 * (1.0 + t0 / sqrt(t0 * t0 + bl * bl));     // доля пути (и отклонения) впереди камеры
    float x = max(bl, 1.0);
    float alpha = ssbh_defl(x) * frac;                           // настоящее (для диска)
    // для фона издалека — уменьшенное: кольцо Эйнштейна ~2.5 радиуса тени на любом расстоянии
    float aBg = alpha * W * min(1.0, 8.0 * ts);
    bool hitH = t0 > 0.0 && bl < 1.0;
    float aa = pix * sqrt(max(D * D - 1.0, 0.0)) * 0.7 + 1e-4;
    float shadow = t0 > 0.0 ? smoothstep(1.0 - aa, 1.0 + aa, bl) : 1.0;

    vec3 col = vec3(0.0);
    float trans = 1.0;
    vec3 N = disk.xyz;
    vec4 F = vec4(0.0);
    vec4 B = vec4(0.0);
    if (disk.w > 0.0) {
        // отрезок 1: камера -> P (или -> вход в горизонт)
        float tEnd = hitH ? t0 - sqrt(1.0 - bl * bl) : max(t0, 0.0);
        float dn = dot(d, N);
        float th = -dot(O, N) / (abs(dn) < 1e-6 ? 1e-6 : dn);
        if (th > 0.0 && th < tEnd) {
            F = ssbh_disk(O + d * th, d, N, disk.w, look, t);
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
        if (!hitH && F.a < 0.995) {
            float aD = min(alpha * W, 0.72);    // меньше настоящего у самой тени: дальняя часть диска (дуга) прижимается к шару
            vec3 d2 = d * cos(aD) - n * sin(aD);
            vec3 P = O + d * max(t0, 0.0);
            float th2 = -dot(P, N) / dot(d2, N);
            if (th2 > 0.0) {
                vec3 h2 = P + d2 * th2;
                float R2 = length(h2);
                // попадание внутрь горизонта при грубом изломе — берём внутренний край (горячая кромка вокруг шара)
                B = ssbh_disk(R2 < 1.07 ? h2 * (1.07 / max(R2, 1e-4)) : h2, d2, N, disk.w, look, t);
            }
        }
    }
    // фотонное кольцо и свечение — тонкая яркая кромка у тени, со стороны приближения ярче
    float dop = disk.w > 0.0 ? dot(cross(N, n), -d) * sign(look.x) : 0.0;
    float e = max(bl - 1.0, 0.0) / max(aa * 2.0, 0.012);
    float thin = exp(-e) * shadow;                                   // кольцо поверх дальнего диска
    float ring = (exp(-(bl - 1.0) * 5.0) * 0.42 + exp(-(bl - 1.0) * 1.6) * 0.05) * shadow;
    vec3 ringCol = ssbh_bb(look.z * (1.05 + 0.35 * dop)) * look.y * (0.8 + 0.5 * dop);
    vec3 E = ring * ringCol;
    E = B.rgb + (1.0 - B.a) * E + thin * 0.9 * ringCol;
    E = F.rgb + (1.0 - F.a) * E;
    E = vec3(1.0) - exp(-E * 1.6);                               // мягкое насыщение ярких мест
    // у самой тени фон — небо, сжатое линзой в тонкое кольцо: приглушить, чтобы не было «каши» из звёзд
    float near = t0 > 0.0 ? mix(0.2, 1.0, smoothstep(1.0, 1.9, bl)) : 1.0;
    tr *= (1.0 - F.a) * (1.0 - B.a) * shadow * near;
    // отклонение фона
    vec3 dB = d * cos(aBg) - n * sin(aBg);
    d = normalize(dB);
    return E;
}

#if SS_HOLES > 0
// Отбор: может ли дыра задеть этот пиксель. Вне радиуса влияния — только эти скаляры (без корней и тригонометрии):
// издалека угол от центра в радиусах тени почти равен прицельному параметру луча (в горизонтах) — пропуск, если он
// больше U. inside — камера внутри горизонта.
bool ssbh_near(vec4 hp, float outer, ivec3 camBlock, vec3 camOff, vec3 d, inout bool inside) {
    vec3 O = ((vec3(camBlock) - hp.xyz) + camOff) / hp.w;       // сначала целые — без потери точности вдали от 0
    float D2 = dot(O, O);
    inside = inside || D2 <= 1.0;
    float U = max(SSBH_U, outer * 1.6);
    float t0 = -dot(O, d);
    return !(D2 > 4.0 * U * U && (t0 <= 0.0 || D2 - t0 * t0 > 1.1 * U * U));
}

// Шаг отбора на КОНСТАНТНОМ индексе (развёрнуто макросами, без цикла по массивам): компилятор сворачивает
// координаты дыры в константы. Найденные — биты mask (без локальных массивов: они дороги).
#define SSBH_CULL(i) if (ssbh_near(SS_HOLE_POS[i], SS_HOLE_DISK[i].w, camBlock, camOff, d, inside)) mask |= 1 << i;
#endif

// Все дыры: d — направление взгляда (меняется на направление для фона ss_space), camBlock/camOff — позиция
// камеры (CameraBlockPos, CameraOffset), pix — угловой размер пикселя. Возвращает emission поверх фона,
// tr — сколько фона видно (0 — шар или плотный диск: ss_space можно не считать). Не больше 4 дыр на пиксель.
vec3 ss_holes(inout vec3 d, ivec3 camBlock, vec3 camOff, float pix, float t, out float tr) {
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
    int drawn = 0;
    for (int i = 0; i < SS_HOLES && mask != 0; i++) {
        if ((mask & (1 << i)) == 0) {
            continue;
        }
        mask &= ~(1 << i);
        if (drawn == 4) {
            break;
        }
        drawn++;
        vec4 hp = SS_HOLE_POS[i];
        vec3 O = ((vec3(camBlock) - hp.xyz) + camOff) / hp.w;
        float tri = 1.0;
        vec3 e = ssbh_one(d, O, SS_HOLE_DISK[i], SS_HOLE_LOOK[i], pix, t, tri);
        E += tr * e;
        tr *= tri;
    }
#endif
    return E;
}

bool ss_space() {
#if SPACESKY_EVERYWHERE
    return true;
#else
    return spacesky_fog_marked(FogColor.rgb) || spacesky_marked(ColorModulator.rgb);
#endif
}

void main() {
    if (!ss_space()) {
        // ванильный sky.fsh
        fragColor = apply_fog(ColorModulator, sphericalVertexDistance, cylindricalVertexDistance, 0.0, FogSkyEnd, FogSkyEnd, FogSkyEnd, FogColor);
        return;
    }
    vec3 d = normalize(spaceDir);
    float t = GameTime * 1200.0;                                       // секунды, цикл — игровые сутки
#if SS_HOLES > 0
    float pix = length(fwidth(d));                                     // угловой размер пикселя (до ветвлений)
    float tr;
    vec3 col = ss_holes(d, CameraBlockPos, CameraOffset, pix, t, tr);  // d -> отклонённое направление (линза)
    if (tr > 0.002) {
        col += tr * ss_space(d, t);
    }
    fragColor = vec4(col, 1.0);
#else
    fragColor = vec4(ss_space(d, t), 1.0);
#endif
}
