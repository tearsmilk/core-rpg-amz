#version 330

#moj_import <tabshader:th_common.glsl>
#moj_import <tabshader:th_layout.glsl>

#define TH_RADIUS TH_RADIUS_HUD

flat in int thGuiKind;
flat in vec2 thGuiScreen;
in vec2 thGuiPos;
in vec4 thGuiA;
in vec4 thGuiC;
in vec2 thGuiOk;

bool th_gui_inside(vec2 p, vec4 rect) {
    return p.x >= rect.x && p.x <= rect.z && p.y >= rect.y && p.y <= rect.w;
}

vec4 th_gui_plain(vec4 color, vec2 p, vec4 rect) {
    return th_gui_inside(p, rect) ? color : vec4(0.0);
}

vec4 th_gui_card(vec2 p, vec4 rect, vec4 grow, vec4 radii, vec4 open, float divider) {
    float px = max(fwidth(p.x), 1e-4);
    vec2 lo = rect.xy - grow.xy;
    vec2 hi = rect.zw + grow.zw;
    vec4 c = th_panel(p, lo, hi, radii, px, open);
    if (divider > 1.0 && c.a > 0.0) {
        float line = 1.0 - smoothstep(0.0, px, abs(p.y - rect.y) - px * 0.5);
        float fade = smoothstep(lo.x + 2.0, lo.x + 8.0, p.x) * smoothstep(hi.x - 2.0, hi.x - 8.0, p.x);
        c.rgb = mix(c.rgb, vec3(1.0, 0.9, 0.94), line * fade * 0.6);
    }
    return c;
}

vec2 th_gui_corner(vec4 v) {
    float w = max(v.w, 1e-6);
    return vec2(v.x / w, th_tab_origin(v.z / w + 0.5 * 9.0 * float(TH_TAB_BAND)) + v.y / w);
}

bool th_gui_near(float v, float t) {
    return abs(v - t) < 0.01;
}

int th_gui_mod9(float v) {
    return int(round(v)) % 9;
}

bool th_gui_tab_row(vec4 rect, vec2 screen, float h) {
    int slot = th_gui_mod9(rect.y);
    return th_gui_near(h, 8.0) && rect.y >= 9.99 && (slot == 1 || slot == 2) && rect.x >= 10.0 && rect.z <= screen.x - 10.0;
}

vec4 th_gui_fragment(vec4 color) {
    if (thGuiOk.x < 0.001) {
        return color;
    }
    vec2 p = thGuiPos;
    vec4 rect = vec4(th_gui_corner(thGuiA), th_gui_corner(thGuiC));
    rect = vec4(min(rect.xy, rect.zw), max(rect.xy, rect.zw));
    float k = float(thGuiKind);
    if (thGuiKind == 0 || abs(thGuiOk.x - k) > 0.001 || abs(thGuiOk.y - k * k) > 0.01) {
        return th_gui_plain(color, p, rect);
    }
    vec2 screen = ceil(thGuiScreen - 0.001);
    float mid = 2.0 * floor(screen.x * 0.5);
    float h = rect.w - rect.y;
    int band = th_tab_code(rect.w);
    if (band >= 0) {
        if (th_tab_hidden(band) || thGuiKind != 1 || th_tab_code(rect.y) != band) {
            return vec4(0.0);
        }
        vec2 shift = th_tab_shift(band, screen);
        int slot = th_gui_mod9(rect.y);
        if (slot == 2) {
            return th_gui_card(p, rect + shift.xyxy, vec4(2.0, 0.0, 2.0, 2.0), vec4(0.0, 0.0, TH_RADIUS, TH_RADIUS), vec4(0.0, 1.0, 0.0, 0.0), 1.0);
        }
        if (slot == 1) {
            return th_gui_card(p, rect + shift.xyxy, vec4(2.0, 0.0, 2.0, 0.0), vec4(0.0), vec4(0.0, 1.0, 0.0, 1.0), 1.0);
        }
        return th_gui_plain(color, p, rect + shift.xyxy);
    }
    if (th_gui_tab_row(rect, screen, h)) {
        return vec4(0.0);
    }
    if (thGuiKind == 2) {
        return color;
    }
    if (thGuiKind == 1) {
        int slot = th_gui_mod9(rect.y);
        bool block = th_gui_mod9(h) == 1 && th_gui_near(fract(h + 0.5), 0.5) && slot <= 2;
        if (block && th_gui_near(rect.x + rect.z, mid) && rect.y >= 8.99 && rect.x >= 20.0) {
            if (slot == 0) {
                return th_gui_card(p, rect, vec4(2.0, 1.0, 2.0, 0.0), vec4(TH_RADIUS, TH_RADIUS, 0.0, 0.0), vec4(0.0, 0.0, 0.0, 1.0), 0.0);
            }
            if (slot == 2) {
                return th_gui_card(p, rect, vec4(2.0, 0.0, 2.0, 2.0), vec4(0.0, 0.0, TH_RADIUS, TH_RADIUS), vec4(0.0, 1.0, 0.0, 0.0), 1.0);
            }
            return th_gui_card(p, rect, vec4(2.0, 0.0, 2.0, 0.0), vec4(0.0), vec4(0.0, 1.0, 0.0, 1.0), 1.0);
        }
        return th_gui_plain(color, p, rect);
    }
    float g = thGuiKind == 3 ? 0.0 : 1.0;
    if (th_gui_near(rect.z, screen.x - 1.0) && rect.x > screen.x * 0.5) {
        if (th_gui_near(h, 9.0)) {
            return th_gui_card(p, rect, vec4(g, g, 0.0, 0.0), vec4(TH_RADIUS, TH_RADIUS, 0.0, 0.0), vec4(0.0, 0.0, 0.0, 1.0), 0.0);
        }
        if (th_gui_mod9(h) == 1 && th_gui_near(fract(h + 0.5), 0.5)) {
            return th_gui_card(p, rect, vec4(g, 0.0, 0.0, g), vec4(0.0, 0.0, TH_RADIUS, TH_RADIUS), vec4(0.0, 1.0, 0.0, 0.0), 1.0);
        }
        return th_gui_card(p, rect, vec4(g, g, 0.0, g), vec4(TH_RADIUS), vec4(0.0), 0.0);
    }
    float half_h = floor(screen.y * 0.5);
    bool centered = abs(rect.x + rect.z - mid) <= 4.01;
    bool bar = th_gui_near(h, 13.0) && th_gui_near(rect.y, screen.y - 74.0);
    bool big = th_gui_near(h, 52.0) && th_gui_near(rect.y, half_h - 48.0);
    bool sub = th_gui_near(h, 26.0) && th_gui_near(rect.y, half_h + 6.0);
    if (centered && (bar || big || sub)) {
        return vec4(0.0);
    }
    return th_gui_plain(color, p, rect);
}
