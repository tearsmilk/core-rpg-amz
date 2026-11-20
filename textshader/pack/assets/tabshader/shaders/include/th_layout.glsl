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
#define TH_BOARD_CODES 31488
#define TH_BOARD_PLAIN 1536
#define TH_BOARD_EXACT 168
#define TH_BOARD_CENTRED 10752
#define TH_BOARD_SAME 5376
#define TH_BOARD_COARSE 9
#define TH_BOARD_COARSE_STEP 32.0
#define TH_TAB_MARGIN_LEVELS 6
#define TH_TAB_MARGIN_STEP 3.0
#define TH_TAB_MAX_MARGIN 15.0
#define TH_TAB_PANEL_EXTRA 6.0
#define TH_BOARD_GAP 3.0
#define TH_BOARD_REACH 236.0
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

vec4 th_board_exact(int end, int exact) {
    float m = TH_TAB_MARGIN_STEP * float(exact % TH_TAB_MARGIN_LEVELS);
    float lines = float(exact / TH_TAB_MARGIN_LEVELS + 1);
    return vec4(1.0, float(end), m + 9.0 * lines + TH_TAB_PANEL_EXTRA, m);
}

ivec3 th_board_fields(int code, out vec4 tab) {
    tab = vec4(0.0);
    if (code < TH_BOARD_PLAIN) {
        return ivec3(code / 256, (code / 16) % 16, code % 16);
    }
    int r = code - TH_BOARD_PLAIN;
    if (r < TH_BOARD_CENTRED) {
        int exact = r % TH_BOARD_EXACT;
        r = r / TH_BOARD_EXACT;
        int n = r % 16;
        r = r / 16;
        tab = th_board_exact(r % 2, exact);
        return ivec3(r / 2 == 0 ? 0 : 5, n, 0);
    }
    r -= TH_BOARD_CENTRED;
    if (r < TH_BOARD_SAME) {
        int exact = r % TH_BOARD_EXACT;
        r = r / TH_BOARD_EXACT;
        tab = th_board_exact(0, exact);
        return ivec3(r / 16 + 1, r % 16, int(tab.w));
    }
    r -= TH_BOARD_SAME;
    int coarse = r % TH_BOARD_COARSE;
    r = r / TH_BOARD_COARSE;
    int margin = r % 16;
    r = r / 16;
    int pair = r / 16;
    tab = vec4(1.0, pair < 2 || pair >= 4 ? 1.0 : 0.0, TH_BOARD_COARSE_STEP * float(coarse + 1), TH_TAB_MAX_MARGIN);
    return ivec3(pair < 4 ? pair + 1 : pair - 1, r % 16, margin);
}

int th_board_slot(int code) {
    vec4 tab;
    return th_board_fields(code, tab).x;
}

bool th_board_right(int pos) {
    return pos == 2 || pos == 4 || pos == 5;
}

vec4 th_board_frame(int code, vec2 screen) {
    vec4 tab;
    ivec3 f = th_board_fields(code, tab);
    float k = float(code + TH_BOARD_BASE);
    float cell = float(TH_BOARD_CELL);
    float n = float(f.y);
    float bottom = floor(screen.y * 0.5) + 3.0 * n;
    float top = bottom - 9.0 * n;
    float edge = th_board_right(f.x) ? screen.x - 5.0 - TH_BOARD_SLACK - cell * (k - 1.0) : screen.x - 3.0 + TH_BOARD_SLACK - cell * k;
    return vec4(edge, top - 11.0, bottom + 1.0, n);
}

vec2 th_board_shift(int code, vec2 screen) {
    vec4 tab;
    ivec3 f = th_board_fields(code, tab);
    vec4 frame = th_board_frame(code, screen);
    bool right = th_board_right(f.x);
    float m = float(f.z);
    float h = frame.z - frame.y;
    float lift = right ? TH_HOTBAR_LIFT : TH_CHAT_LIFT;
    bool high = f.x == 1 || f.x == 2;
    float top = frame.y;
    if (high) {
        top = m;
    } else if (f.x == 3 || f.x == 4) {
        top = screen.y - m - lift - h;
    }
    float side = 0.0;
    if (tab.x > 0.5) {
        bool down = tab.y < 0.5;
        float raise = tab.y > 0.5 && th_tab_narrow(tab.w, screen) ? TH_HOTBAR_LIFT : 0.0;
        float near = down ? tab.z : screen.y - raise - tab.z;
        bool hit = down ? top < near + TH_BOARD_GAP : top + h > near - TH_BOARD_GAP;
        if (hit) {
            float moved = down ? near + TH_BOARD_GAP : near - TH_BOARD_GAP - h;
            bool fits = down ? moved + h <= screen.y - lift : moved >= 2.0;
            if (fits) {
                top = moved;
            } else {
                float away = tab.w + 2.0 * th_tab_half() + 6.0;
                float mid = floor(screen.x * 0.5);
                float inner = right ? screen.x - away - 6.0 - TH_BOARD_REACH : away + 6.0 + TH_BOARD_REACH;
                bool clear = right ? inner >= mid + TH_HOTBAR_HALF : inner <= mid - TH_HOTBAR_HALF;
                if (clear) {
                    side = away;
                }
            }
        }
    }
    float dx = right ? screen.x - side - (frame.x + 3.0) : side - (frame.x - 3.0);
    return vec2(dx, top - frame.y);
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
