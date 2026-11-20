#version 330

#moj_import <tabshader:th_common.glsl>

in vec3 thSprA;
in vec3 thSprC;

vec3 th_bar_tint(int variant, float x) {
    vec3 a = TH_CRIMSON;
    vec3 b = TH_PINK;
    if (variant == 2) {
        b = TH_ROSE;
    } else if (variant == 5) {
        a = vec3(0.62, 0.08, 0.42);
    } else if (variant == 6) {
        a = TH_ROSE;
        b = vec3(1.0, 0.82, 0.9);
    }
    return mix(a, b, smoothstep(0.0, 182.0, x));
}

vec4 th_bar(int kind, int variant, vec2 q, float x_end, float px) {
    if (kind == 250 || kind == 252) {
        float d = th_box(q - vec2(91.0, 2.5), vec2(91.0, 2.0), vec4(2.0));
        float cover = clamp(0.5 - d / px, 0.0, 1.0);
        if (cover <= 0.0) {
            return vec4(0.0);
        }
        if (kind == 252) {
            float seg = 182.0 / float(variant);
            float k = abs(q.x - seg * round(q.x / seg));
            float notch = (1.0 - smoothstep(0.25, 0.25 + px, k)) * step(1.0, q.x) * step(q.x, 181.0);
            return vec4(TH_PINK, 0.35 * notch * cover);
        }
        vec3 c = mix(TH_DEEP, TH_WINE, 0.35 + 0.25 * sin(TH_TAU * (q.x / 182.0 - th_phase(60.0))));
        float rim = 1.0 - smoothstep(0.0, max(px, 0.35), -d - max(px, 0.35) * 0.5);
        c = mix(c, TH_ROSE, rim * 0.6);
        return vec4(c, (0.72 + 0.2 * rim) * cover);
    }
    float d = th_box(q - vec2(0.5 * x_end, 2.5), vec2(0.5 * x_end - 0.75, 1.25), vec4(1.25));
    float cover = clamp(0.5 - d / px, 0.0, 1.0);
    if (cover <= 0.0) {
        return vec4(0.0);
    }
    if (kind == 253) {
        float seg = 182.0 / float(variant);
        float k = abs(q.x - seg * round(q.x / seg));
        float notch = (1.0 - smoothstep(0.25, 0.25 + px, k)) * step(1.0, q.x) * step(q.x, x_end - 1.0);
        return vec4(TH_DEEP, 0.75 * notch * cover);
    }
    vec3 c = th_bar_tint(variant, q.x);
    float wave = 0.5 + 0.5 * sin(TH_TAU * (q.x / 46.0 - th_phase(240.0)));
    c *= 0.86 + 0.18 * wave;
    float sweep = fract(q.x / 182.0 - th_phase(144.0));
    float band = exp(-pow((sweep - 0.5) * 14.0, 2.0));
    c = mix(c, vec3(1.0, 0.9, 0.95), band * 0.55);
    float shine = 1.0 - smoothstep(1.4, 2.6, q.y);
    c += TH_PINK * 0.18 * shine;
    float tip = 1.0 - smoothstep(0.0, 3.0, x_end - 0.75 - q.x);
    c = mix(c, vec3(1.0, 0.94, 0.97), tip * 0.45);
    return vec4(min(c, vec3(1.0)), cover);
}

vec4 th_sprite_fragment(vec4 color, vec4 tex, vec2 uv) {
    int kind = int(round(tex.a * 255.0));
    if (kind < 250 || kind > 253) {
        return color;
    }
    ivec3 code = ivec3(round(tex.rgb * 255.0));
    vec2 atlas = vec2(textureSize(Sampler0, 0));
    vec2 ua = thSprA.xy / max(thSprA.z, 1e-6);
    vec2 uc = thSprC.xy / max(thSprC.z, 1e-6);
    vec2 lo = min(ua, uc);
    vec2 hi = max(ua, uc);
    float rows = (hi.y - lo.y) * atlas.y;
    if (code.r > 181 || code.g > 4 || abs(rows - 5.0) > 0.05) {
        return color;
    }
    int variant = code.b;
    float x_end = round(texture(Sampler0, vec2(hi.x - 0.5 / atlas.x, uv.y)).r * 255.0) + 1.0;
    vec2 q = vec2(float(code.r), float(code.g)) + fract(uv * atlas);
    float px = max(fwidth(q.x), 1e-4);
    vec4 c = th_bar(kind, variant, q, x_end, px);
    return vec4(c.rgb, c.a * color.a / max(tex.a, 1e-3));
}
