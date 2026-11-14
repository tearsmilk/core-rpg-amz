// core-skyrift: fragment part of the sky rift shader (rendertype_item_entity_translucent_cull.fsh).
// sr_fragment() returns the final colour (not premultiplied, no vanilla fog: this is the sky) of a sky fragment.
// Time: GameTime from the Globals block (bound to every core shader that declares it; re-import is deduplicated).
//
// Geometry (all in blocks, relative to the event centre, server mirror: RiftBand.java):
//   the rift lies on a virtual horizontal plane srAnchor.y above the camera (at least SR_MIN_PLANE); a view ray d
//   hits it at d * (h / d.y); uv = (along srAxis, across) of that point. The crack is a band of length
//   SR_LENGTH * size and half-width SR_THIN + SR_HALF_WIDTH * size * open * (1 - s^2)^SR_PROFILE, s = along / half
//   length, plus a zig-zag centre line and jagged edges (shader only). Hell is drawn on deeper planes (+40, +90)
//   seen through the crack: parallax.

#moj_import <minecraft:globals.glsl>
#moj_import <coreskyrift:skyrift_noise.glsl>

flat in int srKind;
flat in float srParam;
flat in vec3 srAnchor;
flat in vec2 srAxis;
flat in float srSize;
in vec3 srRay;
in vec2 srLocal;

const float SR_LENGTH = 480.0;      // server: RiftBand.BASE_LENGTH
const float SR_HALF_WIDTH = 110.0;  // server: RiftBand.BASE_HALF_WIDTH
const float SR_THIN = 1.5;          // server: RiftBand.THIN
const float SR_PROFILE = 0.65;      // server: RiftBand.PROFILE
const float SR_CRACK_END = 0.2;     // server: RiftBand.CRACK_END / Timeline.CRACK_END
const float SR_MIN_PLANE = 64.0;    // the plane is never closer above the camera than this (elytra, mountains)

// Palette.
const vec3 SR_BLACK = vec3(0.039, 0.0, 0.0);      // #0a0000
const vec3 SR_DRIED = vec3(0.227, 0.0, 0.0);      // #3a0000
const vec3 SR_BLOOD = vec3(0.478, 0.0, 0.031);    // #7a0008
const vec3 SR_CRIMSON = vec3(0.690, 0.0, 0.063);  // #b00010
const vec3 SR_MOLTEN = vec3(1.0, 0.165, 0.0);     // #ff2a00
const vec3 SR_HOT = vec3(1.0, 0.690, 0.251);      // #ffb040

// Seconds, 0..1200 (GameTime wraps every 24000 ticks).
float sr_time() {
    return GameTime * 1200.0;
}

// Where the ray d (unit) meets the plane `extra` blocks above the rift plane: x = along the axis, y = across,
// relative to the event centre. d.y must be > 0.
vec2 sr_plane(vec3 d, float extra) {
    float h = max(srAnchor.y, SR_MIN_PLANE) + extra;
    vec2 p = d.xz * (h / max(d.y, 1e-4)) - srAnchor.xz;
    return vec2(dot(p, srAxis), dot(p, vec2(-srAxis.y, srAxis.x)));
}

// Signed distance to the rift edge, blocks (negative inside). jag: 0..1 noise of the nearest edge (for the rim).
float sr_rift_sd(vec2 uv, float r, out float jag) {
    float lenF = clamp(r / SR_CRACK_END, 0.0, 1.0);
    float openF = clamp((r - SR_CRACK_END) / (1.0 - SR_CRACK_END), 0.0, 1.0);
    float hl = 0.5 * SR_LENGTH * srSize * lenF;
    // Zig-zag centre line (straightens a little as the rift opens).
    float zig = (sr_fbm3(vec2(uv.x / 46.0, 1.7)) - 0.5) * (14.0 + 10.0 * srSize) * (1.0 - 0.45 * openF)
        + (sr_noise(vec2(uv.x / 7.0, 4.2)) - 0.5) * 2.4;
    float v = uv.y - zig;
    float s = uv.x / max(hl, 1e-3);
    float q = max(0.0, 1.0 - s * s);
    float thin = SR_THIN * min(1.0, 0.4 + q);
    // Each edge tears on its own.
    float side = v >= 0.0 ? 1.0 : -1.0;
    jag = sr_fbm3(vec2(uv.x / 17.0, side * 5.3 + 11.0));
    float tooth = sr_noise(vec2(uv.x / 3.5, side * 9.1));
    float hw = thin + SR_HALF_WIDTH * srSize * openF * pow(q, SR_PROFILE) * (0.74 + 0.4 * jag + 0.08 * tooth);
    float across = abs(v) - hw;
    float along = abs(uv.x) - hl;
    return max(across, along);
}

