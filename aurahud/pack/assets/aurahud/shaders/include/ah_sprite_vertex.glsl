#version 330

#moj_import <aurahud:ah_layout.glsl>

#if AH_HOTBAR_SLICE
flat out int ahSlice;
out vec2 ahLocal;
out vec4 ahUvA;
out vec4 ahUvC;
#endif

bool ah_items_atlas(vec2 uv) {
    ivec2 size = textureSize(Sampler0, 0);
    if (size.x != size.y || size.x < 512 || (size.x & (size.x - 1)) != 0) {
        return false;
    }
    float n = float(size.x);
    vec2 t = vec2(uv.x * n, n - uv.y * n) / (16.0 * ah_gui_scale());
    return all(lessThan(abs(t - round(t)), vec2(0.002)));
}

int ah_family(vec2 uv, vec2 c) {
    ivec2 size = textureSize(Sampler0, 0);
    ivec2 t = ivec2(round(uv * vec2(size))) - ivec2(c);
    if (t.x < 0 || t.y < 0 || t.x >= size.x || t.y >= size.y) {
        return 0;
    }
    ivec4 m = ivec4(round(texelFetch(Sampler0, t, 0) * 255.0));
    return m.a == 0 && m.r == AH_MARK_R && m.b == AH_MARK_B ? m.g : 0;
}

bool ah_sprite_items(vec3 pos, vec2 c, vec2 s, inout int act, inout vec2 d) {
    float ry = pos.y - (s.y - 19.0);
    if (c.y < 0.5 ? !ah_in(ry, -6.0, 0.0) : !ah_in(ry, 16.0, 18.0)) {
        return false;
    }
    int slot = c.x < 0.5 ? ah_slot_at(pos.x, 0.0, 4.0, s) : ah_slot_at(pos.x, 12.0, 16.0, s);
    if (slot < 0) {
        return false;
    }
    ah_slot_move(slot, s, act, d);
    return true;
}

bool ah_sprite_hotbar(vec3 pos, vec2 uv, vec2 c, vec2 s, inout int act, inout vec2 d) {
    float cx = floor(s.x * 0.5);
    float h = s.y;
    vec2 o = pos.xy - c * vec2(182.0, 22.0);
    if (ah_eq(o.x, cx - 91.0) && ah_eq(o.y, h - 22.0)) {
        act = AH_HOTBAR;
#if AH_HOTBAR_SLICE
        vec2 corner = AH_HOTBAR_BOX + c * AH_HOTBAR_BOX_SIZE;
        d = ah_tl(AH_HOTBAR_ANCHOR, AH_HOTBAR_BASE, s) + corner - pos.xy;
        ahSlice = 1;
        ahLocal = corner;
        if (c.x < 0.5 && c.y < 0.5) {
            ahUvA = vec4(uv, 0.0, 1.0);
        } else if (c.x > 0.5 && c.y > 0.5) {
            ahUvC = vec4(uv, 0.0, 1.0);
        }
#else
        d = ah_slot_delta(0, s);
#endif
        return true;
    }
    o = pos.xy - c * vec2(24.0, 23.0);
    if (ah_eq(o.y, h - 23.0)) {
        int i = ah_index((o.x - (cx - 92.0)) / 20.0, 9);
        if (i >= 0) {
            act = ah_slot_act(i);
            d = ah_slot_delta(i, s);
            return true;
        }
    }
    o = pos.xy - c * vec2(29.0, 24.0);
    if (ah_eq(o.y, h - 23.0) && (ah_eq(o.x, cx - 120.0) || ah_eq(o.x, cx + 91.0))) {
        act = AH_OFFHAND;
        d = ah_offhand_delta(ah_eq(o.x, cx - 120.0) ? 0 : 1, s);
        return true;
    }
    float ax = pos.x - c.x * 18.0;
    bool right = ah_eq(ax, cx + 97.0);
    if ((right || ah_eq(ax, cx - 113.0)) && (c.y > 0.5 ? ah_eq(pos.y, h - 2.0) : ah_in(pos.y, h - 20.0, h - 2.0))) {
#if AH_HOTBAR == 2 || AH_HOTBAR_ATTACK == 0
        act = AH_HIDDEN;
#else
        act = AH_HOTBAR;
#endif
        d = ah_slot_delta(right ? 8 : 0, s) + AH_HOTBAR_ATTACK_OFFSET;
        return true;
    }
    return false;
}

