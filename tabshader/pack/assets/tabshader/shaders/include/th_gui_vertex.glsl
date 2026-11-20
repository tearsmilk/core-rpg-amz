#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <tabshader:th_layout.glsl>

#define TH_GROW_TAB 2.0
#define TH_GROW_BOARD 1.0

flat out int thGuiKind;
flat out vec2 thGuiScreen;
out vec2 thGuiPos;
out vec4 thGuiA;
out vec4 thGuiC;
out vec2 thGuiOk;

int th_gui_candidate(vec4 color) {
    ivec4 k = ivec4(round(color * 255.0));
    if (k.r == 0 && k.g == 0 && k.b == 0) {
        if (k.a == 128) {
            return 1;
        }
        if (k.a == 102) {
            return 4;
        }
        if (k.a == 76) {
            return 5;
        }
        return k.a > 0 ? 3 : 0;
    }
    if (k == ivec4(255, 255, 255, 32)) {
        return 2;
    }
    return 0;
}

void th_gui_vertex(vec3 pos, vec4 color, int vertexId) {
    thGuiKind = 0;
    thGuiScreen = vec2(0.0);
    thGuiPos = pos.xy;
    thGuiA = vec4(0.0);
    thGuiC = vec4(0.0);
    thGuiOk = vec2(0.0);
    if (!th_is_gui()) {
        return;
    }
    int corner = vertexId % 4;
    float origin = th_tab_origin(pos.y);
    if (corner == 0) {
        thGuiA = vec4(pos.x, pos.y - origin, origin, 1.0);
    } else if (corner == 2) {
        thGuiC = vec4(pos.x, pos.y - origin, origin, 1.0);
    }
    vec2 screen = th_screen();
    vec2 side = vec2(corner >= 2 ? -1.0 : 1.0, (corner == 1 || corner == 2) ? -1.0 : 1.0);
    int kind = th_gui_candidate(color);
    int tab = th_tab_code(pos.y);
    if (tab < 0 && th_board_code(pos.x, screen.x) >= 0) {
        thGuiPos = vec2(screen.x + 8.0, pos.y);
        gl_Position = ProjMat * ModelViewMat * vec4(thGuiPos, pos.z, 1.0);
        return;
    }
    if (tab >= 0) {
        int slot = int(round(pos.y)) % 9;
        bool bottom = corner == 0 || corner == 3;
        if (kind == 2 || (kind == 1 && slot == 1 && bottom)) {
            float half_w = th_tab_half() + 1.0 + TH_GROW_TAB;
            thGuiPos = vec2(floor(screen.x * 0.5) + (side.x > 0.0 ? half_w : -half_w), 9.0 - TH_GROW_TAB);
            gl_Position = ProjMat * ModelViewMat * vec4(thGuiPos, pos.z, 1.0);
            return;
        }
        vec2 moved = pos.xy + th_tab_shift(tab, screen);
        if (kind == 1) {
            thGuiKind = 1;
            thGuiOk = vec2(1.0, 1.0);
            thGuiScreen = vec2(2.0 / ProjMat[0][0], -2.0 / ProjMat[1][1]);
            moved += side * TH_GROW_TAB;
        }
        thGuiPos = moved;
        gl_Position = ProjMat * ModelViewMat * vec4(moved, pos.z, 1.0);
        return;
    }
    if (kind == 0) {
        return;
    }
    thGuiKind = kind;
    thGuiOk = vec2(float(kind), float(kind * kind));
    thGuiScreen = vec2(2.0 / ProjMat[0][0], -2.0 / ProjMat[1][1]);
    float grow = 0.0;
    if (kind == 1) {
        grow = TH_GROW_TAB;
    } else if (kind == 4 || kind == 5) {
        grow = TH_GROW_BOARD;
    }
    if (grow > 0.0) {
        vec2 grown = pos.xy + side * grow;
        thGuiPos = grown;
        gl_Position = ProjMat * ModelViewMat * vec4(grown, pos.z, 1.0);
    }
}