// ---- G 96: the black fog dome. Smoky swirls drift slowly; it rolls in from the horizon up to the zenith.
vec4 sr_fog(vec3 d) {
    float f = srParam;
    if (f <= 0.002) return vec4(0.0);
    float time = sr_time();
    vec2 q = d.xz / (abs(d.y) + 0.28);
    float n = sr_fbm(q * 1.4 + vec2(time * 0.018, -time * 0.011));
    float n2 = sr_fbm(q * 3.3 - vec2(time * 0.031, time * 0.017) + 1.7 * n);
    float front = mix(-0.45, 1.95, f);
    float cover = 1.0 - smoothstep(front - 0.4, front, d.y + (n2 - 0.5) * 0.6);
    vec3 col = mix(vec3(0.004, 0.001, 0.002), vec3(0.06, 0.022, 0.026), smoothstep(0.15, 0.6, n2 * n));
    col += vec3(0.045, 0.0, 0.006) * smoothstep(0.55, 0.85, n2);   // faint dark-red smoke
    float a = cover * (0.88 + 0.12 * n) * smoothstep(0.0, 0.18, f);
    return vec4(col, clamp(a, 0.0, 0.985));
}

// What is behind the crack: a molten lava sea far away, blood-splattered rock and dark red smoke in front of it.
vec3 sr_hell(vec3 d, float sd, float time) {
    // Far layer (+90 blocks): lava sea — dark crust, crimson pools, glowing molten rivers, rising embers.
    vec2 p2 = sr_plane(d, 90.0);
    vec2 q = p2 / 70.0;
    float flow = time * 0.025;
    float n1 = sr_fbm(q + vec2(flow, -0.7 * flow));
    float n2 = sr_fbm(q * 1.9 - vec2(1.3 * flow, 0.4 * flow) + 1.6 * n1);
    float veins = 1.0 - smoothstep(0.0, 0.035, abs(n2 - 0.5));
    float pool = smoothstep(0.56, 0.8, n1);
    vec3 col = mix(SR_BLACK, SR_DRIED, smoothstep(0.25, 0.7, n1));
    col = mix(col, SR_CRIMSON, pool * 0.8);
    col += SR_MOLTEN * veins * (0.7 + 0.3 * sin(time * 1.4 + n1 * 9.0));
    col += SR_HOT * pow(veins, 4.0) * 0.6;
    col += SR_MOLTEN * smoothstep(0.7, 0.9, n1) * 0.35;   // hot spots in the pools
    vec2 ec = p2 / 11.0 + vec2(0.0, time * 0.25);
    vec2 cell = floor(ec);
    vec2 f = fract(ec) - 0.5;
    float h = sr_hash(cell);
    vec2 o = (vec2(sr_hash(cell + 3.1), sr_hash(cell + 7.7)) - 0.5) * 0.6;
    float ember = step(0.93, h) * (1.0 - smoothstep(0.0, 0.12, length(f - o))) * (0.55 + 0.45 * sin(time * 6.0 + h * 40.0));
    col += SR_HOT * ember * 1.3;

    // Near layer (+40 blocks): dark red smoke clouds and blood splatters (big stains, satellite drops, drips).
    vec2 p1 = sr_plane(d, 40.0);
    float smoke = sr_fbm(p1 / 95.0 + vec2(time * 0.012, time * 0.007));
    col = mix(col, vec3(0.13, 0.0, 0.006), smoothstep(0.42, 0.78, smoke) * 0.85);
    vec2 sp = p1 / 34.0;
    vec2 warp = 0.4 * vec2(sr_fbm3(sp * 1.7), sr_fbm3(sp * 1.7 + 5.0)) - 0.2;
    vec3 vo = sr_voronoi(sp + warp);
    float r0 = 0.2 + 0.22 * vo.z;
    float edgeN = sr_noise(p1 / 2.5) * 0.08;
    float stain = (1.0 - smoothstep(r0 - 0.04, r0 + edgeN, vo.x)) * step(0.28, vo.z);
    // Satellite drops around each stain and a few runs.
    float ring = smoothstep(r0 + 0.03, r0 + 0.12, vo.x) * (1.0 - smoothstep(r0 + 0.12, r0 + 0.3, vo.x));
    float drops = ring * step(0.28, vo.z) * smoothstep(0.7, 0.85, sr_noise(p1 / 1.8));
    float streak = smoothstep(0.86, 1.0, sr_noise(vec2(p1.x / 2.6, p1.y / 48.0 - time * 0.04)))
        * smoothstep(0.5, 0.8, sr_noise(p1 / 26.0));
    float blood = clamp(max(stain, max(drops, 0.85 * streak)), 0.0, 1.0);
    vec3 bloodCol = mix(SR_DRIED, SR_BLOOD, 0.35 + 0.65 * sr_noise(p1 / 4.0));
    bloodCol = mix(bloodCol, SR_BLACK, smoothstep(r0 - 0.1, r0 - 0.02, vo.x) * stain * 0.5);   // dried rim
    bloodCol += vec3(0.5, 0.03, 0.05) * smoothstep(0.75, 0.97, sr_noise(p1 / 1.4)) * stain * 0.45;   // wet sheen
    col = mix(col, bloodCol, blood * 0.93);

    // Depth: the middle of the rift glows from below, the torn walls near the edge are dark; it breathes.
    float depth = 1.0 - smoothstep(-26.0 * srSize - 6.0, -1.5, sd);
    col *= mix(0.3, 1.0, depth);
    col += SR_CRIMSON * 0.18 * (1.0 - smoothstep(-70.0 * srSize - 10.0, -10.0, sd));
    col *= 0.86 + 0.14 * sin(time * 1.1 + p1.x / 90.0);
    return col;
}

