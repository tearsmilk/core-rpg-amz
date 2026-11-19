#moj_import <minecraft:globals.glsl>

in vec3 csPos;
in vec2 csLocal;
in vec4 csTint;

const vec2 CS_PANO = vec2(1024.0, 512.0);
const vec2 CS_GLOWPX = vec2(16.0, 16.0);
const float CS_TAU = 6.28318530718;
const float CS_PI = 3.14159265359;
const float CS_HORIZON = -0.45;
const int CS_KINDS = 12;
const vec3 CS_SUN = vec3(-0.17196, 0.13917, -0.97522);

const vec3 CS_GLOW[12] = vec3[](
    vec3(0.80, 0.95, 1.00),
    vec3(0.70, 0.45, 1.00),
    vec3(1.00, 0.45, 0.12),
    vec3(0.35, 1.00, 0.60),
    vec3(0.20, 0.85, 0.80),
    vec3(1.00, 0.62, 0.25),
    vec3(1.00, 0.32, 0.08),
    vec3(0.95, 0.22, 0.45),
    vec3(1.00, 0.92, 0.65),
    vec3(0.55, 0.95, 0.45),
    vec3(0.62, 0.72, 0.86),
    vec3(0.35, 0.95, 0.90)
);

bool cs_is(vec4 t, vec4 ref) {
    vec4 d = abs(t * 255.0 - ref);
    return d.r < 0.5 && d.g < 0.5 && d.b < 0.5 && d.a < 0.5;
}

int cs_kind(sampler2D atlas, vec2 uv, vec2 spritePx, out ivec2 p0) {
    ivec2 size = textureSize(atlas, 0);
    vec2 as = vec2(size);
    vec2 uvMin = uv - csLocal * (spritePx / as);
    p0 = clamp(ivec2(floor(uvMin * as + 0.5)), ivec2(0), size - ivec2(2, 1));
    if (!cs_is(texelFetch(atlas, p0, 0), vec4(61.0, 203.0, 147.0, 3.0))) return 0;
    vec4 k = texelFetch(atlas, p0 + ivec2(1, 0), 0) * 255.0;
    int kind = int(floor(k.r + 0.5));
    if (!cs_is(k / 255.0, vec4(float(kind), 147.0, 203.0, 3.0))) return 0;
    return kind;
}

vec3 cs_texel(sampler2D atlas, ivec2 p0, int x, int y) {
    int xx = x - 1024 * int(floor(float(x) / 1024.0));
    int yy = clamp(y, 1, 511);
    return texelFetch(atlas, p0 + ivec2(xx, yy), 0).rgb;
}

