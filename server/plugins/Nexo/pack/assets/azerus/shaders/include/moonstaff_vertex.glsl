// azerus / core_magic «Посох Лунного Странника»: vertex part of the magic circle / beam hook
// (rendertype_item_entity_translucent_cull.vsh). Called as the LAST line of main(): it only writes its own outputs,
// gl_Position and vertexColor stay exactly as computed before, so every other path is unchanged.
//
// Key A (vertex): the custom_model_data tint Color = (R, 233, 253) with G = 233 and B = 253 exact, R <= 250 is the
// parameter (msParam = R / 250), and only at the display brightness override 15/15 (UV2 = (240, 240)).
// Neighbours: rezusmenu R = B = 254 (B 253 != 254); core-portals / core-skyrift R = 255 (R <= 250 here).
// Key B (sprite signature) is checked in moonstaff_fragment.glsl; both keys must match or the vanilla path runs.
// Names: ms / ms_ only (shared core shader).

flat out int msOn;      // 1 = key A matched
flat out float msParam; // R / 250, 0..1
out vec2 msLocal;       // corner of the quad in sprite space: x along u, y along v, 0..1

// Baked item quads go (u0,v0), (u0,v1), (u1,v1), (u1,v0) (vanilla FaceBakery order, face uv rotation 0).
vec2 ms_corner(int id) {
    int i = id % 4;
    if (i == 0) return vec2(0.0, 0.0);
    if (i == 1) return vec2(0.0, 1.0);
    if (i == 2) return vec2(1.0, 1.0);
    return vec2(1.0, 0.0);
}

void ms_vertex(vec4 color, int vertexId, ivec2 light) {
    vec3 c = color.rgb * 255.0;
    msOn = (light == ivec2(240, 240) && abs(c.g - 233.0) < 0.5 && abs(c.b - 253.0) < 0.5 && c.r < 250.5) ? 1 : 0;
    msParam = clamp(color.r * (255.0 / 250.0), 0.0, 1.0);
    msLocal = ms_corner(vertexId);
}