// ---- G 97: the rift across the sky and hell inside it.
vec4 sr_rift(vec3 d) {
    float r = srParam;
    // srParam is flat (one value per quad), so this early return is uniform for the whole primitive and keeps
    // fwidth below in uniform control flow; it skips all the noise during the fog-in stage.
    if (r <= 0.002) return vec4(0.0);
    float time = sr_time();
    float up = max(d.y, 0.002);
    vec2 uv = sr_plane(vec3(d.x, up, d.z), 0.0);
    float jag;
    float sd = sr_rift_sd(uv, r, jag);
    float aa = clamp(fwidth(sd), 0.02, 40.0);
    if (d.y <= 0.0) return vec4(0.0);
    float openF = clamp((r - SR_CRACK_END) / (1.0 - SR_CRACK_END), 0.0, 1.0);
    float horizon = smoothstep(0.012, 0.08, d.y);
    // Lightning-like flicker while the crack grows and when it snaps shut.
    float flick = 0.75 + 0.5 * step(0.78, sr_hash(vec2(floor(time * 14.0), 3.0))) * (1.0 - openF);
    float lipW = 0.6 + 1.8 * openF * srSize;
    float charW = (1.5 + 8.0 * openF * srSize) * (0.55 + 0.9 * jag);
    float pulse = 0.85 + 0.15 * sin(time * 2.1 + uv.x / 40.0);

    vec3 col;
    float a;
    if (sd < 0.0) {
        // Inside: hell, a blood rim, then the molten lip.
        col = openF > 0.0 ? sr_hell(d, sd, time) : SR_HOT;
        col = mix(col, SR_HOT * 1.15, (1.0 - smoothstep(0.0, 0.12, openF)) * 0.9);   // a thin crack is white-hot
        col = mix(col, SR_BLOOD, smoothstep(-7.0 * srSize - 1.0, -0.6, sd) * 0.6 * openF);
        col += SR_MOLTEN * exp(sd / lipW) * 1.1 * pulse * flick;
        a = 1.0;
    } else {
        // Outside: charred black torn edge with embers, the molten lip on it, and a crimson glow into the fog.
        float halo = exp(-sd / (5.0 + 26.0 * openF * srSize)) * (0.45 + 0.35 * openF) * pulse;
        float glowA = halo * 0.85;
        vec3 glow = mix(SR_CRIMSON, SR_MOLTEN, exp(-sd / (3.0 + 6.0 * openF)));
        float charred = (1.0 - smoothstep(charW - aa, charW + aa, sd)) * smoothstep(0.0, 0.25, openF);
        float speck = step(0.86, sr_noise(uv / 1.7)) * charred;
        vec3 charCol = mix(vec3(0.02, 0.0, 0.0), SR_MOLTEN * 0.8, speck * (0.5 + 0.5 * sin(time * 4.0 + uv.x)));
        col = mix(glow, charCol, charred);
        a = max(glowA, charred * 0.97);
        float lip = exp(-sd / lipW) * flick;
        col += SR_MOLTEN * lip * 1.2 * (1.0 - charred * 0.5);
        a = max(a, lip);
        // Fine side cracks of space around the young crack.
        float crackReach = 26.0 + 30.0 * openF;
        if (r < 0.65 && sd < crackReach) {   // Voronoi only where the cracks can show
            vec3 vo = sr_voronoi(uv / 14.0);
            float line = 1.0 - smoothstep(0.0, 0.035, vo.y - vo.x);
            float near = 1.0 - smoothstep(4.0, crackReach, sd);
            float k = line * near * (1.0 - smoothstep(0.35, 0.65, r)) * smoothstep(0.03, 0.12, r) * flick;
            col = mix(col, SR_MOLTEN * 1.2, k);
            a = max(a, k * 0.9);
        }
    }
    // Soft edge (anti-aliasing) between inside and outside is in the lip; fade towards the horizon.
    a *= horizon * smoothstep(0.0, 0.01, r);
    return vec4(col, clamp(a, 0.0, 1.0));
}

