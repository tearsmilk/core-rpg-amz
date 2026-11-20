#version 330

#moj_import <textshader:ts_common.glsl>

#define TS_PI 3.14159265359
#define TS_TAU 6.28318530718
#define TS_EDGE 0.0001

vec2 tsAtlas;
vec2 tsUv;
vec2 tsUvMin;
vec2 tsUvMax;
vec2 tsUvMid;
vec2 tsLocal;
vec2 tsPos;
vec2 tsChar;
vec2 tsSize;
vec4 tsFill;
vec4 tsBack;
vec4 tsTop;
bool tsIsShadow;
bool tsLookup;
float tsIndex;
float tsT;
float tsSec;

bool ts_outside(vec2 uv) {
    return uv.x < tsUvMin.x + TS_EDGE || uv.y < tsUvMin.y + TS_EDGE || uv.x > tsUvMax.x - TS_EDGE || uv.y > tsUvMax.y - TS_EDGE;
}

float ts_alpha(vec2 uv) {
    return ts_outside(uv) ? 0.0 : texture(Sampler0, uv).a;
}

bool ts_solid(vec2 uv) {
    return ts_alpha(uv) >= 0.1;
}

vec2 ts_texel(vec2 offset) {
    return (floor(tsUv * tsAtlas) + 0.5 + offset) / tsAtlas;
}

vec3 ts_hsv(vec3 c) {
    vec4 k = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
    vec3 p = abs(fract(c.xxx + k.xyz) * 6.0 - k.www);
    return c.z * mix(k.xxx, clamp(p - k.xxx, 0.0, 1.0), c.y);
}

float ts_hash(vec2 s) {
    return fract(sin(dot(s, vec2(12.9898, 78.233))) * 43758.5453);
}

float ts_hash(float s) {
    return fract(sin(s) * 43758.5453);
}

float ts_noise(float n) {
    float i = floor(n);
    return mix(ts_hash(i), ts_hash(i + 1.0), smoothstep(0.0, 1.0, fract(n)));
}

float ts_noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = mix(ts_hash(i), ts_hash(i + vec2(1.0, 0.0)), f.x);
    float b = mix(ts_hash(i + vec2(0.0, 1.0)), ts_hash(i + vec2(1.0, 1.0)), f.x);
    float r = mix(a, b, f.y);
    return r * r;
}

int ts_row() {
    return int(floor((tsUv.y - tsUvMin.y) * tsAtlas.y));
}

float ts_cell() {
    return floor(tsChar.x / 6.0);
}

float ts_letter() {
    return tsIndex >= 0.0 ? tsIndex : mod(ts_cell(), 64.0);
}

void ts_set(vec3 c) {
    tsFill.rgb = tsIsShadow ? c * 0.25 : c;
}

void ts_no_shadow() {
    if (tsIsShadow) {
        tsFill.a = 0.0;
    }
}

vec3 ts_lit(vec3 c, vec3 p) {
    return p * (0.35 + 0.85 * dot(c, vec3(0.299, 0.587, 0.114)));
}

void ts_tint(vec3 p) {
    tsFill.rgb = tsIsShadow ? p * 0.25 : ts_lit(tsFill.rgb, p);
    tsBack.rgb = ts_lit(tsBack.rgb, p);
    tsTop.rgb = ts_lit(tsTop.rgb, p);
}

float ts_near(int radius) {
    float best = 99.0;
    for (int x = -radius; x <= radius; x++) {
        for (int y = -radius; y <= radius; y++) {
            if (x == 0 && y == 0) {
                continue;
            }
            if (ts_solid(ts_texel(vec2(x, y)))) {
                best = min(best, length(vec2(x, y)));
            }
        }
    }
    return best;
}

vec3 ts_field() {
    vec3 best = vec3(0.0, 0.0, 1.0);
    vec2 step = 1.0 / tsAtlas;
    for (int x = -1; x <= 1; x++) {
        for (int y = -1; y <= 1; y++) {
            vec2 uv = tsUv + vec2(x, y) * step;
            if (!ts_solid(uv)) {
                continue;
            }
            vec3 v = vec3(fract(uv * tsAtlas), 0.0);
            if (x == 0) v.x = 0.0;
            if (y == 0) v.y = 0.0;
            if (x > 0) v.x = 1.0 - v.x;
            if (y > 0) v.y = 1.0 - v.y;
            v.z = length(v.xy);
            if (v.z < best.z) {
                best = v;
            }
        }
    }
    return best;
}

