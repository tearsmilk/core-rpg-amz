#version 330

#moj_import <minecraft:globals.glsl>

#define NS_TAU 6.2831853

const vec3 NS_DEEP = vec3(0.722, 0.165, 0.310);
const vec3 NS_WINE = vec3(0.910, 0.271, 0.420);
const vec3 NS_CRIMSON = vec3(1.000, 0.420, 0.557);
const vec3 NS_ROSE = vec3(1.000, 0.561, 0.671);
const vec3 NS_PINK = vec3(1.000, 0.702, 0.780);

float ns_phase(float cycles) {
    return GameTime * cycles;
}

float ns_box(vec2 p, vec2 half_size, float r) {
    vec2 q = abs(p) - half_size + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}

vec3 ns_flow(vec2 g) {
    float a = sin(NS_TAU * ((g.x * 0.85 + g.y * 0.55) / 72.0 - ns_phase(300.0)));
    float b = sin(NS_TAU * ((g.x * 0.35 - g.y * 0.9) / 46.0 + ns_phase(220.0)) + 1.3);
    float m = clamp(0.5 + 0.34 * a + 0.16 * b, 0.0, 1.0);
    vec3 c = mix(NS_DEEP, NS_WINE, smoothstep(0.0, 0.6, m));
    c = mix(c, NS_CRIMSON, smoothstep(0.5, 1.0, m) * 0.7);
    float sweep = fract((g.x + g.y * 0.45) / 140.0 - ns_phase(180.0));
    float band = exp(-pow((sweep - 0.5) * 7.0, 2.0));
    return mix(c, NS_PINK, 0.3 * band);
}

vec4 ns_plate(vec2 p, vec2 size, float radius, float aa, float alpha, float rim_px) {
    vec2 half_size = 0.5 * size;
    float r = min(radius, min(half_size.x, half_size.y));
    float d = ns_box(p - half_size, half_size, r);
    float cover = clamp(0.5 - d / aa, 0.0, 1.0);
    vec3 c = ns_flow(p);
    float rim_w = max(aa, rim_px);
    float rim = 1.0 - smoothstep(0.0, rim_w, -d - rim_w * 0.5);
    float glint = 0.5 + 0.5 * sin(NS_TAU * ((p.x - p.y) / 60.0 - ns_phase(160.0)));
    vec3 rim_c = mix(NS_ROSE, vec3(1.0, 0.89, 0.93), glint);
    c = mix(c, rim_c, rim * (0.55 + 0.3 * glint));
    float a = mix(alpha, min(alpha + 0.12, 0.97), rim * 0.7);
    return vec4(c, a * cover);
}