// ---- G 98: screen flash — red vignette and blood running down from the top edge (near plane, whole screen).
vec4 sr_flash(vec2 local) {
    float k = srParam;
    if (k <= 0.002) return vec4(0.0);
    vec2 c = local * 2.0 - 1.0;
    float vig = smoothstep(0.55, 1.25, length(c * vec2(0.85, 1.0)));
    float colId = floor(local.x * 56.0);
    float h = sr_hash(vec2(colId, 2.0));
    float len = (0.05 + 0.38 * h * h) * (0.4 + 0.6 * k);
    float inCol = 1.0 - smoothstep(0.16 + 0.12 * h, 0.24 + 0.12 * h, abs(fract(local.x * 56.0) - 0.5));
    float drip = inCol * (1.0 - smoothstep(len - 0.012, len, local.y)) * step(0.45, h);
    vec3 col = mix(vec3(0.45, 0.0, 0.02), vec3(0.25, 0.0, 0.0), drip);
    float a = k * max(vig * 0.8, drip * 0.92);
    return vec4(col, clamp(a, 0.0, 0.95));
}

vec4 sr_fragment() {
    if (srKind == 1) return sr_fog(normalize(srRay));
    if (srKind == 2) return sr_rift(normalize(srRay));
    if (srKind == 3) return sr_flash(srLocal);
    return vec4(0.0);
}