void ts_own_shadow(vec3 c) {
    if (!tsIsShadow && ts_solid(tsUv - 1.0 / tsAtlas)) {
        tsBack = vec4(c * 0.25, 1.0);
    }
}

void fx_wave(float speed) {
    tsUv.y += sin(tsChar.x * 0.1 - tsT * 7500.0 * speed) / tsAtlas.y;
}

void fx_shake(float k) {
    float nx = ts_noise(tsChar.x + tsChar.y + tsT * 32000.0) - 0.5;
    float ny = ts_noise(tsChar.x - tsChar.y + tsT * 32000.0) - 0.5;
    tsUv += k * vec2(nx, ny) / tsAtlas;
}

void fx_jump(float speed, float space) {
    float x = mod(tsChar.x * 0.4 - tsT * 18000.0 * speed, 5.0 * space * TS_TAU);
    x = min(x, TS_TAU);
    tsUv.y += (0.5 - 0.5 * cos(x)) / tsAtlas.y;
}

void fx_flip(float speed, float space) {
    float t = min(mod((tsChar.x * 0.4 - tsT * 18000.0 * speed) / TS_TAU, 5.0 * space), 1.0);
    tsUv.x = tsUvMid.x + (tsUv.x - tsUvMid.x) / cos(TS_TAU * t);
    tsUv.y = tsUvMid.y + (tsUv.y - tsUvMid.y) / (1.0 + 0.1 * sin(TS_TAU * t));
}

void fx_skew(float speed) {
    float t = tsT * 1600.0 * speed;
    tsUv.x = mix(tsUv.x, tsUv.x + sin(TS_TAU * t * 0.5) / tsAtlas.x, 1.0 - tsLocal.y);
    tsUv.y = mix(tsUv.y, tsUvMax.y, -(0.3 + 0.5 * cos(TS_TAU * t)));
}

void fx_scale(float s) {
    vec2 anchor = tsUvMid + vec2(0.0, 5.0 / tsAtlas.y);
    tsUv = (tsUv - anchor) / s + anchor;
}

void fx_pulse(float speed) {
    fx_scale(1.0 / (sin(tsT * 12800.0 * speed) * 0.15 + 0.85));
}

void fx_outline(vec3 c, float width) {
    if (tsIsShadow) {
        tsFill.rgb = c * 0.25;
        return;
    }
    vec2 step = width / tsAtlas;
    for (int x = -1; x <= 1; x++) {
        for (int y = -1; y <= 1; y++) {
            if ((x != 0 || y != 0) && ts_solid(tsUv + vec2(x, y) * step)) {
                tsBack = vec4(c, 1.0);
                return;
            }
        }
    }
}

void fx_gradient(vec3 a, vec3 b) {
    ts_set(mix(a, b, (tsUv.y - tsUvMin.y) / (tsUvMax.y - tsUvMin.y)));
}

void fx_rainbow() {
    ts_set(ts_hsv(vec3(0.005 * (tsPos.x - tsPos.y) - tsT * 300.0, 0.8, 1.0)));
}

void fx_shimmer(float speed, float amount) {
    if (tsIsShadow) {
        return;
    }
    float f = tsLocal.x + tsLocal.y - tsT * 6400.0 * speed;
    if (mod(f, 5.0) < 0.75) {
        tsFill.rgb = mix(tsFill.rgb, vec3(1.0), amount);
    }
}

void fx_chroma() {
    float nx = ts_noise(tsT * 12000.0) - 0.5;
    float ny = ts_noise(tsT * 12000.0 + 19732.134) - 0.5;
    vec2 off = vec2(0.5 / tsAtlas.x, 0.0) + vec2(0.5, 1.0) * vec2(nx, ny) / tsAtlas;
    vec2 a = tsUv + off;
    vec2 b = tsUv - off;
    vec4 ra = ts_outside(a) ? vec4(0.0) : texture(Sampler0, a);
    vec4 rb = ts_outside(b) ? vec4(0.0) : texture(Sampler0, b);
    ra.rgb *= ra.a;
    rb.rgb *= rb.a;
    tsBack = ra * vec4(1.0, 0.25, 0.0, 1.0) + rb * vec4(0.0, 0.75, 1.0, 1.0);
    tsBack.rgb *= tsFill.rgb;
}

