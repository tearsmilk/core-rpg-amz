#version 330

#moj_import <minecraft:globals.glsl>

#define TH_TAU 6.2831853

const vec3 TH_DEEP = vec3(0.722, 0.165, 0.310);
const vec3 TH_WINE = vec3(0.910, 0.271, 0.420);
const vec3 TH_CRIMSON = vec3(1.000, 0.420, 0.557);
const vec3 TH_ROSE = vec3(1.000, 0.561, 0.671);
const vec3 TH_PINK = vec3(1.000, 0.702, 0.780);
const float TH_ALPHA = 0.84;

float th_phase(float cycles) {
    return GameTime * cycles;
}

float th_box(vec2 p, vec2 half_size, vec4 r) {
    float rad = p.x > 0.0 ? (p.y > 0.0 ? r.z : r.y) : (p.y > 0.0 ? r.w : r.x);
    vec2 q = abs(p) - half_size + rad;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - rad;
}

vec3 th_flow(vec2 g) {
    float a = sin(TH_TAU * ((g.x * 0.85 + g.y * 0.55) / 72.0 - th_phase(300.0)));
    float b = sin(TH_TAU * ((g.x * 0.35 - g.y * 0.9) / 46.0 + th_phase(220.0)) + 1.3);
    float m = clamp(0.5 + 0.34 * a + 0.16 * b, 0.0, 1.0);
    vec3 c = mix(TH_DEEP, TH_WINE, smoothstep(0.0, 0.55, m));
    c = mix(c, TH_CRIMSON, smoothstep(0.45, 1.0, m) * 0.85);
    float sweep = fract((g.x + g.y * 0.45) / 180.0 - th_phase(180.0));
    float band = exp(-pow((sweep - 0.5) * 7.0, 2.0));
    c = mix(c, TH_PINK, 0.34 * band);
    return c;
}

vec4 th_panel(vec2 g, vec2 lo, vec2 hi, vec4 radii, float px, vec4 open) {
    if (g.x < lo.x - px || g.y < lo.y - px || g.x > hi.x + px || g.y > hi.y + px) {
        return vec4(0.0);
    }
    if ((open.x > 0.0 && g.x < lo.x) || (open.y > 0.0 && g.y < lo.y) || (open.z > 0.0 && g.x > hi.x) || (open.w > 0.0 && g.y > hi.y)) {
        return vec4(0.0);
    }
    vec2 blo = lo - open.xy * 16.0;
    vec2 bhi = hi + open.zw * 16.0;
    float d = th_box(g - 0.5 * (blo + bhi), 0.5 * (bhi - blo), radii);
    float cover = clamp(0.5 - d / px, 0.0, 1.0);
    if (cover <= 0.0) {
        return vec4(0.0);
    }
    vec3 c = th_flow(g);
    float rim_w = max(px, 0.5);
    float rim = 1.0 - smoothstep(0.0, rim_w, -d - rim_w * 0.5);
    float glint = 0.5 + 0.5 * sin(TH_TAU * ((g.x - g.y) / 90.0 - th_phase(160.0)));
    vec3 rim_c = mix(TH_ROSE, vec3(1.0, 0.89, 0.93), glint);
    c = mix(c, rim_c, rim * (0.6 + 0.3 * glint));
    float a = mix(TH_ALPHA, 0.97, rim * 0.7);
    return vec4(c, a * cover);
}
