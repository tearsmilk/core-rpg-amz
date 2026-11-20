#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <nameshader:ns_common.glsl>

#define NS_WHITE_U0 (0.01 / 256.0)
#define NS_WHITE_U1 (4.99 / 256.0)
#define NS_WHITE_V0 (9.01 / 256.0)
#define NS_WHITE_V1 (16.99 / 256.0)
#define NS_TAG_HEIGHT 10.0
#define NS_TAG_WORLD_PX 0.025
#define NS_TAG_RADIUS 2.5
#define NS_TAG_RIM 0.4
#define NS_TAG_TEXT_ALPHA 0.8

float ns_tag_alpha(float vanilla) {
    return clamp(0.5 + 1.2 * vanilla, 0.6, 0.93);
}

float ns_tag_env() {
    float sky = max(max(FogColor.r, FogColor.g), FogColor.b);
    return clamp(0.3 + 0.9 * sky, 0.42, 1.0);
}

vec4 ns_tag_any(vec4 color, vec4 tint, vec2 uv, bool text) {
    vec2 local = vec2((uv.y - NS_WHITE_V0) / (NS_WHITE_V1 - NS_WHITE_V0), (uv.x - NS_WHITE_U0) / (NS_WHITE_U1 - NS_WHITE_U0));
    vec2 lx = dFdx(local);
    vec2 ly = dFdy(local);
    if (ProjMat[2][3] == 0.0) {
        return color;
    }
    bool white = all(greaterThanEqual(local, vec2(-0.01))) && all(lessThanEqual(local, vec2(1.01)));
    if (!white) {
        if (text && abs(tint.a - 128.0 / 255.0) < 0.002) {
            color.a = color.a / tint.a * NS_TAG_TEXT_ALPHA;
        }
        return color;
    }
    if (tint.a < 0.1 || any(greaterThan(tint.rgb, vec3(0.002)))) {
        return color;
    }
    float det = lx.x * ly.y - lx.y * ly.x;
    if (abs(det) < 1.0e-14) {
        return color;
    }
    mat2 screen = inverse(mat2(lx, ly));
    float w_screen = length(screen[0]);
    float h_screen = length(screen[1]);
    float depth = ProjMat[3][2] / (2.0 * gl_FragCoord.z - 1.0 + ProjMat[2][2]);
    float world = h_screen * 2.0 * depth / (abs(ProjMat[1][1]) * ScreenSize.y);
    if (world < NS_TAG_HEIGHT * NS_TAG_WORLD_PX * 0.55 || world > NS_TAG_HEIGHT * NS_TAG_WORLD_PX * 1.8) {
        return color;
    }
    float px = h_screen / NS_TAG_HEIGHT;
    vec2 size = vec2(NS_TAG_HEIGHT * w_screen / h_screen, NS_TAG_HEIGHT);
    vec2 p = clamp(local, 0.0, 1.0) * size;
    vec4 plate = ns_plate(p, size, NS_TAG_RADIUS, 1.0 / max(px, 0.05), ns_tag_alpha(tint.a), NS_TAG_RIM);
    return vec4(plate.rgb * ns_tag_env(), plate.a);
}

vec4 ns_tag(vec4 color, vec4 tint, vec2 uv) {
    return ns_tag_any(color, tint, uv, true);
}

vec4 ns_tag_plate_only(vec4 color, vec4 tint, vec2 uv) {
    return ns_tag_any(color, tint, uv, false);
}