void fx_metal(vec3 c) {
    int y = ts_row();
    ts_set(y > 3 ? c * 0.7 : (y == 3 ? c + 0.25 : c));
}

void fx_fire() {
    if (tsIsShadow) {
        return;
    }
    float h = fract(tsUv.y * tsAtlas.y);
    if (ts_solid(tsUv + vec2(0.0, 1.0 / tsAtlas.y))) {
        float f = ts_noise(tsLocal * 32.0 + vec2(0.0, tsT * 6400.0)) * 0.5 + 0.5;
        f -= (1.0 - sqrt(h)) * 0.8;
        if (f > 0.5) {
            tsBack = vec4(mix(vec3(1.0, 0.2, 0.2), vec3(1.0, 0.7, 0.3), (f - 0.5) / 0.5), 1.0);
        }
    }
}

void fx_fade(vec3 c) {
    if (tsIsShadow) {
        c *= 0.25;
    }
    tsFill.rgb = mix(tsFill.rgb, c, sin(tsT * 1200.0 * TS_PI) * 0.5 + 0.5);
}

void fx_blink(float speed) {
    if (sin(tsT * 3200.0 * speed * TS_PI) < 0.0) {
        tsFill.a = 0.0;
        tsBack.a = 0.0;
        tsTop.a = 0.0;
    }
}

void fx_glow(vec3 c, float k) {
    if (tsIsShadow) {
        tsFill = vec4(0.0);
    }
    vec3 d = ts_field();
    tsBack = vec4(c, k * (1.0 - d.z) * (1.0 - d.z));
}

void fx_flag(vec3 a, vec3 b, vec3 c, vec3 d, vec3 e, int lo) {
    int y = ts_row();
    ts_set(y < lo ? a : (y == lo ? b : (y == lo + 1 ? c : (y == lo + 2 ? d : e))));
}

void fx_pride() {
    int y = ts_row();
    vec3 c = vec3(0.459, 0.027, 0.529);
    if (y < 2) c = vec3(1.0, 0.012, 0.012);
    else if (y == 2) c = vec3(1.0, 0.549, 0.0);
    else if (y == 3) c = vec3(1.0, 0.929, 0.0);
    else if (y == 4) c = vec3(0.0, 0.502, 0.149);
    else if (y == 5) c = vec3(0.0, 0.302, 1.0);
    ts_set(c);
}

void fx_bands3(vec3 a, vec3 b, vec3 c, int lo, int hi) {
    int y = ts_row();
    ts_set(y < lo ? a : (y <= hi ? b : c));
}

void fx_ace() {
    int y = ts_row();
    ts_set(y < 3 ? vec3(0.1) : (y == 3 ? vec3(0.639) : (y == 4 ? vec3(1.0) : vec3(0.502, 0.0, 0.502))));
}

void fx_enby() {
    int y = ts_row();
    ts_set(y < 3 ? vec3(1.0, 0.957, 0.173) : (y == 3 ? vec3(1.0) : (y == 4 ? vec3(0.616, 0.349, 0.824) : vec3(0.1))));
}

void fx_neon(vec3 c) {
    float r = ts_hash(floor(tsT * 24000.0) + ts_cell() * 0.37);
    ts_set(mix(c, vec3(1.0), 0.35));
    fx_glow(c, r > 0.93 ? 0.25 : 1.0);
    if (r > 0.97) {
        tsFill.rgb *= 0.4;
    }
}

void fx_glitch(vec3 c) {
    float tick = floor(tsT * 24000.0);
    float r = ts_hash(vec2(tick, ts_cell()));
    if (r > 0.8) {
        tsUv.x += (ts_hash(vec2(tick, float(ts_row()))) - 0.5) * 2.0 / tsAtlas.x;
    }
    ts_set(c);
    if (r > 0.9) {
        fx_chroma();
    }
}

void fx_ice(vec3 c) {
    fx_gradient(vec3(1.0), c);
    if (!tsIsShadow && ts_hash(floor(tsPos) + floor(tsT * 8000.0)) > 0.96) {
        tsFill.rgb = vec3(1.0);
    }
}