void ah_sprite_marked(int fam, vec3 pos, vec2 c, vec2 s, inout int act, inout vec2 d) {
    float cx = floor(s.x * 0.5);
    float h = s.y;
    vec2 o = pos.xy - c * 9.0;
    if (fam == AH_FAMILY_HEART) {
#if AH_HEARTS != 0
        int col = ah_index((o.x - (cx - 91.0)) / 8.0, 10);
        if (col >= 0) {
            float yrel = o.y - (h - 39.0);
            int row = clamp(int(round(-yrel / 10.0)), 0, 3);
            act = AH_HEARTS;
            d = ah_tl(AH_HEARTS_ANCHOR, AH_HEARTS_BASE, s) + AH_HEARTS_POS[row * 10 + col] + vec2(0.0, yrel + 10.0 * float(row)) - o;
        }
#endif
    } else if (fam == AH_FAMILY_ARMOR) {
#if AH_ARMOR != 0
        int j = ah_index((o.x - (cx - 91.0)) / 8.0, 10);
        if (j >= 0) {
            float rows = -(o.y - (h - 49.0)) / 10.0;
            act = AH_ARMOR;
            d = ah_tl(AH_ARMOR_ANCHOR, AH_ARMOR_BASE, s) + AH_ARMOR_POS[j] + floor(rows * AH_ARMOR_ROW_STEP + 0.5) - o;
        }
#endif
    } else if (fam == AH_FAMILY_FOOD) {
#if AH_FOOD != 0
        int j = ah_index((cx + 82.0 - o.x) / 8.0, 10);
        float jit = o.y - (h - 39.0);
        if (j >= 0 && ah_in(jit, -1.0, 1.0)) {
            act = AH_FOOD;
            d = ah_tl(AH_FOOD_ANCHOR, AH_FOOD_BASE, s) + AH_FOOD_POS[j] + vec2(0.0, jit) - o;
        }
#endif
    } else if (fam == AH_FAMILY_AIR) {
#if AH_AIR != 0
        int j = ah_index((cx + 82.0 - o.x) / 8.0, 10);
        float yrel = o.y - (h - 49.0);
        float rows = round(yrel / 10.0);
        float jit = yrel - 10.0 * rows;
        if (j >= 0 && ah_in(jit, 0.0, 1.0)) {
            act = AH_AIR;
            d = ah_tl(AH_AIR_ANCHOR, AH_AIR_BASE, s) + AH_AIR_POS[j] + vec2(0.0, jit) - rows * AH_AIR_ROW_STEP - o;
        }
#endif
    } else if (fam == AH_FAMILY_MOUNT) {
#if AH_MOUNT != 0
        int j = ah_index((cx + 82.0 - o.x) / 8.0, 10);
        int row = ah_index((h - 39.0 - o.y) / 10.0, 3);
        if (j >= 0 && row >= 0) {
            act = AH_MOUNT;
            d = ah_tl(AH_MOUNT_ANCHOR, AH_MOUNT_BASE, s) + AH_MOUNT_POS[row * 10 + j] - o;
        }
#endif
    }
}

bool ah_xp_member(vec3 pos, vec2 c, bool white, vec2 s) {
    float cx = floor(s.x * 0.5);
    float h = s.y;
    if (white && ah_in(pos.x, cx - 91.0, cx + 91.0) && (ah_eq(pos.y, h - 29.0) || ah_eq(pos.y, h - 24.0))) {
        return true;
    }
    if (!ah_in(pos.x, cx - 100.0, cx + 100.0)) {
        return false;
    }
    if (c.y < 0.5 ? ah_eq(pos.y, h - 31.0) : ah_eq(pos.y, h - 22.0)) {
        return true;
    }
    return c.y < 0.5 ? (ah_eq(pos.y, h - 35.0) || ah_eq(pos.y, h - 23.0)) : (ah_eq(pos.y, h - 30.0) || ah_eq(pos.y, h - 18.0));
}

void ah_sprite_vertex(vec3 pos, vec2 uv, vec4 color, int vertexId) {
#if AH_HOTBAR_SLICE
    ahSlice = 0;
    ahLocal = vec2(0.0);
    ahUvA = vec4(0.0);
    ahUvC = vec4(0.0);
#endif
#if AH_ACTIVE
    if (ah_skip(pos)) {
        return;
    }
    vec2 s = ah_screen();
    vec2 c = ah_blit_corner(vertexId % 4);
    bool white = ivec4(round(color * 255.0)) == ivec4(255);
    int act = AH_VANILLA;
    vec2 d = vec2(0.0);
    if (!(white && ah_items_atlas(uv) && ah_sprite_items(pos, c, s, act, d))) {
        int fam = ah_family(uv, c);
        if (fam > 0) {
            ah_sprite_marked(fam, pos, c, s, act, d);
        } else if (!(white && ah_sprite_hotbar(pos, uv, c, s, act, d)) && ah_xp_member(pos, c, white, s)) {
#if AH_XP != 0
            act = AH_XP;
            d = ah_xp_place(pos.xy, s) - pos.xy;
#endif
        }
    }
    ah_apply(act, d);
#endif
}
