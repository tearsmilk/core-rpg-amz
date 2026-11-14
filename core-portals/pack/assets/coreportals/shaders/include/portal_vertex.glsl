// core-portals: vertex part of the portal shader (rendertype_item_entity_translucent_cull.vsh).
//
// Portal quads are item models (assets/coreportals/models/portal/*) whose faces are tinted with a marker colour
// from custom_model_data colours: R = 255, G = 64..95, B = 0. Any other colour is not ours: the vertex stays vanilla.
//   G 64 surface (swirling sky)   65 vertical glowing edge   66 flame tongues
//   G 67 ground crack glow        68 horizontal glowing edge 69..95 reserved for core-portals
//
// All names are prefixed with cp / cp_ so the file can share one core shader with other systems' hooks.

flat out int cpKind;   // 0 = not a portal vertex, otherwise G - 63 (1..32)
out vec2 cpLocal;      // position inside the quad: x 0..1 left to right, y 0..1 top to bottom

int cp_kind(vec4 color) {
    vec3 c = color.rgb * 255.0;
    if (c.r < 254.5 || c.b > 0.5 || c.g < 63.5 || c.g > 95.5) {
        return 0;
    }
    return int(c.g + 0.5) - 63;
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

// Fills cpKind / cpLocal for every vertex. Returns true for portal vertices (the caller makes them unlit).
bool cp_vertex(vec4 color, int vertexId) {
    cpKind = cp_kind(color);
    cpLocal = cp_corner(vertexId);
    return cpKind > 0;
}