void fx_holo(vec3 c) {
    ts_set(c);
    float scan = step(0.5, fract(-tsPos.y * 0.5 - tsT * 3000.0));
    tsFill.a *= 0.55 + 0.35 * scan;
    if (ts_hash(floor(tsT * 20000.0)) > 0.95) {
        tsUv.x += 1.0 / tsAtlas.x;
    }
    ts_no_shadow();
}

void fx_type(vec3 c) {
    ts_set(c);
    ts_no_shadow();
    float n = mod(tsT * 8400.0, 72.0);
    if (tsIndex >= n) {
        tsFill.a = 0.0;
        float bar = max(0.15, 1.0 / max((tsUvMax.x - tsUvMin.x) * tsAtlas.x, 1.0));
        if (floor(n) + 1.0 == tsIndex && tsLocal.x >= 0.0 && tsLocal.x < bar && tsLocal.y >= 0.0 && tsLocal.y <= 1.0) {
            tsFill.a = 1.0;
            tsBack = vec4(1.0);
            tsLookup = false;
        }
    }
}

void fx_heart(vec3 c) {
    float p = fract(tsT * 1000.0);
    float beat = exp(-pow((p - 0.05) * 22.0, 2.0)) + 0.6 * exp(-pow((p - 0.22) * 22.0, 2.0));
    fx_scale(1.0 + 0.18 * beat);
    ts_set(mix(c, vec3(1.0), 0.25 * beat));
}

void fx_gold() {
    fx_metal(vec3(1.0, 0.78, 0.25));
    if (!tsIsShadow && fract((tsPos.x - tsPos.y) * 0.02 - tsT * 900.0) < 0.08) {
        tsFill.rgb = mix(tsFill.rgb, vec3(1.0, 1.0, 0.85), 0.7);
    }
}

void fx_galaxy() {
    float n = ts_noise(tsPos * 0.25 + vec2(tsT * 400.0, 0.0));
    vec3 c = mix(vec3(0.25, 0.05, 0.45), vec3(0.15, 0.35, 0.95), n);
    float star = ts_hash(floor(tsPos));
    if (star > 0.93) {
        c = vec3(0.6 + 0.4 * sin(tsT * 30000.0 + star * 50.0));
    }
    ts_set(c);
}

void fx_swing(vec3 c) {
    float a = sin(tsT * 9000.0 + tsChar.x * 0.3) * 0.35;
    vec2 pivot = vec2(tsUvMid.x, tsUvMin.y) * tsAtlas;
    vec2 q = tsUv * tsAtlas - pivot;
    q = vec2(cos(a) * q.x + sin(a) * q.y, -sin(a) * q.x + cos(a) * q.y);
    tsUv = (q + pivot) / tsAtlas;
    ts_set(c);
}

void fx_echo(vec3 c) {
    ts_set(c);
    if (tsIsShadow) {
        return;
    }
    vec4 acc = vec4(0.0);
    for (int i = 1; i <= 3; i++) {
        vec2 off = vec2(float(i) * (0.7 + 0.5 * sin(tsT * 6000.0)), 0.0) / tsAtlas;
        if (ts_solid(tsUv - off)) {
            acc = max(acc, vec4(c * 0.6, 0.6 - 0.15 * float(i)));
        }
    }
    tsBack = acc;
}

void fx_jeb() {
    ts_set(ts_hsv(vec3(ts_hash(ts_cell() + 0.5), 0.75, 1.0)));
}

void fx_toxic() {
    float f = ts_noise(tsPos * vec2(0.3, -0.3) + vec2(0.0, tsT * 3000.0));
    ts_set(mix(vec3(0.3, 1.0, 0.2), vec3(0.75, 1.0, 0.1), f));
    fx_wave(0.5);
}

void fx_vampire() {
    ts_set(vec3(0.62, 0.02, 0.05));
    if (tsIsShadow) {
        return;
    }
    float len = mod(tsT * 3000.0 + ts_hash(ts_cell() * 1.3) * 3.0, 3.0);
    for (int i = 1; i <= 3; i++) {
        vec2 uv = tsUv - vec2(0.0, float(i) / tsAtlas.y);
        if (float(i) <= len && ts_solid(uv) && ts_hash(floor(tsUv.x * tsAtlas.x)) > 0.55) {
            tsBack = vec4(0.45, 0.0, 0.03, 1.0);
        }
    }
}

