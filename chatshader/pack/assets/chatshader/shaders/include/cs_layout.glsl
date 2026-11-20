#version 330

#moj_import <minecraft:projection.glsl>
#moj_import <chatshader:cs_config.glsl>

#define CS_CHAT_BOTTOM 40.0
#define CS_CHAT_MAX_HEIGHT 180.0
#define CS_CHAT_MAX_WIDTH 332.0
#define CS_CHAT_RIGHT_LIMIT 345.0
#define CS_CHAT_REACH 260.0
#define CS_INPUT_TOP 14.0
#define CS_INPUT_BOTTOM 2.0
#define CS_LIST_BOTTOM 15.0
#define CS_LIST_REACH 160.0
#define CS_NOTCH 4.0

bool cs_is_gui() {
    return ProjMat[3][3] == 1.0 && ProjMat[2][3] == 0.0 && ProjMat[1][1] < 0.0;
}

vec2 cs_screen() {
    return ceil(vec2(2.0 / ProjMat[0][0], -2.0 / ProjMat[1][1]) - 0.001);
}

vec2 cs_shift(vec2 screen) {
    float m = CS_MARGIN;
    int p = CS_POSITION;
    float dx = (p == 2 || p == 3) ? max(0.0, screen.x - m - CS_CHAT_MAX_WIDTH) : m;
    float dy = (p == 1 || p == 2) ? min(0.0, m + CS_CHAT_BOTTOM + CS_CHAT_MAX_HEIGHT - screen.y) : -m;
    return vec2(dx, dy);
}

bool cs_near(float a, float b) {
    return abs(a - b) < 0.01;
}

bool cs_gui_scrollbar(ivec4 k) {
    bool a = k.a == 96 || k.a == 170;
    return a && (k.rgb == ivec3(51, 51, 170) || k.rgb == ivec3(204, 51, 51) || k.rgb == ivec3(204, 204, 204));
}