vec3 cs_pano(sampler2D atlas, ivec2 p0, vec2 q) {
    float fx = q.x * 1024.0 - 0.5;
    float fy = 1.0 + clamp(q.y, 0.0, 1.0) * 510.0;
    float ix = floor(fx);
    float iy = floor(fy);
    vec2 f = vec2(fx - ix, fy - iy);
    int x0 = int(ix);
    int y0 = int(iy);
    vec3 a = cs_texel(atlas, p0, x0, y0);
    vec3 b = cs_texel(atlas, p0, x0 + 1, y0);
    vec3 c = cs_texel(atlas, p0, x0, y0 + 1);
    vec3 d = cs_texel(atlas, p0, x0 + 1, y0 + 1);
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

float cs_hash(vec3 p) {
    return fract(sin(dot(p, vec3(12.9898, 78.233, 37.719))) * 43758.5453);
}

float cs_edge(vec2 l) {
    vec2 e = min(l, 1.0 - l);
    return min(e.x, e.y);
}

vec4 cs_window(sampler2D atlas, ivec2 p0, int kind) {
    float t = GameTime * 1200.0;
    vec3 d = normalize(csPos);
    float el = asin(clamp(d.y, -1.0, 1.0));
    float elm = (el >= CS_HORIZON) ? (el - CS_HORIZON) / (0.5 * CS_PI - CS_HORIZON) : (el - CS_HORIZON) / (0.5 * CS_PI + CS_HORIZON);
    vec2 q = vec2(atan(d.z, d.x) / CS_TAU + 0.5, 0.5 - 0.5 * elm);
    float skyW = 1.0 - smoothstep(0.47, 0.5, q.y);
    float seaW = smoothstep(0.5, 0.53, q.y);
    vec2 qs = q;
    if (kind == 1 || kind == 10) {
        qs.x = fract(q.x + 2.0 * GameTime);
    } else if (kind == 9) {
        qs.x = fract(q.x + GameTime);
    }
    if (kind == 1 || kind == 6 || kind == 11 || kind == 12) {
        q.y += 0.0012 * sin(CS_TAU * (q.x * 120.0 + t * 0.25)) * sin(CS_TAU * (q.y * 60.0 - t * 0.2)) * seaW;
    } else if (kind == 2) {
        q.x += GameTime;
    } else if (kind == 3) {
        q.y += 0.0015 * sin(CS_TAU * (q.x * 40.0 + t * 0.5)) * seaW;
        q.x += 0.004 * sin(CS_TAU * (q.y * 8.0 - t * 0.05)) * skyW;
    } else if (kind == 4) {
        q.x += 0.004 * sin(CS_TAU * (q.y * 6.0 + t * 0.1) + q.x * CS_TAU * 8.0) * skyW;
    } else if (kind == 5) {
        q.x += 2.0 * GameTime;
        q.y += 0.01 * sin(CS_TAU * GameTime);
    }
    q.x = fract(q.x);
    vec3 col = cs_pano(atlas, p0, q);
    if (kind == 1 || kind == 9 || kind == 10) {
        float cw = (kind == 1) ? 1.0 - smoothstep(0.482, 0.497, q.y) : (kind == 9) ? smoothstep(0.515, 0.53, q.y) : 1.0 - smoothstep(0.401, 0.414, q.y);
        if (cw > 0.0) col = mix(col, cs_pano(atlas, p0, qs), cw);
    }
    if (kind == 1) {
        float sd = dot(d, CS_SUN);
        float above = smoothstep(CS_HORIZON - 0.01, CS_HORIZON + 0.03, el);
        float clear = smoothstep(0.2, 0.45, col.b - col.r) * above;
        float halo = exp((sd - 1.0) * 1000.0) * 0.6 + exp((sd - 1.0) * 90.0) * 0.25 + exp((sd - 1.0) * 9.0) * 0.12;
        col = mix(col, vec3(1.0, 0.98, 0.9), smoothstep(0.99897, 0.99922, sd) * clear);
        col += vec3(1.0, 0.92, 0.75) * halo * (0.35 + 0.65 * clear) * above;
    }
    float lum = max(col.r, max(col.g, col.b));
    if (kind == 2 || kind == 4 || kind == 5) {
        float h = cs_hash(vec3(floor(q * CS_PANO), float(kind)));
        float tw = sin(CS_TAU * (t * 0.5 + h));
        float starW = (kind == 4) ? smoothstep(0.4, 0.9, lum) * skyW * (1.0 - smoothstep(0.1, 0.5, col.g - max(col.r, col.b))) : smoothstep(0.3, 0.9, lum);
        col *= 1.0 + 0.4 * tw * starW;
    }
    if (kind == 3) {
        float hot = smoothstep(0.55, 1.0, col.r) * seaW;
        col *= 1.0 + 0.18 * hot * sin(CS_TAU * (t * 0.2) + q.x * 40.0 + q.y * 25.0);
    }
    if (kind == 6) {
        float h = cs_hash(vec3(floor(q * CS_PANO), 6.0));
        col *= 1.0 + 0.45 * sin(CS_TAU * (t * 0.7 + h)) * smoothstep(0.6, 1.0, lum) * seaW;
    }
    if (kind == 7 || kind == 8) {
        float hot = smoothstep(0.55, 1.0, col.r) * smoothstep(0.3, 0.6, col.r - col.b);
        col *= 1.0 + 0.2 * hot * sin(CS_TAU * (t * 0.25 + q.x * 8.0) + q.y * 30.0);
    }
    if (kind == 8) {
        float h = cs_hash(vec3(floor(q * CS_PANO), 8.0));
        float starW = smoothstep(0.3, 0.9, lum) * skyW * (1.0 - smoothstep(0.1, 0.4, col.r - col.b));
        col *= 1.0 + 0.4 * sin(CS_TAU * (t * 0.5 + h)) * starW;
    }
    if (kind == 11) {
        float rain = 0.0;
        for (int i = 0; i < 2; i++) {
            float fi = float(i);
            vec2 rp = vec2(q.x * (300.0 + 220.0 * fi) + q.y * 18.0, q.y * (22.0 + 12.0 * fi));
            float h = cs_hash(vec3(floor(rp.x), fi, 11.0));
            float fx = abs(fract(rp.x) - 0.5);
            float line = 1.0 - smoothstep(0.08, 0.2, fx);
            float y = fract(rp.y - t * (2.0 + floor(h * 2.0)) + h * 7.0);
            float drop = smoothstep(0.0, 0.06, y) * (1.0 - smoothstep(0.06, 0.4, y));
            rain += line * drop * step(0.4, h) * (0.16 - 0.07 * fi);
        }
        rain *= 1.0 - smoothstep(0.25, 0.42, abs(q.y - 0.5));
        col += vec3(0.72, 0.77, 0.84) * rain;
        float cyc = floor(t / 6.0);
        float fh = cs_hash(vec3(cyc, 3.0, 11.0));
        float ft = (t / 6.0 - cyc) * 6.0 - 1.0 - 3.0 * fh;
        float flash = step(0.7, fh) * (step(0.0, ft) * exp(-max(ft, 0.0) * 9.0) + 0.6 * step(0.22, ft) * exp(-max(ft - 0.22, 0.0) * 12.0));
        col += vec3(0.85, 0.9, 1.0) * flash * (0.45 + 0.55 * lum) * (0.4 + 0.6 * skyW);
    }
    if (kind == 4) {
        float green = clamp((col.g - max(col.r, col.b)) * 2.0, 0.0, 1.0) * skyW;
        col += col * green * 0.3 * sin(CS_TAU * (q.x * 5.0 - t * 0.05));
    }
    vec3 glow = CS_GLOW[kind - 1];
    float e = cs_edge(csLocal);
    float rim = exp(-e * 45.0) * 0.45 + exp(-e * 10.0) * 0.08;
    rim *= 0.85 + 0.15 * sin(CS_TAU * t * 0.5);
    col = col + glow * rim;
    return vec4(col * csTint.rgb, 1.0);
}

vec4 cs_frame(int kind) {
    float t = GameTime * 1200.0;
    vec2 p = abs(csLocal * 22.0 - 11.0) - 8.0;
    if (max(p.x, p.y) <= 0.0) return vec4(0.0);
    float dist = length(max(p, 0.0));
    float pulse = 0.85 + 0.15 * sin(CS_TAU * t * 0.5);
    float a = (exp(-dist / 0.9) * 0.75 + exp(-dist / 2.2) * 0.3) * pulse;
    a *= 1.0 - smoothstep(2.4, 3.0, dist);
    vec3 glow = CS_GLOW[kind - 17];
    vec3 col = mix(glow, vec3(1.0), exp(-dist / 0.5) * 0.5);
    return vec4(col * csTint.rgb, clamp(a, 0.0, 1.0));
}

bool cs_fragment(sampler2D atlas, vec2 uv, out vec4 color) {
    color = vec4(0.0);
    ivec2 p0;
    int kind = cs_kind(atlas, uv, CS_PANO, p0);
    if (kind >= 1 && kind <= CS_KINDS) {
        color = cs_window(atlas, p0, kind);
        return true;
    }
    kind = cs_kind(atlas, uv, CS_GLOWPX, p0);
    if (kind >= 17 && kind <= 16 + CS_KINDS) {
        color = cs_frame(kind);
        return true;
    }
    return false;
}