void fx_ghost(vec3 c) {
    float ph = sin(tsT * 5000.0 + tsChar.x * 0.25);
    tsUv.y += ph * 0.6 / tsAtlas.y;
    ts_set(c);
    tsFill.a *= 0.45 + 0.4 * (ph * 0.5 + 0.5);
    ts_no_shadow();
}

void fx_lava() {
    float f = ts_noise(tsPos * vec2(0.35, -0.35) + vec2(tsT * 900.0, tsT * 2500.0));
    ts_set(mix(vec3(0.85, 0.1, 0.0), vec3(1.0, 0.75, 0.15), f));
}

void fx_hue() {
    ts_set(ts_hsv(vec3(ts_cell() * 0.07 - tsT * 300.0, 0.7, 1.0)));
}

void fx_sparkle(vec3 c) {
    ts_set(c);
    if (tsIsShadow) {
        return;
    }
    vec2 px = floor(tsPos) + 0.5;
    vec2 home = floor(px / 6.0);
    float glint = 0.0;
    for (int gx = -1; gx <= 1; gx++) {
        for (int gy = -1; gy <= 1; gy++) {
            vec2 cell = home + vec2(gx, gy);
            float r = ts_hash(cell);
            if (r < 0.62) {
                continue;
            }
            float life = fract(tsSec * 0.55 + r * 7.31);
            if (life > 0.32) {
                continue;
            }
            float k = sin(life / 0.32 * TS_PI);
            vec2 centre = cell * 6.0 + floor(vec2(ts_hash(cell + 3.1), ts_hash(cell + 7.7)) * 6.0) + 0.5;
            vec2 d = abs(px - centre);
            float arm = 0.4 + 2.2 * k;
            float v = 0.0;
            if (d.x < 0.1 && d.y <= arm) {
                v = 1.0 - d.y / (arm + 1.0);
            }
            if (d.y < 0.1 && d.x <= arm) {
                v = max(v, 1.0 - d.x / (arm + 1.0));
            }
            if (k > 0.7 && d.x > 0.9 && d.x < 1.1 && d.y > 0.9 && d.y < 1.1) {
                v = max(v, 0.45);
            }
            glint = max(glint, v * k);
        }
    }
    if (glint > 0.0) {
        tsTop = vec4(mix(c, vec3(1.0, 1.0, 0.92), 0.85), min(1.0, glint * 1.4));
    }
}

void fx_matrix() {
    float cell = ts_cell();
    float r = ts_hash(vec2(cell, 17.0));
    float span = 13.0 + floor(r * 6.0);
    float head = mod(tsSec * (9.0 + r * 5.0) + r * 40.0, span);
    float dist = head - float(ts_row());
    float trail = dist >= 0.0 && dist < 6.0 ? 1.0 - dist / 6.0 : 0.0;
    vec3 c = mix(vec3(0.12, 0.66, 0.27), vec3(0.45, 1.0, 0.55), trail);
    if (dist >= 0.0 && dist < 1.0) {
        c = vec3(0.9, 1.0, 0.92);
    }
    if (ts_hash(vec2(floor(tsSec * 10.0), cell)) > 0.95) {
        c = mix(c, vec3(0.8, 1.0, 0.8), 0.6);
    }
    ts_set(c);
    if (tsIsShadow) {
        tsFill.rgb = vec3(0.0, 0.1, 0.03);
    }
}

