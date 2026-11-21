#version 330

#moj_import <minecraft:globals.glsl>
#moj_import <worldshader:ws_data.glsl>

#define WS_PI 3.14159265
#define WS_MAX_PINCH 2.1
#define WS_MAX_SWIRL 1.5
#define WS_CORE 0.2
#define WS_TRAIL 6
#define WS_OVERSHOOT 1.4
#define WS_TAU 6.28318531

uniform sampler2D MainSampler;
uniform sampler2D MainDepthSampler;
uniform sampler2D DataSampler;

layout(std140) uniform WsConfig {
    float OutlineMode;
};

in vec2 texCoord;

out vec4 fragColor;

ivec2 wsScreen;

ivec4 ws_texel(int copy, int x, int y) {
    return ivec4(round(texelFetch(DataSampler, ws_origin(copy, y, wsScreen) + ivec2(x, 0), 0) * 255.0));
}

float ws_read(int copy, int x, int y, inout bool bad) {
    ivec4 t = ws_texel(copy, x, y);
    float v = ws_float(t.rgb);
    bad = bad || t.a != 0 || isnan(v) || isinf(v);
    return v;
}

bool ws_row(int copy, int s) {
    ivec4 tag = ivec4(ws_tag(s), 0);
    return ws_texel(copy, WS_TAG_A, s) == tag && ws_texel(copy, WS_TAG_B, s) == tag;
}

bool ws_mask_fill(ivec2 px, out vec4 color) {
    for (int k = 0; k < WS_COPIES; k++) {
        ivec2 o = ws_origin(k, 0, wsScreen);
        int slot = k == 0 ? px.y : wsScreen.y - 1 - px.y;
        if (px.x < o.x || px.x >= o.x + WS_ROW_PIXELS || slot < 0 || slot >= WS_SLOTS) {
            continue;
        }
        color = texelFetch(MainSampler, px, 0);
        if (color.a == 0.0 && ws_row(k, slot)) {
            vec4 up = texelFetch(MainSampler, ivec2(px.x, k == 0 ? WS_SLOTS : wsScreen.y - 1 - WS_SLOTS), 0);
            vec4 side = texelFetch(MainSampler, ivec2(k == 0 ? WS_ROW_PIXELS : o.x - 1, px.y), 0);
            color = up.a > 0.0 ? up : side.a > 0.0 ? side : vec4(0.0);
        }
        return true;
    }
    return false;
}

float ws_phase(float cycles) {
    return fract(GameTime * cycles) * WS_TAU;
}

vec3 ws_view(mat4 inv, vec2 uv, float depth) {
    vec4 p = inv * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    return p.xyz / p.w;
}

vec3 ws_rotate(vec3 v, vec3 axis, float a) {
    float c = cos(a);
    float s = sin(a);
    return v * c + cross(axis, v) * s + axis * dot(axis, v) * (1.0 - c);
}

vec3 ws_warp(vec3 d, vec3 axis, float pinch, float swirl) {
    float cosT = clamp(dot(d, axis), -1.0, 1.0);
    vec3 e = d - axis * cosT;
    float len = length(e);
    if (len > 1e-6) {
        float t = acos(cosT);
        float ts = t + pinch * t * (WS_PI - t) / WS_PI;
        d = axis * cos(ts) + (e / len) * sin(ts);
    }
    return ws_rotate(d, axis, swirl);
}

vec3 ws_apply(vec3 dir, int count, vec4 zc[WS_SLOTS], vec2 zw[WS_SLOTS], float zv[WS_SLOTS], float scale) {
    vec3 d = dir;
    for (int i = 0; i < count; i++) {
        if (zv[i] > 0.0) {
            d = ws_warp(d, normalize(zc[i].xyz), zw[i].x * scale, zw[i].y * scale);
        }
    }
    return d;
}

