#version 330

#moj_import <minecraft:globals.glsl>

#define TS_EFFECTS 56
#define TS_COLORS 16

const vec3 TS_PALETTE[TS_COLORS] = vec3[](
    vec3(1.0000, 1.0000, 1.0000), vec3(1.0000, 0.2745, 0.2745), vec3(1.0000, 0.5882, 0.1569), vec3(1.0000, 0.9216, 0.2353),
    vec3(0.5490, 1.0000, 0.2745), vec3(0.2353, 0.7843, 0.3529), vec3(0.2745, 0.9020, 0.8235), vec3(0.4314, 0.8235, 1.0000),
    vec3(0.3137, 0.4314, 1.0000), vec3(0.6275, 0.3529, 1.0000), vec3(0.9020, 0.3137, 0.9020), vec3(1.0000, 0.5490, 0.7843),
    vec3(1.0000, 0.7843, 0.2353), vec3(0.6667, 0.6667, 0.7059), vec3(0.1569, 0.1569, 0.1882), vec3(0.6275, 0.3922, 0.2353)
);

const bool TS_OWN[TS_EFFECTS] = bool[](
    false, false, false, false, false, false, true, false,
    false, false, false, false, false, false, false, false,
    false, true, true, true, true, true, true, true,
    true, true, true, false, false, false, false, false,
    false, true, true, false, false, false, true, true,
    true, false, true, true, false, false, false, true,
    true, true, true, true, false, false, false, false
);

const vec3 TS_WORLD[TS_EFFECTS] = vec3[](
    vec3(1.0), vec3(1.0), vec3(1.0), vec3(1.0), vec3(1.0), vec3(1.0), vec3(1.0, 0.45, 0.45), vec3(1.0),
    vec3(1.0), vec3(1.0), vec3(1.0), vec3(1.0), vec3(1.0), vec3(1.0), vec3(1.0), vec3(1.0),
    vec3(1.0), vec3(1.0, 0.55, 0.0), vec3(1.0, 0.61, 0.33), vec3(0.41, 0.94, 0.69), vec3(0.84, 0.0, 0.44), vec3(0.36, 0.81, 0.98), vec3(1.0, 0.13, 0.55), vec3(0.64, 0.64, 0.64),
    vec3(0.58, 0.8, 0.45), vec3(1.0, 0.96, 0.17), vec3(0.6, 0.85, 1.0), vec3(1.0), vec3(1.0), vec3(1.0), vec3(1.0), vec3(1.0),
    vec3(1.0), vec3(1.0, 0.78, 0.25), vec3(0.55, 0.35, 0.95), vec3(1.0), vec3(1.0), vec3(1.0), vec3(1.0, 0.6, 0.3), vec3(0.45, 1.0, 0.2),
    vec3(0.62, 0.02, 0.05), vec3(1.0), vec3(1.0, 0.45, 0.1), vec3(0.7, 0.6, 1.0), vec3(1.0), vec3(1.0), vec3(1.0), vec3(0.2, 0.9, 0.35),
    vec3(0.7, 0.9, 1.0), vec3(0.5, 0.9, 1.0), vec3(1.0, 0.6, 0.3), vec3(0.2, 0.6, 0.95), vec3(1.0), vec3(1.0), vec3(1.0), vec3(1.0)
);

ivec4 ts_decode(vec3 rgb) {
    ivec3 c = ivec3(round(rgb * 255.0));
    if (c.r == 214 && (c.g >> 2) < TS_EFFECTS) {
        int letter = ((c.b >> 6) << 4) | ((c.g & 3) << 2) | (c.b & 3);
        return ivec4(c.g >> 2, (c.b >> 2) & 15, letter, 0);
    }
    if (c.r == 53 && c.g < TS_EFFECTS) {
        return ivec4(c.g, c.b & 15, -1, 1);
    }
    return ivec4(-1, 0, -1, 0);
}

vec3 ts_world_color(int effect, int palette) {
    if (TS_OWN[effect] && palette == 0) {
        return TS_WORLD[effect];
    }
    return TS_PALETTE[palette];
}

float ts_margin(int effect) {
    return effect == 52 ? 12.0 : 4.0;
}