void fx_frost() {
    float f = clamp((float(ts_row()) + 0.5) / max(tsSize.y, 1.0), 0.0, 1.0);
    vec3 c = mix(vec3(0.93, 0.98, 1.0), vec3(0.45, 0.72, 1.0), f);
    vec2 px = floor(tsPos);
    if (ts_hash(px * 0.73 + 4.1) > 0.78) {
        c *= 0.78;
    }
    ts_set(c);
    if (tsIsShadow) {
        tsFill.rgb = vec3(0.04, 0.1, 0.22);
        return;
    }
    if (ts_hash(px + floor(tsSec * 5.0) * 0.37) > 0.965) {
        tsFill.rgb = vec3(1.0);
    }
    if (ts_solid(tsUv)) {
        return;
    }
    float phase = fract(tsSec / 5.0);
    float grow = smoothstep(0.0, 0.45, phase) * (1.0 - smoothstep(0.82, 1.0, phase));
    bool up = ts_solid(ts_texel(vec2(0.0, 1.0)));
    bool side = ts_solid(ts_texel(vec2(1.0, 0.0))) || ts_solid(ts_texel(vec2(-1.0, 0.0)));
    float n = ts_hash(px * 1.31 + 9.7);
    if (up && !ts_solid(ts_texel(vec2(0.0, -1.0))) && n < 0.15 + 0.6 * grow) {
        tsBack = vec4(0.85, 0.96, 1.0, 0.95);
    } else if (side && n < 0.35 * grow) {
        tsBack = vec4(0.7, 0.88, 1.0, 0.55);
    }
}

void fx_electric() {
    float tick = floor(tsSec * 14.0);
    float flick = ts_hash(vec2(tick, ts_cell()));
    if (ts_hash(vec2(tick, 3.0)) > 0.86) {
        tsUv.x += (ts_hash(vec2(tick, ts_cell() + 1.0)) > 0.5 ? 1.0 : -1.0) / tsAtlas.x;
    }
    vec3 core = mix(vec3(0.35, 0.78, 1.0), vec3(1.0), flick > 0.82 ? 0.9 : 0.25 + 0.2 * flick);
    ts_set(core);
    if (tsIsShadow) {
        tsFill.rgb = vec3(0.02, 0.08, 0.25);
        return;
    }
    vec2 px = floor(tsPos);
    if (!ts_solid(tsUv) && ts_near(1) <= 1.0 && ts_hash(px * 0.91 + tick * 1.7) > 0.88) {
        tsBack = vec4(0.65, 0.92, 1.0, 0.8);
    }
    float burst = ts_hash(vec2(floor(tsSec * 4.0), 9.0));
    if (burst > 0.8 && !ts_solid(tsUv)) {
        float seed = floor(tsSec * 3.0) * 7.3 + tick * 0.61;
        float y0 = 1.0 + ts_noise(px.x * 0.55 + seed) * (tsSize.y - 1.0);
        float y1 = 1.0 + ts_noise((px.x + 1.0) * 0.55 + seed) * (tsSize.y - 1.0);
        float py = tsLocal.y * tsSize.y;
        if (py >= min(y0, y1) - 0.5 && py <= max(y0, y1) + 0.5) {
            tsBack = vec4(0.8, 0.95, 1.0, 0.9);
        }
    }
}

void fx_sunset() {
    float f = clamp((float(ts_row()) + 0.5) / max(tsSize.y, 1.0), 0.0, 1.0);
    f = clamp(f * 1.3 - 0.15 + 0.25 * sin(tsPos.x * 0.09 - tsSec * 1.6), 0.0, 1.0);
    vec3 top = vec3(1.0, 0.95, 0.5);
    vec3 mid = vec3(1.0, 0.5, 0.18);
    vec3 low = vec3(0.86, 0.2, 0.62);
    ts_set(f < 0.5 ? mix(top, mid, f * 2.0) : mix(mid, low, f * 2.0 - 1.0));
    if (tsIsShadow) {
        tsFill.rgb = vec3(0.2, 0.04, 0.18);
        return;
    }
    float g = fract((tsPos.x - tsPos.y * 0.5) * 0.012 - tsSec * 0.25);
    if (g < 0.05) {
        tsFill.rgb = mix(tsFill.rgb, vec3(1.0, 0.97, 0.85), 0.6);
    }
}

void fx_ocean() {
    tsUv.y += 0.5 * sin(tsChar.x * 0.18 - tsSec * 2.2) / tsAtlas.y;
    float py = float(ts_row()) + 0.5;
    float surf = 3.0 + 1.4 * sin(tsPos.x * 0.3 - tsSec * 2.6) + 0.6 * sin(tsPos.x * 0.85 + tsSec * 1.7);
    vec3 c;
    if (py < surf - 0.5) {
        c = vec3(0.55, 0.95, 1.0);
    } else if (py < surf + 0.7) {
        c = vec3(1.0);
    } else {
        c = mix(vec3(0.1, 0.55, 1.0), vec3(0.06, 0.22, 0.8), clamp((py - surf) / 4.0, 0.0, 1.0));
        c += 0.12 * ts_noise(tsPos * 0.6 + vec2(tsSec * 1.5, -tsSec));
    }
    ts_set(c);
    if (tsIsShadow) {
        tsFill.rgb = vec3(0.01, 0.06, 0.18);
    }
}