bool ws_onscreen(mat4 proj, vec3 d, float dist) {
    vec4 q = proj * vec4(d * dist, 1.0);
    if (q.w <= 1e-4) {
        return false;
    }
    vec2 n = q.xy / q.w;
    return abs(n.x) <= WS_OVERSHOOT && abs(n.y) <= WS_OVERSHOOT;
}

vec2 ws_mirror(vec2 uv) {
    vec2 m = mod(uv, 2.0);
    return clamp(mix(m, 2.0 - m, step(1.0, m)), vec2(0.0), vec2(1.0));
}

vec3 ws_tap(mat4 proj, vec3 d, float dist, vec3 fallback) {
    vec4 q = proj * vec4(d * dist, 1.0);
    if (q.w <= 1e-4) {
        return fallback;
    }
    return texture(MainSampler, ws_mirror(q.xy / q.w * 0.5 + 0.5)).rgb;
}

void main() {
    wsScreen = textureSize(DataSampler, 0);
    vec4 base = texture(MainSampler, texCoord);
    bool outline = OutlineMode > 0.5;
    vec4 filled;
    if (outline && ws_mask_fill(ivec2(gl_FragCoord.xy), filled)) {
        fragColor = filled;
        return;
    }
    vec4 zc[WS_SLOTS];
    vec2 zs[WS_SLOTS];
    int count = 0;
    int first = -1;
    int firstCopy = 0;
    for (int s = 0; s < WS_SLOTS; s++) {
        int cp = -1;
        ivec4 info = ivec4(0);
        vec3 center = vec3(0.0);
        for (int k = 0; k < WS_COPIES; k++) {
            if (!ws_row(k, s)) {
                continue;
            }
            info = ws_texel(k, WS_INFO, s);
            if (info.a != 0 || info.z != s || info.x < 1 || info.x > WS_MAX_RADIUS || info.y < 1 || info.y > 31) {
                continue;
            }
            bool bad = false;
            vec4 box = vec4(ws_read(k, WS_BOX, s, bad), ws_read(k, WS_BOX + 1, s, bad), ws_read(k, WS_BOX + 2, s, bad), ws_read(k, WS_BOX + 3, s, bad));
            if (bad) {
                continue;
            }
            if (any(lessThan(texCoord, box.xy)) || any(greaterThan(texCoord, box.zw))) {
                break;
            }
            center = vec3(ws_read(k, WS_CENTER, s, bad), ws_read(k, WS_CENTER + 1, s, bad), ws_read(k, WS_CENTER + 2, s, bad));
            if (bad) {
                continue;
            }
            cp = k;
            break;
        }
        if (cp < 0) {
            continue;
        }
        if (first < 0) {
            first = s;
            firstCopy = cp;
        }
        zc[count] = vec4(center, float(info.x));
        zs[count] = vec2(float(info.y) / 31.0, 0.0);
        count++;
    }
    if (count == 0) {
        fragColor = base;
        return;
    }

    mat4 proj;
    bool broken = true;
    for (int t = 0; t < WS_COPIES && broken; t++) {
        int k = (firstCopy + t) % WS_COPIES;
        broken = false;
        for (int i = 0; i < 16; i++) {
            proj[i / 4][i % 4] = ws_read(k, WS_PROJ + i, first, broken);
        }
    }
    if (broken || abs(determinant(proj)) < 1e-12) {
        fragColor = base;
        return;
    }
    mat4 inv = inverse(proj);

    vec3 p = ws_view(inv, texCoord, texture(MainDepthSampler, texCoord).r);
    float dist = length(p);
    vec3 dir = p / max(dist, 1e-4);

    float zx[WS_SLOTS];
    float zv[WS_SLOTS];
    bool hit = false;
    for (int i = 0; i < count; i++) {
        vec3 c = zc[i].xyz;
        float r = zc[i].w;
        float tc = max(dot(c, dir), 0.0);
        float perp = length(dir * tc - c);
        zx[i] = perp / r;
        zv[i] = 0.0;
        if (zx[i] < 1.0) {
            float chord = sqrt(r * r - perp * perp);
            float enter = tc - chord;
            zv[i] = enter <= 0.0 ? 1.0 : smoothstep(enter, enter + max(chord * 0.5, 0.5), dist);
        }
        hit = hit || zv[i] > 0.0;
    }
    if (!hit) {
        fragColor = base;
        return;
    }

    vec2 zw[WS_SLOTS];
    float dark = 0.0;
    float glow = 0.0;
    float best = 1.0;
    float flow = 0.0;
    vec3 focus = vec3(0.0, 0.0, -1.0);
    for (int i = 0; i < count; i++) {
        float x = zx[i];
        zw[i] = vec2(0.0);
        if (zv[i] <= 0.0) {
            continue;
        }
        vec3 c = zc[i].xyz;
        float str = zs[i].x * zv[i];
        float u = 1.0 - x;
        float inside = smoothstep(0.3, 1.0, length(c) / zc[i].w);
        float amp = str * mix(0.2, 1.0, inside);
        zw[i].x = WS_MAX_PINCH * amp * u * u * (1.0 + 0.05 * sin(x * 16.0 + ws_phase(764.0)));
        zw[i].y = WS_MAX_SWIRL * amp * u * u * (1.0 + 0.3 * sin(ws_phase(95.0)) + 0.6 * u * (1.0 + 0.25 * sin(ws_phase(248.0) + x * 6.0)));
        float core = 1.0 - smoothstep(WS_CORE * 0.25, WS_CORE, x);
        dark = max(dark, max(core * core * (0.6 + 0.4 * str), 0.4 * u * u * u * str) * inside);
        glow += exp(-pow((x - WS_CORE * 1.5) / 0.06, 2.0)) * str * inside;
        if (x < best) {
            best = x;
            focus = normalize(c);
            flow = u * u * amp;
        }
    }

    vec2 edge = min(texCoord, 1.0 - texCoord);
    float reach = smoothstep(0.0, 0.12, min(edge.x, edge.y));
    vec3 warped = ws_apply(dir, count, zc, zw, zv, reach);
    if (!ws_onscreen(proj, warped, dist)) {
        float lo = 0.0;
        float hi = reach;
        for (int b = 0; b < 6; b++) {
            float mid = (lo + hi) * 0.5;
            if (ws_onscreen(proj, ws_apply(dir, count, zc, zw, zv, mid), dist)) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        warped = ws_apply(dir, count, zc, zw, zv, lo);
    }
    if (outline) {
        vec4 qo = proj * vec4(warped * dist, 1.0);
        fragColor = qo.w > 1e-4 ? texture(MainSampler, ws_mirror(qo.xy / qo.w * 0.5 + 0.5)) : base;
        return;
    }

    vec3 axis = cross(focus, warped);
    axis = dot(axis, axis) > 1e-12 ? normalize(axis) : vec3(0.0, 1.0, 0.0);
    float ang = acos(clamp(dot(warped, focus), -1.0, 1.0));
    vec3 fallback = base.rgb;
    vec3 col = vec3(0.0);
    float total = 0.0;
    vec3 lead = fallback;
    for (int j = 0; j < WS_TRAIL; j++) {
        float f = float(j) / float(WS_TRAIL);
        float w = 1.0 - f * 0.7;
        vec3 t = ws_tap(proj, ws_rotate(warped, axis, ang * f * 0.32 * flow), dist, fallback);
        if (j == 0) {
            lead = t;
        }
        col += t * w;
        total += w;
    }
    col /= total;
    col.r += ws_tap(proj, ws_rotate(warped, axis, -ang * 0.035 * flow), dist, lead).r - lead.r;
    col.b += ws_tap(proj, ws_rotate(warped, axis, ang * 0.035 * flow), dist, lead).b - lead.b;
    col = clamp(col, 0.0, 1.0);
    col = col * (1.0 - 0.97 * dark) + vec3(0.55, 0.32, 1.0) * glow * 0.18;
    fragColor = vec4(col, base.a);
}
