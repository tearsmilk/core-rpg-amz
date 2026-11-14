// core-portals: fragment part of the portal shader (rendertype_item_entity_translucent_cull.fsh).
// cp_fragment(kind, local) returns the final colour (not premultiplied) of a portal fragment.
// Time: GameTime from the Globals block (the client binds Globals to every core shader that declares it).

#moj_import <minecraft:globals.glsl>
#moj_import <coreportals:portal_noise.glsl>

flat in int cpKind;
flat in float cpParam;
in vec2 cpLocal;

// Seconds, 0..1200 (GameTime wraps every 24000 ticks).
float cp_time() {
    return GameTime * 1200.0;
}

// Fire ramp: 0 dark red .. 1 white-hot.
vec3 cp_fire(float x) {
    vec3 c = mix(vec3(0.35, 0.01, 0.0), vec3(0.95, 0.18, 0.02), smoothstep(0.0, 0.35, x));
    c = mix(c, vec3(1.0, 0.52, 0.08), smoothstep(0.3, 0.7, x));
    c = mix(c, vec3(1.0, 0.86, 0.45), smoothstep(0.7, 0.92, x));
    return mix(c, vec3(1.0, 0.98, 0.9), smoothstep(0.93, 1.0, x));
}

vec2 cp_rot(vec2 p, float a) {
    float c = cos(a);
    float s = sin(a);
    return vec2(c * p.x - s * p.y, s * p.x + c * p.y);
}

// Crimson ramp: 0 deep #8a0a1a .. 0.5 #ff3a4a .. 0.8 #ff6a6a .. 1 hot pink (never orange, never white).
vec3 cp_crimson(float x) {
    vec3 c = mix(vec3(0.30, 0.01, 0.04), vec3(0.54, 0.04, 0.10), smoothstep(0.0, 0.25, x));
    c = mix(c, vec3(1.0, 0.23, 0.29), smoothstep(0.2, 0.55, x));
    c = mix(c, vec3(1.0, 0.42, 0.42), smoothstep(0.55, 0.82, x));
    return mix(c, vec3(1.0, 0.70, 0.72), smoothstep(0.82, 1.0, x));
}