float fx_bounce_height(float t) {
    if (t < 0.0) {
        return 99.0;
    }
    if (t < 0.3) {
        float k = t / 0.3;
        return 9.0 * (1.0 - k * k);
    }
    t -= 0.3;
    if (t < 0.22) {
        float k = t / 0.22;
        return 9.0 * k * (1.0 - k);
    }
    t -= 0.22;
    if (t < 0.12) {
        float k = t / 0.12;
        return 3.0 * k * (1.0 - k);
    }
    return 0.0;
}

void fx_bounce(vec3 c) {
    ts_set(c);
    if (tsIsShadow) {
        tsFill.a = 0.0;
        return;
    }
    float cycle = mod(tsSec, 7.0);
    float h = fx_bounce_height(cycle - 0.2 - ts_letter() * 0.07);
    if (h > 50.0) {
        tsFill.a = 0.0;
        return;
    }
    if (cycle > 6.6) {
        tsFill.a *= (7.0 - cycle) / 0.4;
    }
    tsUv.y += floor(h + 0.5) / tsAtlas.y;
    ts_own_shadow(c);
}

void fx_scramble(vec3 c) {
    if (tsIsShadow) {
        tsFill.a = 0.0;
        return;
    }
    float cycle = mod(tsSec, 7.0);
    float reveal = 0.4 + ts_letter() * 0.06;
    if (cycle < reveal) {
        tsLookup = false;
        vec2 cell = floor(tsLocal * tsSize);
        bool inside = tsLocal.x >= 0.0 && tsLocal.y >= 0.0 && tsLocal.x < 1.0 && tsLocal.y < 1.0 && cell.y >= 1.0 && cell.y < tsSize.y - 1.0;
        float n = ts_hash(cell * vec2(1.7, 2.3) + ts_letter() * 13.1 + floor(tsSec * 14.0) * 0.71);
        if (inside && n > 0.5) {
            tsBack = vec4(mix(c, vec3(0.1), 0.35), 1.0);
        }
        return;
    }
    float since = cycle - reveal;
    ts_set(since < 0.25 ? mix(vec3(1.0), c, since / 0.25) : c);
    ts_own_shadow(c);
}

void fx_extrude(vec3 c) {
    if (tsIsShadow) {
        tsFill.a = 0.0;
        return;
    }
    fx_gradient(mix(c, vec3(1.0), 0.35), c);
    float a = 0.785 + 0.55 * sin(tsSec * 1.3);
    vec2 dir = vec2(cos(a), sin(a));
    for (int k = 1; k <= 4; k++) {
        if (ts_solid(tsUv - dir * float(k) * 0.8 / tsAtlas)) {
            tsBack = vec4(c * (0.62 - 0.1 * float(k)), 1.0);
            break;
        }
    }
}

void fx_sweep(vec3 c) {
    ts_set(c);
    if (tsIsShadow) {
        return;
    }
    float f = mod(tsPos.x - tsSec * 70.0, 160.0) / 160.0;
    float b = f > 0.7 ? (f - 0.7) / 0.3 : 0.0;
    b = b * b;
    if (ts_solid(tsUv)) {
        tsFill.rgb = mix(c, vec3(1.0), 0.75 * b);
    }
    float py = tsLocal.y * tsSize.y;
    float px = tsLocal.x * tsSize.x;
    if (py >= tsSize.y && py < tsSize.y + 1.0 && px > -2.5 && px < tsSize.x + 2.5) {
        tsBack = vec4(mix(c * 0.55, vec3(1.0), b), 0.55 + 0.45 * b);
    }
}

