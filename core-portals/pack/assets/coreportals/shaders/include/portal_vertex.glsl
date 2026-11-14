// core-portals: vertex part of the portal shader (rendertype_item_entity_translucent_cull.vsh).
//
// Portal quads are item models (assets/coreportals/models/portal/*) whose faces are tinted with a marker colour
// from custom_model_data colours: R = 255, G = 64..95, B = 0. Any other colour is not ours: the vertex stays vanilla.
//   G 64 surface (crimson sky)    65 vertical glowing edge   66 flame tongues
//   G 67 ground crack glow        68 horizontal glowing edge 69 reserved
// Emerald portal: R = 255, G = 70..79, B = 0..250 — the blue channel is a parameter (cpParam = B / 250, 0..1):
//   G 70 vortex ring + void (B = phase: 0 formed .. 1 only a point of light)
//   G 71 nebula (B = 0)           72 spark / flash (B = 0)   73 crystal shard (B = face brightness)
//   G 74..79 reserved (any B)
// Lapis rift: R = 255, G = 80..89, B = 0..250 parameter:
//   G 80 rift (B = tear: 0 nothing, 0.3 full crack line, 0.45 split, 1 fully open)
//   G 81 haze around the rift (B = strength)
//   G 82 SCREEN glow overlay (B = intensity)   G 83 SCREEN crack overlay (B = opening progress)
//   G 84..89 reserved (any B), 90..95 reserved (B = 0)
// Screen kinds (82, 83) ignore where the display is: their quad is placed over the whole screen in clip space,
// just behind the near plane (CP_SCREEN_DEPTH), so only the player the display is shown to sees it, at any
// resolution, GUI scale or aspect ratio.
// B is never above 250: rezusmenu's cursor marker is R ~ 254, B ~ 254 (+-1.5), and R = 255 is inside its tolerance.
//
// All names are prefixed with cp / cp_ so the file can share one core shader with other systems' hooks.

flat out int cpKind;   // 0 = not a portal vertex, otherwise G - 63 (1..32)
flat out float cpParam; // blue channel / 250, 0..1 (only meaningful for G 70..89)
out vec2 cpLocal;      // position inside the quad: x 0..1 left to right, y 0..1 top to bottom

int cp_kind(vec4 color) {
    vec3 c = color.rgb * 255.0;
    if (c.r < 254.5 || c.g < 63.5 || c.g > 95.5) {
        return 0;
    }
    int g = int(c.g + 0.5);
    // Blue is a parameter only for G 70..89; everywhere else ours means B = 0.
    if (c.b > 0.5 && (g < 70 || g > 89)) {
        return 0;
    }
    return g - 63;
}

// Corner of the quad this vertex belongs to. Item quads are baked with 4 vertices each in a fixed order, and the
// UV of vertex i (rotation 0) is (u0,v0), (u0,v1), (u1,v1), (u1,v0): so the local position follows from the index.
vec2 cp_corner(int vertexId) {
    int i = vertexId % 4;
    if (i == 0) return vec2(0.0, 0.0);
    if (i == 1) return vec2(0.0, 1.0);
    if (i == 2) return vec2(1.0, 1.0);
    return vec2(1.0, 0.0);
}

const float CP_SCREEN_DEPTH = 0.06;   // view-space depth of the screen overlays (near plane is 0.05)

// Fills cpKind / cpParam / cpLocal for every vertex. Returns true for portal vertices (the caller makes them
// unlit). Screen overlay kinds (19, 20 = G 82, 83) also get their full-screen clip-space position here; this runs
// after the caller has set gl_Position, so it simply overrides it.
bool cp_vertex(vec4 color, int vertexId) {
    cpKind = cp_kind(color);
    cpParam = clamp(color.b * (255.0 / 250.0), 0.0, 1.0);
    cpLocal = cp_corner(vertexId);
    if (cpKind == 19 || cpKind == 20) {
        // Depth (and w) of a point straight ahead at CP_SCREEN_DEPTH: correct for any projection, view bobbing too.
        vec4 z = ProjMat * vec4(0.0, 0.0, -CP_SCREEN_DEPTH, 1.0);
        vec2 ndc = vec2(cpLocal.x * 2.0 - 1.0, 1.0 - cpLocal.y * 2.0);   // TL, BL, BR, TR: counter-clockwise
        gl_Position = vec4(ndc * z.w, z.z, z.w);
    }
    return cpKind > 0;
}
