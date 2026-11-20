#version 330

#moj_import <minecraft:globals.glsl>

#define CS_TAU 6.2831853
#define CS_RADIUS 3.0

const vec3 CS_DEEP = vec3(0.722, 0.165, 0.310);
const vec3 CS_WINE = vec3(0.910, 0.271, 0.420);
const vec3 CS_CRIMSON = vec3(1.000, 0.420, 0.557);
const vec3 CS_ROSE = vec3(1.000, 0.561, 0.671);
const vec3 CS_PINK = vec3(1.000, 0.702, 0.780);
const float CS_ALPHA = 0.84;
const float CS_INPUT_ALPHA = 0.72;
const float CS_LIST_ALPHA = 0.95;
const float CS_LIST_SHADE = 0.78;

float cs_phase(float cycles) {
    return GameTime * cycles;
}

float cs_box(vec2 p, vec2 half_size, vec4 r) {
    float rad = p.x > 0.0 ? (p.y > 0.0 ? r.z : r.y) : (p.y > 0.0 ? r.w : r.x);
    vec2 q = abs(p) - half_size + rad;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - rad;
}

vec3 cs_flow(vec2 g) {
    float a = sin(CS_TAU * ((g.x * 0.85 + g.y * 0.55) / 72.0 - cs_phase(300.0)));
    float b = sin(CS_TAU * ((g.x * 0.35 - g.y * 0.9) / 46.0 + cs_phase(220.0)) + 1.3);
    float m = clamp(0.5 + 0.34 * a + 0.16 * b, 0.0, 1.0);
    vec3 c = mix(CS_DEEP, CS_WINE, smoothstep(0.0, 0.55, m));
    c = mix(c, CS_CRIMSON, smoothstep(0.45, 1.0, m) * 0.85);
    float sweep = fract((g.x + g.y * 0.45) / 180.0 - cs_phase(180.0));
    float band = exp(-pow((sweep - 0.5) * 7.0, 2.0));
    c = mix(c, CS_PINK, 0.34 * band);
    return c;
}

float cs_rect_sdf(vec2 g, vec2 lo, vec2 hi, vec4 radii) {
    return cs_box(g - 0.5 * (lo + hi), 0.5 * (hi - lo), radii);
}

vec4 cs_shade(vec2 g, float d_rim, float px, float alpha) {
    vec3 c = cs_flow(g);
    float rim_w = max(px, 0.5);
    float rim = 1.0 - smoothstep(0.0, rim_w, -d_rim - rim_w * 0.5);
    float glint = 0.5 + 0.5 * sin(CS_TAU * ((g.x - g.y) / 90.0 - cs_phase(160.0)));
    vec3 rim_c = mix(CS_ROSE, vec3(1.0, 0.89, 0.93), glint);
    c = mix(c, rim_c, rim * (0.6 + 0.3 * glint));
    return vec4(c, mix(alpha, min(alpha * 1.15, 0.97), rim * 0.7));
}

float cs_alpha(float vanilla, float base) {
    return clamp(vanilla * base / 0.5, 0.0, 0.97);
}