// ---- 1: portal surface — a glowing crimson monolith: luminous pink-red, fine light wisps flowing in arcs (like
//      marble or long-exposure light trails) slowly turning, a soft bright glow towards the bottom, faint embers.
vec4 cp_surface(vec2 uv, float t) {
    const float W = 2.34;   // surface size in blocks (see Spot.SURFACE_HALF_WIDTH / SURFACE_HEIGHT)
    const float H = 3.42;
    vec2 p = vec2((uv.x - 0.5) * W, (0.5 - uv.y) * H);      // blocks, y up, centre of the surface

    // Slow swirl: the inner part turns a little faster, so the arcs keep sliding past each other.
    vec2 c = p - vec2(0.0, 0.15);
    float r = length(c);
    vec2 q = cp_rot(c, t * 0.10 + 0.8 / (r + 0.7));

    // Domain warp: bends concentric arcs into flowing marble veins.
    vec2 w = vec2(cp_fbm(q * 0.85 + vec2(t * 0.03, 0.0)),
                  cp_fbm(q * 0.85 + vec2(4.7, -t * 0.025)));
    float body = cp_fbm(q * 1.6 + w * 1.5);

    // Two families of thin wisps (bright where the warped phase crosses an integer), gated by slow masks so they
    // come in bunches of partial arcs, not closed rings.
    float ph1 = r * 2.2 + w.x * 5.0 + w.y * 2.0;
    float ph2 = r * 3.6 - w.y * 6.0 + body * 2.0;
    float m1 = 0.3 + 0.7 * smoothstep(0.32, 0.62, cp_fbm(q * 0.9 + vec2(7.3, t * 0.02)));
    float m2 = 0.3 + 0.7 * smoothstep(0.3, 0.6, cp_fbm(q * 1.1 - vec2(2.1, 5.4)));
    float wisp1 = pow(0.5 + 0.5 * cos(ph1 * 6.2832), 14.0) * m1;
    float wisp2 = pow(0.5 + 0.5 * cos(ph2 * 6.2832), 22.0) * m2;
    float fine = pow(0.5 + 0.5 * cos((ph1 * 2.7 + ph2 * 0.6) * 6.2832), 30.0) * max(m1, m2);
    // Deep crimson shadow lanes between the veins.
    float shadow = smoothstep(0.55, 0.8, cp_fbm(q * 1.5 - w * 1.8 + vec2(3.1, 1.7)));

    // Soft bright glow towards the bottom of the opening.
    float low = smoothstep(0.35, 1.0, uv.y);
    float lum = 0.42 + 0.24 * body + 0.3 * low * low;
    lum = mix(lum, 0.12, shadow * (1.0 - 0.7 * low) * 0.7);
    lum += wisp1 * 0.4 + wisp2 * 0.3 + fine * 0.16;
    vec3 col = cp_crimson(clamp(lum, 0.0, 1.0));
    col += vec3(1.0, 0.35, 0.40) * pow(low, 3.0) * 0.18;

    // Faint red-pink embers drifting up.
    for (int i = 0; i < 2; i++) {
        float fi = float(i);
        vec2 sp = vec2(uv.x * (12.0 + fi * 6.0), uv.y * (21.0 + fi * 10.0) + t * (0.6 + fi * 0.4));
        vec2 g = floor(sp);
        vec2 f = fract(sp) - 0.5;
        float rnd = cp_hash(g + fi * 17.0);
        vec2 off = vec2(cp_hash(g + 3.1 + fi), cp_hash(g + 9.7 + fi)) - 0.5;
        float d = length(f - off * 0.7);
        float tw = 0.5 + 0.5 * sin(t * (3.0 + rnd * 4.0) + rnd * 40.0);
        float spark = smoothstep(0.09, 0.0, d) * step(0.84, rnd) * tw;
        col = mix(col, vec3(1.0, 0.62, 0.66), spark * 0.45);
    }

    // Red-pink rim along the frame, flickering.
    float edge = min(min(uv.x, 1.0 - uv.x) * W, min(uv.y, 1.0 - uv.y) * H);
    float flick = 0.7 + 0.3 * cp_noise(vec2(t * 5.0, (uv.x + uv.y) * 9.0));
    float rim = exp(-edge * 16.0) * flick;
    col = mix(col, vec3(1.0, 0.30, 0.38), clamp(rim * 1.1, 0.0, 1.0));
    col += vec3(1.0, 0.55, 0.60) * rim * 0.35;

    return vec4(min(col, vec3(1.0)), 0.96);
}

// ---- 2 / 5: thin glowing edge of the frame (2 — vertical strip, glow across x; 5 — horizontal, across y).
vec4 cp_edge(vec2 uv, float t, bool vertical) {
    float across = vertical ? uv.x : uv.y;
    float along = vertical ? uv.y : uv.x;
    float x = abs(across - 0.5) * 2.0;                       // 0 in the middle of the strip, 1 at its border
    float core = exp(-x * x * 22.0);
    float halo = exp(-x * 3.2) * 0.6;
    float flick = 0.68 + 0.32 * cp_noise(vec2(along * 9.0 - t * 2.6, t * 4.5));
    flick *= 0.9 + 0.1 * sin(t * 23.0 + along * 30.0);
    float k = (core + halo) * flick;
    vec3 col = mix(vec3(0.85, 0.10, 0.02), vec3(1.0, 0.55, 0.15), core);
    col = mix(col, vec3(1.0, 0.93, 0.75), pow(core, 4.0) * flick);
    float alpha = clamp(k, 0.0, 1.0) * (1.0 - smoothstep(0.8, 1.0, x));
    return vec4(col * (0.85 + 0.5 * core), alpha);
}

// ---- 3: flame tongues at the base of the frame (y = 1 is the ground).
vec4 cp_flames(vec2 uv, float t) {
    float h = 1.0 - uv.y;
    float n = cp_fbm(vec2(uv.x * 7.0, h * 2.4 - t * 1.9));
    float n2 = cp_noise(vec2(uv.x * 16.0, h * 5.5 - t * 3.6));
    float tongue = cp_noise(vec2(uv.x * 4.5 + t * 0.35, t * 0.6));
    float cols = smoothstep(0.3, 0.75, cp_noise(vec2(uv.x * 7.0 + t * 0.25, t * 0.45)));   // separate tongues
    float shape = n * 0.75 + n2 * 0.3 + tongue * 0.25 * (1.0 - h) + (cols - 0.5) * 0.45 * h - h + 0.02;
    float side = smoothstep(0.0, 0.14, uv.x) * smoothstep(1.0, 0.86, uv.x);
    float f = clamp(shape * 1.4, 0.0, 1.0) * side;

    // Embers rising above the flames.
    vec2 ep = vec2(uv.x * 22.0, h * 9.0 - t * 1.4);
    vec2 g = floor(ep);
    vec2 fr = fract(ep) - 0.5;
    float rnd = cp_hash(g);
    float ember = smoothstep(0.12, 0.0, length(fr - (vec2(cp_hash(g + 4.3), cp_hash(g + 8.1)) - 0.5) * 0.6))
        * step(0.86, rnd) * (1.0 - h) * side;

    vec3 col = cp_fire(f) + vec3(1.0, 0.6, 0.2) * ember;
    float alpha = max(smoothstep(0.06, 0.5, f), ember);
    return vec4(col, alpha);
}

