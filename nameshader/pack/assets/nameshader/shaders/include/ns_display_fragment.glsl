#version 330

#moj_import <nameshader:ns_common.glsl>

#define NS_MARK_RGB vec3(92.0, 14.0, 42.0)
#define NS_DISPLAY_RADIUS 4.0
#define NS_DISPLAY_ALPHA 0.86

in vec4 nsDisp;
in vec3 nsPos;

vec4 ns_display_fragment(vec4 color, vec4 tint) {
    vec3 px = dFdx(nsPos);
    vec3 py = dFdy(nsPos);
    vec2 lx = dFdx(nsDisp.xy);
    vec2 ly = dFdy(nsDisp.xy);
    if (nsDisp.z < 0.5) {
        return color;
    }
    float det = lx.x * ly.y - lx.y * ly.x;
    if (abs(det) < 1.0e-14) {
        return color;
    }
    mat2 screen = inverse(mat2(lx, ly));
    float w = length(px * screen[0].x + py * screen[0].y);
    float h = length(px * screen[1].x + py * screen[1].y);
    float unit = max(min(w, h) / 10.0, 1.0e-6);
    vec2 size = vec2(w, h) / unit;
    vec2 p = clamp(nsDisp.xy, 0.0, 1.0) * size;
    float aa = 0.5 * (length(vec2(lx.x, ly.x)) * size.x + length(vec2(lx.y, ly.y)) * size.y);
    vec3 light = clamp(tint.rgb * 255.0 / NS_MARK_RGB, 0.0, 1.0);
    vec4 plate = ns_plate(p, size, NS_DISPLAY_RADIUS, max(aa, 0.02), NS_DISPLAY_ALPHA, 0.6);
    return vec4(plate.rgb * light, plate.a * color.a / max(tint.a, 0.001));
}
