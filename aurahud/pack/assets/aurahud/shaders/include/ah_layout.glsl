#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <aurahud:ah_common.glsl>

bool ah_is_gui() {
    return ProjMat[3][3] == 1.0 && ProjMat[2][3] == 0.0 && ProjMat[1][1] < 0.0;
}

vec2 ah_screen() {
    return ceil(vec2(2.0 / ProjMat[0][0], -2.0 / ProjMat[1][1]) - 0.001);
}

float ah_gui_scale() {
    return max(1.0, round(ScreenSize.x * ProjMat[0][0] * 0.5));
}

bool ah_skip(vec3 pos) {
    return !ah_is_gui() || pos.y > 2601.0 || pos.x < -8192.0;
}

bool ah_eq(float a, float b) {
    return abs(a - b) < 0.01;
}

bool ah_in(float v, float lo, float hi) {
    return v > lo - 0.01 && v < hi + 0.01;
}

int ah_index(float v, int count) {
    float r = round(v);
    return abs(v - r) < 0.01 && r > -0.5 && r < float(count) - 0.5 ? int(r) : -1;
}

vec2 ah_blit_corner(int k) {
    return vec2(k >= 2 ? 1.0 : 0.0, (k == 1 || k == 2) ? 1.0 : 0.0);
}

vec2 ah_fill_corner(int k) {
    return vec2(k <= 1 ? 1.0 : 0.0, (k == 0 || k == 3) ? 1.0 : 0.0);
}

vec2 ah_anchor(vec2 a, vec2 s) {
    return vec2(a.x < 0.25 ? 0.0 : (a.x < 0.75 ? floor(s.x * 0.5) : s.x), a.y < 0.25 ? 0.0 : (a.y < 0.75 ? floor(s.y * 0.5) : s.y));
}

vec2 ah_tl(vec2 anchor, vec2 base, vec2 s) {
    return ah_anchor(anchor, s) + base;
}

void ah_apply(int act, vec2 d) {
    if (act == AH_HIDDEN) {
        gl_Position = vec4(-4.0, -4.0, 0.0, 1.0);
    } else if (act == AH_MOVED && (d.x != 0.0 || d.y != 0.0)) {
        gl_Position += ProjMat * ModelViewMat * vec4(d, 0.0, 0.0);
    }
}

vec2 ah_slot_origin(int i, vec2 s) {
    return vec2(floor(s.x * 0.5) - 91.0 + 20.0 * float(i), s.y - 22.0);
}

vec2 ah_slot_delta(int i, vec2 s) {
    return ah_tl(AH_HOTBAR_ANCHOR, AH_HOTBAR_BASE, s) + AH_HOTBAR_POS[i] - ah_slot_origin(i, s);
}

int ah_slot_act(int i) {
#if AH_HOTBAR == 2
    return AH_HIDDEN;
#elif AH_HOTBAR == 1
    return ((AH_HOTBAR_HIDDEN >> i) & 1) != 0 ? AH_HIDDEN : AH_MOVED;
#else
    return AH_VANILLA;
#endif
}

vec2 ah_offhand_delta(int side, vec2 s) {
#if AH_OFFHAND_FOLLOW
    return ah_slot_delta(side == 0 ? 0 : 8, s);
#else
    float cx = floor(s.x * 0.5);
    return ah_tl(AH_OFFHAND_ANCHOR, AH_OFFHAND_BASE, s) - vec2(side == 0 ? cx - 120.0 : cx + 91.0, s.y - 23.0);
#endif
}

void ah_slot_move(int slot, vec2 s, inout int act, inout vec2 d) {
    if (slot < 9) {
        act = ah_slot_act(slot);
        d = ah_slot_delta(slot, s);
    } else {
        act = AH_OFFHAND;
        d = ah_offhand_delta(slot - 9, s);
    }
}

int ah_slot_at(float x, float lo, float hi, vec2 s) {
    float cx = floor(s.x * 0.5);
    float r = x - (cx - 88.0);
    float i = round((r - 0.5 * (lo + hi)) / 20.0);
    if (i > -0.5 && i < 8.5 && ah_in(r - 20.0 * i, lo, hi)) {
        return int(i);
    }
    if (ah_in(x - (cx - 117.0), lo, hi)) {
        return 9;
    }
    if (ah_in(x - (cx + 101.0), lo, hi)) {
        return 10;
    }
    return -1;
}

vec2 ah_xp_place(vec2 p, vec2 s) {
    vec2 l = p - vec2(floor(s.x * 0.5) - 91.0, s.y - 29.0);
#if AH_XP_ROTATE == 1
    l = vec2(5.0 - l.y, l.x);
#elif AH_XP_ROTATE == 2
    l = vec2(182.0 - l.x, 5.0 - l.y);
#elif AH_XP_ROTATE == 3
    l = vec2(l.y, 182.0 - l.x);
#endif
    return ah_tl(AH_XP_ANCHOR, AH_XP_BASE, s) + l;
}

vec2 ah_xp_level_delta(vec2 s) {
    vec2 now = ah_tl(AH_XP_ANCHOR, AH_XP_BASE, s) + floor(AH_XP_SIZE * 0.5);
    return now - vec2(floor(s.x * 0.5), s.y - 27.0) + AH_XP_LEVEL_OFFSET;
}

vec2 ah_name_delta(vec2 s) {
    return ah_tl(AH_NAME_ANCHOR, AH_NAME_BASE, s) - vec2(floor(s.x * 0.5), s.y - 59.0);
}