// ---- 4: glowing cracks in the ground around the base.
vec4 cp_ground(vec2 uv, float t) {
    vec2 p = (uv - 0.5) * vec2(3.6, 2.4);                    // blocks (see the model: 3.6 × 2.4)
    float r = length(p * vec2(0.75, 1.15));
    vec2 v = cp_voronoi(p * 2.6);                             // x = F1, y = F2
    float crack = 1.0 - smoothstep(0.0, 0.07 + 0.03 * r, v.y - v.x);
    float pulse = 0.75 + 0.25 * sin(t * 2.4 - r * 5.0);
    float falloff = smoothstep(1.75, 0.15, r);
    float base = exp(-r * 2.4) * 0.7;
    float k = (crack * 0.95 * pulse + base) * falloff;
    vec3 col = mix(vec3(0.6, 0.05, 0.0), vec3(1.0, 0.55, 0.12), crack * pulse);
    col = mix(col, vec3(1.0, 0.85, 0.5), crack * (1.0 - r) * 0.5);
    return vec4(col, clamp(k, 0.0, 1.0));
}

// ================================ emerald portal (kinds 7..10, markers G 70..73) ================================
// cpParam is the marker's blue channel (0..1). Quad sizes match RingVisuals: VORTEX_HALF, NEBULA_HALF.

const float CP_VX_HALF = 2.4;    // vortex quad half-size, blocks (RingVisuals.VORTEX_HALF)
const float CP_VOID_R = 1.12;    // void (event horizon) radius when formed, blocks
const float CP_NB_HALF = 3.4;    // nebula quad half-size, blocks (RingVisuals.NEBULA_HALF)
const float CP_CENTER_UP = 1.8;  // ring centre above the ground (RingPlacement.CENTER_UP): glow fades before it

// Emerald ramp: 0 deep green .. 0.6 emerald .. 1 bright mint .. 1.6 white-mint.
vec3 cp_emerald(float x) {
    vec3 c = mix(vec3(0.0, 0.07, 0.03), vec3(0.01, 0.36, 0.15), smoothstep(0.0, 0.3, x));
    c = mix(c, vec3(0.06, 0.82, 0.40), smoothstep(0.25, 0.65, x));
    c = mix(c, vec3(0.45, 1.0, 0.66), smoothstep(0.6, 1.05, x));
    return mix(c, vec3(0.85, 1.0, 0.90), smoothstep(1.05, 1.6, x));
}

// Accretion streaks: 4 rigid layers, each turning at the Keplerian speed of its radius (faster inside) and blended
// by radius — the pattern shears like a disk but never winds up, however large GameTime gets. Long tangential arcs:
// low angular frequency (noise sampled on a circle — seamless) and high radial frequency.
float cp_streaks(float r, float a, float t, float rin) {
    float s = 0.0;
    float wsum = 0.0;
    for (int i = 0; i < 4; i++) {
        float fi = float(i);
        float ri = rin + 0.06 + fi * 0.24;
        float w = exp(-pow((r - ri) / 0.2, 2.0));
        float omega = 1.4 * pow(1.2 / max(ri, 0.3), 1.5);       // rad/s
        float ai = a + t * omega + 1.5 * log(max(r, 0.05));      // log spiral: arms wind into the void
        vec2 c = vec2(cos(ai), sin(ai));
        float n = cp_noise(c * 1.4 + vec2(r * 9.0, fi * 7.31)) * 0.6
                + cp_noise(c * 3.3 + vec2(r * 24.0, fi * 3.77 + 11.0)) * 0.4;
        s += w * n;
        wsum += w;
    }
    return s / max(wsum, 1e-3);
}

