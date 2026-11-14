// core-skyrift: noise helpers (prefixed sr_ to avoid clashes with other systems' includes).
// Callers scale coordinates down first (features of several blocks), so arguments stay small for float precision.

float sr_hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float sr_noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(sr_hash(i), sr_hash(i + vec2(1.0, 0.0)), u.x),
               mix(sr_hash(i + vec2(0.0, 1.0)), sr_hash(i + vec2(1.0, 1.0)), u.x), u.y);
}

float sr_fbm(vec2 p) {
    float s = 0.0;
    float a = 0.5;
    mat2 m = mat2(1.6, 1.2, -1.2, 1.6);
    for (int i = 0; i < 5; i++) {
        s += a * sr_noise(p);
        p = m * p;
        a *= 0.5;
    }
    return s;
}

// 3 octaves: cheaper, for edges.
float sr_fbm3(vec2 p) {
    float s = 0.5 * sr_noise(p);
    p = mat2(1.6, 1.2, -1.2, 1.6) * p;
    s += 0.25 * sr_noise(p);
    p = mat2(1.6, 1.2, -1.2, 1.6) * p;
    s += 0.125 * sr_noise(p);
    return s / 0.875;
}

// x = distance to the nearest cell point (F1), y = second nearest (F2), z = hash of the nearest cell (0..1).
vec3 sr_voronoi(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    float f1 = 8.0;
    float f2 = 8.0;
    float id = 0.0;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 g = vec2(float(x), float(y));
            vec2 o = vec2(sr_hash(i + g), sr_hash(i + g + 19.19));
            float d = length(g + o - f);
            if (d < f1) {
                f2 = f1;
                f1 = d;
                id = sr_hash(i + g + 7.31);
            } else if (d < f2) {
                f2 = d;
            }
        }
    }
    return vec3(f1, f2, id);
}
