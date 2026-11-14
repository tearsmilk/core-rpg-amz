// core-portals: noise helpers (prefixed cp_ to avoid clashes with other systems' includes).

float cp_hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float cp_noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(cp_hash(i), cp_hash(i + vec2(1.0, 0.0)), u.x),
               mix(cp_hash(i + vec2(0.0, 1.0)), cp_hash(i + vec2(1.0, 1.0)), u.x), u.y);
}

float cp_fbm(vec2 p) {
    float s = 0.0;
    float a = 0.5;
    mat2 m = mat2(1.6, 1.2, -1.2, 1.6);
    for (int i = 0; i < 5; i++) {
        s += a * cp_noise(p);
        p = m * p;
        a *= 0.5;
    }
    return s;
}

// Distances to the nearest and second nearest cell points (F1, F2).
vec2 cp_voronoi(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    float f1 = 8.0;
    float f2 = 8.0;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 g = vec2(float(x), float(y));
            vec2 o = vec2(cp_hash(i + g), cp_hash(i + g + 19.19));
            float d = length(g + o - f);
            if (d < f1) {
                f2 = f1;
                f1 = d;
            } else if (d < f2) {
                f2 = d;
            }
        }
    }
    return vec2(f1, f2);
}