// Twinkling stars in the void (two layers).
vec3 cp_stars(vec2 q, float t) {
    vec3 c = vec3(0.0);
    for (int i = 0; i < 2; i++) {
        float fi = float(i);
        vec2 sp = q * (12.0 + fi * 10.0) + fi * 31.7;
        vec2 g = floor(sp);
        vec2 f = fract(sp) - 0.5;
        float rnd = cp_hash(g + fi * 7.0);
        vec2 off = vec2(cp_hash(g + 1.7), cp_hash(g + 5.3)) - 0.5;
        float d = length(f - off * 0.6);
        float tw = 0.55 + 0.45 * sin(t * (1.5 + rnd * 4.0) + rnd * 50.0);
        float star = smoothstep(0.11 - fi * 0.03, 0.0, d) * step(0.8, rnd) * tw;
        c += mix(vec3(0.80, 1.0, 0.88), vec3(0.45, 1.0, 0.62), fi) * star;
    }
    return c;
}

// ---- 7: vortex — bright emerald accretion ring around a black void with stars. phase: 0 formed .. 1 only light.
vec4 cp_vortex(vec2 uv, float t, float phase) {
    vec2 p = vec2(uv.x - 0.5, 0.5 - uv.y) * (2.0 * CP_VX_HALF);
    float r = length(p);
    float a = atan(p.y, p.x);
    float form = 1.0 - phase;
    float rv = CP_VOID_R * (0.04 + 0.96 * form);             // the void grows from the centre

    // Lensing wobble of the inner edge.
    vec2 dir = p / max(r, 1e-4);
    float wob = 0.022 * sin(a * 3.0 + t * 1.7) + 0.035 * (cp_noise(dir * 1.3 + vec2(t * 0.5, t * 0.3)) - 0.5);
    float e = r - rv * (1.0 + wob);                          // distance outside the horizon

    float st = cp_streaks(r, a, t, rv);
    float k = smoothstep(0.28, 0.82, st);
    float photon = exp(-pow((e - 0.03) / 0.028, 2.0));       // thin bright photon ring at the inner edge
    float disk = smoothstep(-0.01, 0.07, e) * exp(-max(e, 0.0) * 2.0);
    float halo = smoothstep(-0.02, 0.05, e) * exp(-max(e, 0.0) * 1.4);
    float light = photon * (1.0 + 0.5 * k) + disk * (0.15 + 1.45 * k * k) + halo * (0.2 + 0.2 * k);
    light *= 1.0 + 0.9 * phase;                              // a young ring burns brighter
    float fade = smoothstep(2.35, 1.6, r);                   // nothing past the inscribed circle (the quad spins)
    float ga = clamp(light, 0.0, 1.0) * fade;
    vec3 gc = cp_emerald(light * 0.82 + photon * 0.3);

    // Void: near-black with stars, lensed outwards and swirled near the horizon.
    float x = clamp(r / max(rv, 1e-3), 0.0, 1.0);
    vec2 q = cp_rot(p * (1.0 + 0.6 * x * x * x), t * 0.05 + 1.3 * x * x);
    vec3 voidCol = vec3(0.0, 0.012, 0.008) + cp_stars(q, t) * (1.0 - 0.6 * x * x);
    voidCol += vec3(0.05, 0.6, 0.3) * exp(-pow((x - 0.9) / 0.05, 2.0)) * 0.3 * (0.5 + 0.5 * st);   // bent light
    float voidA = (1.0 - smoothstep(-0.015, 0.012, e)) * 0.97;

    float aOut = ga + voidA * (1.0 - ga);
    vec3 col = (gc * ga + voidCol * voidA * (1.0 - ga)) / max(aOut, 1e-4);
    return vec4(min(col, vec3(1.0)), aOut);
}

