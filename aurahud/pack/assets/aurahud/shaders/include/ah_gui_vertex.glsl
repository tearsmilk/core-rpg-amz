#version 330

#moj_import <aurahud:ah_layout.glsl>

void ah_gui_vertex(vec3 pos, vec4 color, int vertexId) {
#if AH_ACTIVE
    if (ah_skip(pos)) {
        return;
    }
    vec2 s = ah_screen();
    float h = s.y;
    vec2 c = ah_fill_corner(vertexId % 4);
    ivec4 col = ivec4(round(color * 255.0));
    int act = AH_VANILLA;
    vec2 d = vec2(0.0);
    int slot = -1;
    if (col == ivec4(0, 0, 0, 255) && ah_eq(pos.y, c.y < 0.5 ? h - 6.0 : h - 4.0)) {
        float x = c.x < 0.5 ? 2.0 : 15.0;
        slot = ah_slot_at(pos.x, x, x, s);
    }
    if (slot < 0 && col.a == 255 && ah_eq(pos.y, c.y < 0.5 ? h - 6.0 : h - 5.0)) {
        slot = ah_slot_at(pos.x, 2.0, c.x < 0.5 ? 2.0 : 15.0, s);
    }
    if (slot < 0 && col == ivec4(255, 255, 255, 127) && (c.y < 0.5 ? ah_in(pos.y, h - 19.0, h - 3.0) : ah_eq(pos.y, h - 3.0))) {
        float x = c.x < 0.5 ? 0.0 : 16.0;
        slot = ah_slot_at(pos.x, x, x, s);
    }
    if (slot >= 0) {
        ah_slot_move(slot, s, act, d);
    } else if (ah_eq(pos.y, c.y < 0.5 ? h - 61.0 : h - 48.0) || ah_eq(pos.y, c.y < 0.5 ? h - 47.0 : h - 34.0)) {
#if AH_NAME != 0
        act = AH_NAME;
        d = ah_name_delta(s);
#endif
    }
    ah_apply(act, d);
#endif
}
