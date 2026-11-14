// core-skyrift: vertex part of the sky rift shader (rendertype_item_entity_translucent_cull.vsh).
// Import AFTER <minecraft:projection.glsl> (needs ProjMat) and <minecraft:dynamictransforms.glsl> (ModelViewMat).
//
// Sky quads are item models (assets/coreskyrift/models/sky/sky.json) tinted with a marker colour from
// custom_model_data colours: R = 255, G = 96..111, B = 0..250 parameter (srParam = B / 250), and only on displays
// with the brightness override 15/15 (UV2 = 240, 240). Any other vertex is not ours and stays exactly vanilla.
//   G 96  sky fog dome   (far plane, full screen)  B = fog density
//   G 97  rift + hell    (far plane, full screen)  B = progress: 0..0.2 crack grows, 0.2..1 edges tear open
//   G 98  screen flash   (near plane, full screen) B = intensity
//   G 99..111 reserved (99 descent beam, 100 blood sigil): not drawn yet, so they stay vanilla (srKind = 0)
// Neighbours: core-portals uses R = 255, G 64..95 (its cp_kind rejects G > 95.5); rezusmenu uses R = B = 254 with
// UV2 = (240, 224). White (255, 255, 255) has G = 255 and never matches.
//
// Where the display stands does not matter: kinds 96/97 are placed over the whole screen just in front of the far
// plane (the sky never writes depth, so they pass only where nothing else was drawn), and the fragment part paints
// them by world direction. The server sets the display's translation to (rift anchor - display position), so the
// camera-relative vertex Position IS the camera -> anchor vector (srAnchor); the display's left rotation turns the
// face normal: its horizontal part is the rift axis (srAxis), its Y encodes the rift size (srSize).
//
// All names are prefixed with sr / sr_ so the file can share one core shader with other systems' hooks.

flat out int srKind;      // 0 = not ours, else G - 95 (1..16)
flat out float srParam;   // B / 250, 0..1
flat out vec3 srAnchor;   // camera -> rift anchor, blocks, world axes
flat out vec2 srAxis;     // horizontal rift axis (unit, world x/z)
flat out float srSize;    // rift size factor 0.25..2 (length 480 x half-width 110 at 1.0)
out vec3 srRay;           // world-space view ray (not normalised; exact direction per pixel)
out vec2 srLocal;         // position inside the quad / screen: x 0..1 left to right, y 0..1 top to bottom

const float SR_FAR_Z = 0.999999;      // NDC depth of the sky quads (behind all terrain up to ~the far plane)
const float SR_SCREEN_DEPTH = 0.055;  // view-space depth of the screen flash (near plane is 0.05)
const int SR_LAST_KIND = 3;           // highest implemented kind (G 98)
const float SR_TILT_MAX = 0.9;        // normal.y = 0.9 <-> size 2.0 (server: RiftBand.TILT_MAX)

// Strict: R exactly 255, G 96..111, B <= 250, and only at brightness 15/15. Returns 0 for everything else.
int sr_kind(vec4 color, ivec2 light) {
    if (light != ivec2(240, 240)) return 0;
    vec3 c = color.rgb * 255.0;
    if (abs(c.r - 255.0) > 0.5 || c.g < 95.5 || c.g > 111.5 || c.b > 250.5) return 0;
    return int(c.g + 0.5) - 95;
}

// Baked item quads: vertex i of a quad has UV (u0,v0), (u0,v1), (u1,v1), (u1,v0) -> corner from the index.
vec2 sr_corner(int vertexId) {
    int i = vertexId % 4;
    if (i == 0) return vec2(0.0, 0.0);
    if (i == 1) return vec2(0.0, 1.0);
    if (i == 2) return vec2(1.0, 1.0);
    return vec2(1.0, 0.0);
}

// Always writes every sr output (call it for every vertex). Returns true for our vertices (the caller makes them
// unlit). Runs after the caller has set gl_Position, so for kinds 96..98 it simply overrides it.
bool sr_vertex(vec4 color, int vertexId, vec3 position, vec3 normal, ivec2 light) {
    srKind = sr_kind(color, light);
    if (srKind > SR_LAST_KIND) srKind = 0;   // reserved markers are not drawn yet: vanilla
    srParam = clamp(color.b * (255.0 / 250.0), 0.0, 1.0);
    srLocal = sr_corner(vertexId);
    srAnchor = position;
    srAxis = length(normal.xz) > 1e-3 ? normalize(normal.xz) : vec2(0.0, 1.0);
    // Normal arrives as a signed byte / 127; the server sends (k + 0.5) / 127, the byte keeps k.
    float k = floor(clamp(normal.y, 0.0, 1.0) * 127.0 + 0.5);
    srSize = 0.25 + 1.75 * clamp((k / 127.0) / SR_TILT_MAX, 0.0, 1.0);
    srRay = vec3(0.0);
    vec2 ndc = vec2(srLocal.x * 2.0 - 1.0, 1.0 - srLocal.y * 2.0);   // TL, BL, BR, TR: counter-clockwise
    if (srKind == 1 || srKind == 2) {
        // A point on the far plane under this screen corner, back to view space and to world axes. The undivided
        // xyz is linear in screen space and parallel to the true ray, so the interpolated direction is exact (view
        // bobbing and nausea are part of ProjMat and are followed too).
        vec4 v = inverse(ProjMat) * vec4(ndc, 1.0, 1.0);
        srRay = inverse(mat3(ModelViewMat)) * (v.xyz * sign(v.w));
        gl_Position = vec4(ndc, SR_FAR_Z, 1.0);
    } else if (srKind == 3) {
        vec4 z = ProjMat * vec4(0.0, 0.0, -SR_SCREEN_DEPTH, 1.0);
        gl_Position = vec4(ndc * z.w, z.z, z.w);
    }
    return srKind > 0;
}