void ts_apply(int id, vec3 p) {
    vec3 w = vec3(1.0);
    if (id == 0) { fx_shake(1.0); ts_set(p); }
    else if (id == 1) { fx_wave(1.0); ts_set(p); }
    else if (id == 2) { fx_jump(1.0, 1.0); ts_set(p); }
    else if (id == 3) { fx_flip(1.0, 1.0); ts_set(p); }
    else if (id == 4) { fx_skew(1.0); ts_set(p); }
    else if (id == 5) { fx_pulse(1.0); ts_set(p); }
    else if (id == 6) { fx_rainbow(); }
    else if (id == 7) { fx_gradient(p, mix(p, w, 0.7)); }
    else if (id == 8) { ts_set(p); fx_shimmer(1.0, 0.5); }
    else if (id == 9) { ts_set(p); fx_chroma(); ts_no_shadow(); }
    else if (id == 10) { fx_metal(p); }
    else if (id == 11) { ts_set(p); fx_fire(); }
    else if (id == 12) { ts_set(w); fx_fade(p); }
    else if (id == 13) { ts_set(p); fx_blink(1.0); }
    else if (id == 14) { ts_set(w); fx_glow(p, 1.0); }
    else if (id == 15) { ts_set(w); fx_outline(p, 1.0); }
    else if (id == 16) { ts_set(w); fx_outline(p, 0.5); }
    else if (id == 17) { fx_pride(); }
    else if (id == 18) { fx_flag(vec3(0.839, 0.161, 0.0), vec3(1.0, 0.608, 0.333), w, vec3(0.831, 0.384, 0.647), vec3(0.647, 0.0, 0.384), 3); }
    else if (id == 19) { fx_flag(vec3(0.0, 0.584, 0.525), vec3(0.412, 0.941, 0.686), w, vec3(0.510, 0.698, 1.0), vec3(0.271, 0.153, 0.627), 3); }
    else if (id == 20) { fx_bands3(vec3(0.843, 0.0, 0.443), vec3(0.612, 0.306, 0.592), vec3(0.0, 0.208, 0.663), 4, 4); }
    else if (id == 21) { fx_flag(vec3(0.357, 0.812, 0.980), vec3(0.961, 0.671, 0.725), w, vec3(0.961, 0.671, 0.725), vec3(0.357, 0.812, 0.980), 3); }
    else if (id == 22) { fx_bands3(vec3(1.0, 0.129, 0.549), vec3(1.0, 0.847, 0.0), vec3(0.129, 0.694, 1.0), 3, 4); }
    else if (id == 23) { fx_ace(); }
    else if (id == 24) { fx_flag(vec3(0.047, 0.655, 0.294), vec3(0.584, 0.796, 0.451), w, vec3(0.639), vec3(0.1), 3); }
    else if (id == 25) { fx_enby(); }
    else if (id == 26) { fx_wave(1.0); fx_rainbow(); }
    else if (id == 27) { fx_neon(p); }
    else if (id == 28) { fx_glitch(p); }
    else if (id == 29) { fx_ice(p); }
    else if (id == 30) { fx_holo(p); }
    else if (id == 31) { fx_type(p); }
    else if (id == 32) { fx_heart(p); }
    else if (id == 33) { fx_gold(); }
    else if (id == 34) { fx_galaxy(); }
    else if (id == 35) { fx_shake(3.0); ts_set(p); }
    else if (id == 36) { fx_swing(p); }
    else if (id == 37) { fx_echo(p); }
    else if (id == 38) { fx_jeb(); }
    else if (id == 39) { fx_toxic(); }
    else if (id == 40) { fx_vampire(); }
    else if (id == 41) { fx_ghost(p); }
    else if (id == 42) { fx_lava(); }
    else if (id == 43) { fx_hue(); }
    else if (id == 44) { fx_wave(1.0); ts_set(w); fx_outline(p, 1.0); }
    else if (id == 45) { tsFill.a = 0.0; }
    else if (id == 46) { fx_sparkle(p); }
    else if (id == 47) { fx_matrix(); }
    else if (id == 48) { fx_frost(); }
    else if (id == 49) { fx_electric(); }
    else if (id == 50) { fx_sunset(); }
    else if (id == 51) { fx_ocean(); }
    else if (id == 56) { fx_bounce(p); }
    else if (id == 57) { fx_scramble(p); }
    else if (id == 58) { fx_extrude(p); }
    else if (id == 59) { fx_sweep(p); }
    else { ts_set(p); }
}
