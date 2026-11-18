// azerus / core_magic «Посох Лунного Странника»: fragment part of the magic circle / beam hook
// (rendertype_item_entity_translucent_cull.fsh). Python copy (previews, checks): nexo-items/tools/moonstaff_fx.py.
//
// Key B (sprite): every effect sprite is 128x128 in the item atlas, texel (0,0) = (177,126,90,3) and
// texel (1,0) = (K,90,126,3), K = sprite kind 1..19 (20..31 reserved). Both compared exactly. Alpha 3 is below the vanilla cutout 0.1,
// so without this hook the signature is invisible. ms_fragment returns false (use vanilla) unless both match.
//   K1 ring, K2 runes, K3 hexagram, K4 core  -> magic circle layers, msParam = charge 0..1
//   K5 beam_up (+v), K6 beam_down (-v), K7 beam_west (+u), K8 beam_east (-u) -> beam, length L = msParam*250/8 blocks
//   K9 flare (beam origin)
//   K10 hex_cell (barrier cell), msParam*250 = g + 25*L: g 0..24 shimmer phase, L 0..7 alpha L/8, 8 formed, 9 flash.
//       Sprite alpha classes: 255 rim, 254 glint, 96..239 fill (209..239 bevel), 0 (and < 8) outside.
//   K11 fire_ring, K12 fire_runes, K13 fire_sigil, K14 fire_flames -> fire circle («Огненный шар»), msParam = charge;
//       fire colours are baked into the sprites; K14 alpha 254 = ember (twinkles).
//   K15 comet_tail_up (+v), K16 _down (-v), K17 _west (+u), K18 _east (-u) -> comet tail, s = 0 at the head;
//   K19 comet_core (radial). Comet msParam*250 = g + 125*B: g 0..124 flicker seed, B 0 falling, 1 impact burst.
// Time: GameTime from the Globals block (re-import is deduplicated). t = seconds 0..1200; every rate below makes an
// integer number of cycles per 1200 s, so the wrap of GameTime is seamless.
// GLSL 330 core only. Names: ms / ms_ only (shared core shader).

#moj_import <minecraft:globals.glsl>

flat in int msOn;
flat in float msParam;
in vec2 msLocal;

const float MS_SPRITE_PX = 128.0;
const float MS_TAU = 6.28318530718;
const float MS_EDGE = 0.5 - 2.0 / 128.0;

bool ms_texel_is(vec4 t, vec4 ref) {
    vec4 d = abs(t * 255.0 - ref);
    return d.r < 0.5 && d.g < 0.5 && d.b < 0.5 && d.a < 0.5;
}

vec2 ms_rotate(vec2 p, float a) {
    vec2 d = p - 0.5;
    float c = cos(a);
    float s = sin(a);
    return vec2(c * d.x - s * d.y, s * d.x + c * d.y) + 0.5;
}

// Sprite-local q (0..1, the 124x124 interior) -> atlas uv.
vec2 ms_uv(vec2 uvMin, vec2 us, vec2 q) {
    return uvMin + (2.0 / MS_SPRITE_PX + clamp(q, 0.0, 1.0) * (124.0 / MS_SPRITE_PX)) * us;
}