// ---- 8: nebula — green mist around the ring, slowly drifting, with tiny sparks; fades before the ground.
vec4 cp_nebula(vec2 uv, float t) {
    vec2 p = vec2(uv.x - 0.5, 0.5 - uv.y) * (2.0 * CP_NB_HALF);
    float r = length(p);
    vec2 q = cp_rot(p, t * 0.035);
    vec2 w = vec2(cp_fbm(q * 0.7 + vec2(t * 0.03, 0.0)), cp_fbm(q * 0.7 + vec2(5.2, -t * 0.025)));
    float m = cp_fbm(q * 1.1 + w * 1.7 - vec2(0.0, t * 0.02));
    float band = smoothstep(1.0, 1.7, r) * smoothstep(3.3, 2.0, r);
    float glow = exp(-pow((r - 1.55) / 0.5, 2.0));
    float k = band * smoothstep(0.38, 0.8, m) * 0.8 + glow * (0.3 + 0.4 * m);

    vec2 sp = q * 4.5 + vec2(0.0, t * 0.25);
    vec2 g = floor(sp);
    vec2 f = fract(sp) - 0.5;
    float rnd = cp_hash(g + 4.0);
    vec2 off = vec2(cp_hash(g + 2.3), cp_hash(g + 6.1)) - 0.5;
    float sparks = smoothstep(0.08, 0.0, length(f - off * 0.6)) * step(0.9, rnd)
        * (0.5 + 0.5 * sin(t * (2.0 + rnd * 3.0) + rnd * 30.0)) * band;

    float ground = smoothstep(-CP_CENTER_UP - 0.1, -CP_CENTER_UP + 0.8, p.y);
    vec3 col = mix(vec3(0.01, 0.22, 0.11), vec3(0.16, 0.88, 0.46), clamp(m * 1.3 - 0.15, 0.0, 1.0));
    col = mix(col, vec3(0.6, 1.0, 0.75), sparks);
    float alpha = clamp(k * 0.55 + sparks * 0.9, 0.0, 0.7) * smoothstep(3.35, 2.8, r) * ground;
    return vec4(col, alpha);
}

// ---- 9: spark — a pulsing point of light with a soft four-ray flare (also the closing flash).
vec4 cp_spark(vec2 uv, float t) {
    vec2 p = (uv - 0.5) * 2.0;
    float r = length(p);
    float pulse = 0.85 + 0.15 * sin(t * 14.0);
    float core = exp(-r * r * 60.0);
    float glow = exp(-r * 4.5) * 0.7 * pulse;
    float rays = (exp(-abs(p.x) * 45.0) * exp(-abs(p.y) * 3.5) + exp(-abs(p.y) * 45.0) * exp(-abs(p.x) * 3.5))
        * 0.55 * pulse;
    float k = core + glow + rays;
    return vec4(cp_emerald(0.55 + k * 0.9), clamp(k, 0.0, 1.0) * smoothstep(1.0, 0.75, r));
}

// ---- 10: crystal shard — dark emerald with glowing cracks; shade (cpParam) = brightness of the face.
vec4 cp_shard(vec2 uv, float t, float shade) {
    vec2 v = cp_voronoi(uv * vec2(2.6, 3.4) + shade * 13.7);
    float crack = 1.0 - smoothstep(0.0, 0.085, v.y - v.x);
    float pulse = 0.65 + 0.35 * sin(t * 2.3 + shade * 9.0 + uv.y * 4.0);
    float edge = min(min(uv.x, 1.0 - uv.x), min(uv.y, 1.0 - uv.y));
    float rim = exp(-edge * 26.0);
    vec3 base = mix(vec3(0.01, 0.07, 0.04), vec3(0.03, 0.22, 0.12), uv.y * 0.5 + 0.25) * (0.45 + 0.75 * shade);
    vec3 col = base + vec3(0.02, 0.35, 0.17) * rim * shade + cp_emerald(0.9 + 0.4 * pulse) * crack * pulse;
    return vec4(min(col, vec3(1.0)), 1.0);
}

vec4 cp_fragment(int kind, vec2 local) {
    float t = cp_time();
    vec2 uv = clamp(local, 0.0, 1.0);
    if (kind == 1) return cp_surface(uv, t);
    if (kind == 2) return cp_edge(uv, t, true);
    if (kind == 3) return cp_flames(uv, t);
    if (kind == 4) return cp_ground(uv, t);
    if (kind == 5) return cp_edge(uv, t, false);
    if (kind == 7) return cp_vortex(uv, t, cpParam);
    if (kind == 8) return cp_nebula(uv, t);
    if (kind == 9) return cp_spark(uv, t);
    if (kind == 10) return cp_shard(uv, t, cpParam);
    return vec4(1.0, 0.0, 1.0, 1.0);                          // reserved marker without a painter: magenta
}
