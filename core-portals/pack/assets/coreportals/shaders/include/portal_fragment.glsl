// core-portals: fragment part of the portal shader (rendertype_item_entity_translucent_cull.fsh).
// cp_fragment(kind, local) returns the final colour (not premultiplied) of a portal fragment.
// Time: GameTime from the Globals block (the client binds Globals to every core shader that declares it).

#moj_import <minecraft:globals.glsl>
#moj_import <coreportals:portal_noise.glsl>

flat in int cpKind;
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

// ---- 1: portal surface — a swirling night sky with violet / blue clouds, pink horizon glow and sparks.
vec4 cp_surface(vec2 uv, float t) {
    const float W = 1.64;   // surface size in blocks (see PortalVisuals)
    const float H = 2.82;
    vec2 p = vec2((uv.x - 0.5) * W, (0.5 - uv.y) * H);      // blocks, y up, centre of the surface

    // Swirl around a point a little above the middle: angular speed grows towards the centre.
    vec2 c = p - vec2(0.0, 0.25);
    float r = length(c);
    float a = atan(c.y, c.x) + t * 0.32 + 1.25 / (r + 0.45);
    vec2 q = vec2(cos(a), sin(a)) * r;

    // Domain-warped clouds.
    vec2 w = vec2(cp_fbm(q * 1.7 + vec2(t * 0.07, -t * 0.05)),
                  cp_fbm(q * 1.7 + vec2(-t * 0.06, t * 0.08) + 5.2));
    float clouds = cp_fbm(q * 2.4 + w * 1.8 + vec2(0.0, t * 0.04));
    float detail = cp_fbm(q * 5.0 - w * 2.0 + vec2(t * 0.1, 0.0));

    // Sky: deep navy at the top, violet in the middle, pink-magenta horizon in the lower third, dusk below it.
    float h = uv.y;
    vec3 sky = mix(vec3(0.02, 0.02, 0.10), vec3(0.17, 0.06, 0.38), smoothstep(0.0, 0.6, h));
    float horizon = exp(-pow((h - 0.72) * 7.0, 2.0));
    sky = mix(sky, vec3(0.98, 0.38, 0.62), horizon * 0.8);
    sky = mix(sky, vec3(0.16, 0.04, 0.20), smoothstep(0.8, 1.0, h));

    vec3 cloudCol = mix(vec3(0.10, 0.10, 0.42), vec3(0.50, 0.20, 0.78), clouds);
    cloudCol = mix(cloudCol, vec3(1.0, 0.50, 0.70), horizon * 0.55 + detail * 0.15);
    float cloudMask = smoothstep(0.38, 0.82, clouds + detail * 0.25);
    vec3 col = mix(sky, cloudCol, cloudMask * 0.85);

    // Glowing heart of the swirl.
    col += vec3(0.75, 0.40, 1.0) * pow(max(0.0, 1.0 - r * 1.35), 3.0) * (0.55 + 0.25 * sin(t * 1.7));

    // Sparks drifting up (two layers, different speed).
    for (int i = 0; i < 2; i++) {
        float fi = float(i);
        vec2 sp = vec2(uv.x * (12.0 + fi * 6.0), uv.y * (21.0 + fi * 10.0) + t * (0.7 + fi * 0.5));
        vec2 g = floor(sp);
        vec2 f = fract(sp) - 0.5;
        float rnd = cp_hash(g + fi * 17.0);
        vec2 off = vec2(cp_hash(g + 3.1 + fi), cp_hash(g + 9.7 + fi)) - 0.5;
        float d = length(f - off * 0.7);
        float tw = 0.5 + 0.5 * sin(t * (3.0 + rnd * 4.0) + rnd * 40.0);
        float spark = smoothstep(0.10, 0.0, d) * step(0.78, rnd) * tw;
        col += mix(vec3(1.0, 0.70, 0.95), vec3(1.0, 0.75, 0.40), fi) * spark * 1.2;
    }

    // Fire licking up from the bottom edge.
    float fy = (1.0 - uv.y) * H;   // height above the bottom, blocks
    float fn = cp_fbm(vec2(uv.x * 6.0, fy * 2.6 - t * 2.1));
    float flame = clamp((fn * 1.2 - fy * 1.9 - 0.05) * 2.0, 0.0, 1.0);
    col = mix(col, cp_fire(flame), smoothstep(0.05, 0.6, flame));

    // Hot rim along the frame, flickering.
    float edge = min(min(uv.x, 1.0 - uv.x) * W, min(uv.y, 1.0 - uv.y) * H);
    float flick = 0.7 + 0.3 * cp_noise(vec2(t * 5.0, (uv.x + uv.y) * 9.0));
    float rim = exp(-edge * 16.0) * flick;
    col = mix(col, vec3(1.0, 0.33, 0.05), clamp(rim * 1.2, 0.0, 1.0));
    col += vec3(1.0, 0.65, 0.25) * rim * 0.5;

    return vec4(col, 0.96);
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

vec4 cp_fragment(int kind, vec2 local) {
    float t = cp_time();
    vec2 uv = clamp(local, 0.0, 1.0);
    if (kind == 1) return cp_surface(uv, t);
    if (kind == 2) return cp_edge(uv, t, true);
    if (kind == 3) return cp_flames(uv, t);
    if (kind == 4) return cp_ground(uv, t);
    if (kind == 5) return cp_edge(uv, t, false);
    return vec4(1.0, 0.0, 1.0, 1.0);                          // reserved marker without a painter: magenta
}