bool ms_fragment(sampler2D atlas, vec2 uv, out vec4 color) {
    color = vec4(0.0);
    ivec2 size = textureSize(atlas, 0);
    vec2 as = vec2(size);
    vec2 us = MS_SPRITE_PX / as;
    vec2 uvMin = uv - msLocal * us;
    ivec2 p0 = clamp(ivec2(floor(uvMin * as + 0.5)), ivec2(0), size - ivec2(2, 1));
    if (!ms_texel_is(texelFetch(atlas, p0, 0), vec4(177.0, 126.0, 90.0, 3.0))) return false;
    vec4 k4 = texelFetch(atlas, p0 + ivec2(1, 0), 0) * 255.0;
    int kind = int(floor(k4.r + 0.5));
    if (kind < 1 || kind > 23) return false;
    if (!ms_texel_is(k4 / 255.0, vec4(float(kind), 90.0, 126.0, 3.0))) return false;
    // K20..23 — teleport circle (core_items scroll): the K1..4 circle logic with a violet glow.
    bool msViolet = kind >= 20;
    if (msViolet) kind -= 19;

    float t = GameTime * 1200.0;
    if (kind == 10) {
        // Barrier hex cell: crisp texel (sprite pixel = msLocal * 128), light sweep top-left -> bottom-right
        // (period 4 s), breath (2 s), glint twinkle (1.5 s); level L from msParam.
        vec2 msPx = clamp(msLocal * MS_SPRITE_PX, vec2(0.5), vec2(MS_SPRITE_PX - 0.5));
        vec4 tex = textureLod(atlas, uvMin + msPx / MS_SPRITE_PX * us, 0.0);
        int code = int(floor(msParam * 250.0 + 0.5));
        int lv = code / 25;
        int g = code - 25 * lv;
        float ph = float(g) / 24.0;
        float pos = ph * 0.8 + 0.2 * (msLocal.x - 0.5 * msLocal.y);
        float band = 1.0 - smoothstep(0.0, 0.12, abs(pos - (fract(t / 4.0) * 1.6 - 0.3)));
        float breath = 0.5 + 0.5 * sin(MS_TAU * 0.5 * t + ph * 6.0);
        float a255 = tex.a * 255.0;
        vec3 rgb = vec3(0.0);
        float alpha = 0.0;
        if (a255 > 254.5) {
            rgb = tex.rgb * (1.05 + 0.2 * breath) + 0.35 * band;
            alpha = 1.0;
        } else if (a255 > 253.5) {
            float tw = pow(max(0.5 + 0.5 * sin(MS_TAU * t / 1.5 + ph * 11.0 + msLocal.x * 7.0), 0.0), 8.0);
            rgb = mix(vec3(0.85, 0.97, 1.1), vec3(1.6), tw);
            alpha = 0.8 + 0.2 * tw;
        } else if (a255 >= 7.5) {
            rgb = mix(tex.rgb * vec3(0.82, 0.95, 1.12) * (0.95 + 0.12 * breath) + vec3(0.02, 0.08, 0.22),
                      vec3(0.72, 0.93, 1.0), 0.4) + vec3(0.6, 0.9, 1.0) * 0.35 * band;
            alpha = min(tex.a * (1.45 + 0.2 * band), 1.0);
        }
        if (lv <= 7) {
            alpha *= float(lv) / 8.0;
        } else if (lv == 9) {
            rgb = mix(rgb, vec3(1.0), 0.5);
            alpha = clamp(alpha * 1.3 + 0.1 * step(0.004, alpha), 0.0, 1.0);
        }
        color = vec4(rgb, alpha);
        return true;
    }
    if (kind >= 11 && kind <= 14) {
        // Fire circle layer (copy of K1..4): rotation, charge reveal, flicker, warm glow.
        // Rates per 1200 s: K11 60, K12 100, K13 40 turns; pulse 1800, shimmer 4800, wobble 2400, flicker 6000,
        // ember twinkle 800 cycles.
        float c = msParam;
        float a = 0.0;
        if (kind == 11) a = MS_TAU * t / 20.0;
        else if (kind == 12) a = -MS_TAU * t / 12.0;
        else if (kind == 13) a = MS_TAU * t / 30.0;
        vec2 q = ms_rotate(msLocal, a);
        float flick = 1.0;
        if (kind == 14) {
            vec2 dq = q - 0.5;
            float fa = atan(dq.x, -dq.y);
            q = 0.5 + dq * (1.0 + 0.035 * sin(MS_TAU * 2.0 * t + 24.0 * fa));
            flick = 0.85 + 0.15 * sin(MS_TAU * 5.0 * t + 7.0 * fa);
        }
        vec4 tex = texture(atlas, ms_uv(uvMin, us, q));
        float halo = textureLod(atlas, ms_uv(uvMin, us, q), 2.0).a;
        float pulse = 0.5 + 0.5 * sin(MS_TAU * 1.5 * t);
        float appear = 1.0;
        if (kind == 12) {
            vec2 d = msLocal - 0.5;
            float ang = atan(d.x, -d.y);
            if (ang < 0.0) ang += MS_TAU;
            appear = smoothstep(ang, ang + 0.15, c * (MS_TAU + 0.15));
        } else if (kind == 13) {
            appear = smoothstep(0.2, 0.65, c);
        } else if (kind == 14) {
            appear = 0.35 + 0.65 * c;
        }
        float bright = 0.8 + 0.35 * c + 0.1 * pulse;
        if (c >= 0.999) bright += 0.25 * (0.5 + 0.5 * sin(MS_TAU * 4.0 * t));
        // Channel-safe: dimming goes into alpha, never into a grey/brown colour; light above 1 spills from red into
        // green/blue (toward yellow-white). Soft and halo texels take the bright glow colour, not the dark texel.
        vec3 lit = tex.rgb * bright * flick;
        lit += vec3(0.0, 0.35, 0.25) * max(lit.r - 1.0, 0.0);
        vec3 glow = vec3(1.0, 0.56, 0.16) * (0.85 + 0.3 * c);
        vec3 rgb = mix(glow, lit, tex.a);
        float alpha = max(tex.a, halo * 0.55) * appear;
        if (kind == 14 && abs(textureLod(atlas, ms_uv(uvMin, us, q), 0.0).a * 255.0 - 254.0) < 0.5) {
            float tw = pow(max(0.5 + 0.5 * sin(MS_TAU * t / 1.5 + q.x * 37.0 + q.y * 23.0), 0.0), 6.0);
            rgb = mix(vec3(1.0, 0.62, 0.2), vec3(1.6, 1.35, 0.85), tw);
            alpha = (0.55 + 0.45 * tw) * appear;
        }
        if (length(q - 0.5) > MS_EDGE) alpha = 0.0;
        color = vec4(rgb, alpha);
        return true;
    }
    if (kind >= 15) {
        // Comet: code = g + 125*B (g flicker seed, B 1 = impact burst).
        int code = int(floor(msParam * 250.0 + 0.5));
        int burst = code / 125;
        int g = code - 125 * burst;
        if (kind <= 18) {
            // Tail plane: s along the tail (0 at the head), w across; streaks run from the head to the tip (4 Hz).
            int kb = kind - 10;
            float s = msLocal.y;
            float w = msLocal.x;
            if (kb == 6) s = 1.0 - msLocal.y;
            if (kb == 7) { s = msLocal.x; w = msLocal.y; }
            if (kb == 8) { s = 1.0 - msLocal.x; w = msLocal.y; }
            float d = abs(2.0 * w - 1.0);
            float ph = fract(s * 3.0 - t * 4.0 + float(g) / 125.0);
            vec2 q = (kind <= 16) ? vec2(w, ph) : vec2(ph, w);
            float streak = textureLod(atlas, ms_uv(uvMin, us, q), 0.0).a;
            float width = mix(0.95, 0.15, s);
            float body = 1.0 - smoothstep(0.0, width, d);
            vec3 heat;
            if (s < 0.18) heat = mix(vec3(1.0, 0.97, 0.85), vec3(1.0, 0.78, 0.3), s / 0.18);
            else if (s < 0.5) heat = mix(vec3(1.0, 0.78, 0.3), vec3(1.0, 0.5, 0.1), (s - 0.18) / 0.32);
            else heat = mix(vec3(1.0, 0.5, 0.1), vec3(0.85, 0.2, 0.03), (s - 0.5) / 0.5);
            vec3 rgb = heat * (0.9 + 0.25 * streak);
            float alpha = body * (0.55 + 0.45 * streak) * pow(max(1.0 - s, 0.0), 1.3) * smoothstep(0.0, 0.05, s);
            if (burst == 1) alpha *= 0.35;
            color = vec4(rgb, clamp(alpha, 0.0, 1.0));
            return true;
        }
        // K19 comet head: radial sprite, 8 Hz pulse, warm glow; burst = whiter and denser.
        vec2 dc = msLocal - 0.5;
        float rr = length(dc);
        vec4 tex = texture(atlas, ms_uv(uvMin, us, msLocal));
        float halo = textureLod(atlas, ms_uv(uvMin, us, msLocal), 2.0).a;
        float pulse = 0.9 + 0.1 * sin(MS_TAU * 8.0 * t + float(g));
        vec3 rgb = tex.rgb * pulse + vec3(1.0, 0.6, 0.15) * halo * 0.6;
        float alpha = max(tex.a, halo * 0.5);
        if (burst == 1) {
            rgb = mix(rgb, vec3(1.0), 0.55);
            alpha = min(1.0, alpha * 1.4 + 0.1 * (1.0 - smoothstep(0.3, MS_EDGE, rr)));
        }
        if (rr > MS_EDGE) alpha = 0.0;
        color = vec4(rgb, alpha);
        return true;
    }
    if (kind <= 4) {
        // Magic circle layer: rotation, charge reveal, pulse, glow.
        float c = msParam;
        float a = 0.0;
        if (kind == 1) a = MS_TAU * 0.25 * t;
        else if (kind == 2) a = -MS_TAU * t / 6.0;
        else if (kind == 3) a = MS_TAU * t / 12.0;
        vec2 q = ms_rotate(msLocal, a);
        vec4 tex = texture(atlas, ms_uv(uvMin, us, q));
        float halo = textureLod(atlas, ms_uv(uvMin, us, q), 2.0).a;
        float pulse = 0.5 + 0.5 * sin(MS_TAU * 1.5 * t);
        float appear = 1.0;
        if (kind == 2) {
            vec2 d = msLocal - 0.5;
            float ang = atan(d.x, -d.y);
            if (ang < 0.0) ang += MS_TAU;
            appear = smoothstep(ang, ang + 0.15, c * (MS_TAU + 0.15));
        } else if (kind == 3) {
            appear = smoothstep(0.25, 0.7, c);
        } else if (kind == 4) {
            appear = 0.4 + 0.6 * c;
        }
        float bright = 0.55 + 0.45 * c + 0.12 * pulse;
        if (c >= 0.999) bright += 0.25 * (0.5 + 0.5 * sin(MS_TAU * 4.0 * t));
        vec3 glowC = msViolet ? vec3(0.8, 0.35, 1.0) : vec3(0.35, 0.8, 1.0);
        vec3 rgb = tex.rgb * bright + glowC * halo * 0.6 * (0.5 + 0.5 * c);
        float alpha = max(tex.a, halo * 0.45) * appear;
        if (length(q - 0.5) > MS_EDGE) alpha = 0.0;
        color = vec4(rgb, alpha);
        return true;
    }
    if (kind <= 8) {
        // Beam plane: s along the beam (0 at the circle), w across.
        float s = msLocal.y;
        float w = msLocal.x;
        if (kind == 6) s = 1.0 - msLocal.y;
        if (kind == 7) { s = msLocal.x; w = msLocal.y; }
        if (kind == 8) { s = 1.0 - msLocal.x; w = msLocal.y; }
        float d = abs(2.0 * w - 1.0);
        float L = max(msParam * 250.0 / 8.0, 0.5);
        float ph = fract(s * L * 0.5 - t * 6.0);
        vec2 q = (kind <= 6) ? vec2(w, ph) : vec2(ph, w);
        float streak = textureLod(atlas, ms_uv(uvMin, us, q), 0.0).a;
        float core = 1.0 - smoothstep(0.0, 0.42, d);
        float soft = 1.0 - smoothstep(0.1, 1.0, d);
        float taper = smoothstep(0.0, 0.4 / L, s) * (1.0 - smoothstep(1.0 - 1.2 / L, 1.0, s));
        vec3 rgb = mix(vec3(0.72, 0.9, 1.0), vec3(1.0), core);
        float alpha = clamp(soft * (0.12 + 0.28 * streak) + core * 0.85, 0.0, 1.0) * taper;
        color = vec4(rgb, alpha);
        return true;
    }
    // K9 flare at the beam origin.
    vec2 q = ms_rotate(msLocal, MS_TAU * t);
    vec4 tex = texture(atlas, ms_uv(uvMin, us, q));
    float alpha = tex.a;
    if (length(q - 0.5) > MS_EDGE) alpha = 0.0;
    color = vec4(tex.rgb * (1.2 + 0.3 * sin(MS_TAU * 3.0 * t)), alpha);
    return true;
}
