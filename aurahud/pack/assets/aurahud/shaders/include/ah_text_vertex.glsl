#version 330

#moj_import <aurahud:ah_layout.glsl>

void ah_text_vertex(vec3 pos, vec4 color, int vertexId) {
#if AH_ACTIVE
    if (ah_skip(pos)) {
        return;
    }
    vec2 s = ah_screen();
    float cx = floor(s.x * 0.5);
    float h = s.y;
    vec2 c = ah_blit_corner(vertexId % 4);
    float top = pos.y - c.y * 8.0;
    ivec4 col = ivec4(round(color * 255.0));
    int act = AH_VANILLA;
    vec2 d = vec2(0.0);
    if ((col == ivec4(255) && ah_eq(top, h - 10.0)) || (col == ivec4(63, 63, 63, 255) && ah_eq(top, h - 9.0))) {
        int slot = ah_slot_at(pos.x, 5.0, 22.0, s);
        if (slot >= 0) {
            ah_slot_move(slot, s, act, d);
        }
    } else if ((col == ivec4(128, 255, 32, 255) || col == ivec4(0, 0, 0, 255)) && ah_in(top, h - 36.0, h - 34.0) && ah_in(pos.x, cx - 60.0, cx + 60.0)) {
#if AH_XP != 0
#if AH_XP_LEVEL
        act = AH_XP;
#else
        act = AH_HIDDEN;
#endif
        d = ah_xp_level_delta(s);
#endif
    } else if (ah_eq(top, h - 59.0) || ah_eq(top, h - 58.0) || ah_eq(top, h - 45.0) || ah_eq(top, h - 44.0)) {
#if AH_NAME != 0
        act = AH_NAME;
        d = ah_name_delta(s);
#endif
    }
    ah_apply(act, d);
#endif
}
