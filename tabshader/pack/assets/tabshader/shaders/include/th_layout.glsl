#version 330

#moj_import <minecraft:projection.glsl>

#define TH_TAB_WIDTH 205
#define TH_TAB_BAND 32
#define TH_TAB_BASE 9
#define TH_TAB_CODES 80
#define TH_TAB_CENTER 64
#define TH_TAB_NOPING 80
#define TH_TAB_BANDS 160
#define TH_BOSSBAR_ROW 19.0
#define TH_HOTBAR_HALF 92.0
#define TH_BOARD_CELL 256
#define TH_BOARD_BASE 64
#define TH_BOARD_CODES 26112
#define TH_BOARD_EDGE 8192.0
#define TH_BOARD_SLACK 8.0
#define TH_CHAT_LIFT 132.0
#define TH_HOTBAR_LIFT 60.0

bool th_is_gui() {
    return ProjMat[3][3] == 1.0 && ProjMat[2][3] == 0.0 && ProjMat[1][1] < 0.0;
}

vec2 th_screen() {
    return ceil(vec2(2.0 / ProjMat[0][0], -2.0 / ProjMat[1][1]) - 0.001);
}

float th_tab_half() {
    return float(TH_TAB_WIDTH / 2);
}

int th_tab_code(float y) {
    float c = floor((y - 9.0) / (9.0 * float(TH_TAB_BAND))) - float(TH_TAB_BASE);
    if (c < 0.0 || c >= float(TH_TAB_BANDS)) {
        return -1;
    }
    return int(c);
}

float th_tab_origin(float y) {
    int band = th_tab_code(y);
    return band < 0 ? 0.0 : 9.0 + 9.0 * float(TH_TAB_BAND) * float(band + TH_TAB_BASE);
}

bool th_tab_noping(vec3 pos) {
    return th_is_gui() && th_tab_code(pos.y) >= TH_TAB_NOPING;
}

bool th_tab_narrow(float m, vec2 screen) {
    return screen.x < 2.0 * (float(TH_TAB_WIDTH) + m + 6.0) + 2.0 * TH_HOTBAR_HALF;
}

vec2 th_tab_shift(int band, vec2 screen) {
    float c = float(band + TH_TAB_BASE);
    float b = float(TH_TAB_BAND);
    int code = band % TH_TAB_CODES;
    if (code >= TH_TAB_CENTER) {
        return vec2(0.0, max(TH_BOSSBAR_ROW * float(code - TH_TAB_CENTER) + 2.0, 9.0) - (9.0 + 9.0 * (b * c + 2.0)));
    }
    int p = code & 3;
    float m = float(code >> 2);
    float mid = floor(screen.x * 0.5);
    float left = mid - th_tab_half() - 1.0;
    float right = mid + th_tab_half() + 1.0;
    float dx = (p & 1) == 0 ? m + 2.0 - left : screen.x - m - 2.0 - right;
    float dy = p < 2 ? m + 1.0 - (9.0 + 9.0 * (b * c + 2.0)) : screen.y - m - 2.0 - (12.0 + 9.0 * (b * c + b - 2.0));
    if (p >= 2 && th_tab_narrow(m, screen)) {
        dy -= TH_HOTBAR_LIFT;
    }
    return vec2(dx, dy);
}

int th_board_code(float x, float sw) {
    if (x > -TH_BOARD_EDGE) {
        return -1;
    }
    float k = ceil((sw - 3.0 - x) / float(TH_BOARD_CELL)) - float(TH_BOARD_BASE);
    if (k < 0.0 || k >= float(TH_BOARD_CODES)) {
        return -1;
    }
    return int(k);
}

ivec4 th_board_fields(int code) {
    int margin = code % 16;
    int r = code / 16;
    int tab = r % 17;
    r = r / 17;
    return ivec4(r / 16, r % 16, tab, margin);
}

bool th_board_right(int pos) {
    return pos == 2 || pos == 4 || pos == 5;
}

vec4 th_board_frame(int code, vec2 screen) {
    ivec4 f = th_board_fields(code);
    float k = float(code + TH_BOARD_BASE);
    float cell = float(TH_BOARD_CELL);
    float n = float(f.y);
    float bottom = floor(screen.y * 0.5) + 3.0 * n;
    float top = bottom - 9.0 * n;
    float edge = th_board_right(f.x) ? screen.x - 5.0 - TH_BOARD_SLACK - cell * (k - 1.0) : screen.x - 3.0 + TH_BOARD_SLACK - cell * k;
    return vec4(edge, top - 11.0, bottom + 1.0, n);
}

vec2 th_board_shift(int code, vec2 screen) {
    ivec4 f = th_board_fields(code);
    vec4 frame = th_board_frame(code, screen);
    float m = float(f.w);
    float side = f.z > 0 ? float(f.z - 1) + 2.0 * th_tab_half() + 6.0 : 0.0;
    float dx = th_board_right(f.x) ? screen.x - m - side - (frame.x + 3.0) : m + side - (frame.x - 3.0);
    float dy = 0.0;
    if (f.x == 1 || f.x == 2) {
        dy = m - frame.y;
        float reach = th_board_right(f.x) ? screen.x - m - side - 6.0 - float(TH_BOARD_CELL - 20) : m + side + 6.0 + float(TH_BOARD_CELL - 20);
        float mid = floor(screen.x * 0.5);
        bool covers = th_board_right(f.x) ? reach < mid + TH_HOTBAR_HALF : reach > mid - TH_HOTBAR_HALF;
        if (f.z > 0 && covers) {
            dy = max(m, TH_BOSSBAR_ROW + 2.0) - frame.y;
        }
    } else if (f.x == 3) {
        dy = screen.y - m - TH_CHAT_LIFT - frame.z;
    } else if (f.x == 4) {
        dy = screen.y - m - TH_HOTBAR_LIFT - frame.z;
    }
    return vec2(dx, dy);
}

vec3 th_layout_shift(vec3 pos) {
    if (!th_is_gui()) {
        return pos;
    }
    vec2 screen = th_screen();
    int tab = th_tab_code(pos.y);
    if (tab >= 0) {
        return vec3(pos.xy + th_tab_shift(tab, screen), pos.z);
    }
    int board = th_board_code(pos.x, screen.x);
    if (board >= 0) {
        return vec3(pos.xy + th_board_shift(board, screen), pos.z);
    }
    return pos;
}
