#version 330

#moj_import <tabshader:th_common.glsl>

#define TH_RADIUS 3.0

flat in int thGuiKind;
flat in vec2 thGuiScreen;
in vec2 thGuiPos;
in vec3 thGuiA;
in vec3 thGuiC;

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
    if (divider > 0.0 && c.a > 0.0) {
        float line = 1.0 - smoothstep(0.0, px, abs(p.y - rect.y) - px * 0.5);
        float fade = smoothstep(lo.x + 2.0, lo.x + 8.0, p.x) * smoothstep(hi.x - 2.0, hi.x - 8.0, p.x);
        c.rgb = mix(c.rgb, TH_PINK, line * fade * 0.55);
    }
    return c;
}

vec4 th_gui_fragment(vec4 color) {
    if (thGuiKind == 0) {
        return color;
    }
    vec2 p = thGuiPos;
    vec4 rect = vec4(thGuiA.xy / max(thGuiA.z, 1e-6), thGuiC.xy / max(thGuiC.z, 1e-6));
    rect = vec4(min(rect.xy, rect.zw), max(rect.xy, rect.zw));
    vec2 screen = ceil(thGuiScreen - 0.001);
    float mid = 2.0 * floor(screen.x * 0.5);
    float w = rect.z - rect.x;
    float h = rect.w - rect.y;
    if (thGuiKind == 2) {
        if (abs(h - 8.0) < 0.01 && rect.y >= 8.99) {
            return vec4(0.0);
        }
        return color;
    }
    if (thGuiKind == 1) {
        if (abs(rect.x + rect.z - mid) < 0.01 && rect.y >= 8.99 && rect.x >= 20.0) {
            int slot = int(round(rect.y)) % 9;
            if (slot == 0) {
                return th_gui_card(p, rect, vec4(2.0, 2.0, 2.0, 0.0), vec4(TH_RADIUS, TH_RADIUS, 0.0, 0.0), vec4(0.0, 0.0, 0.0, 1.0), 0.0);
            }
            if (slot == 2) {
                return th_gui_card(p, rect, vec4(2.0, 0.0, 2.0, 2.0), vec4(0.0, 0.0, TH_RADIUS, TH_RADIUS), vec4(0.0, 1.0, 0.0, 0.0), 1.0);
            }
            return th_gui_card(p, rect, vec4(2.0, 0.0, 2.0, 0.0), vec4(0.0), vec4(0.0, 1.0, 0.0, 1.0), 1.0);
        }
        return th_gui_plain(color, p, rect);
    }
    if (abs(rect.z - (screen.x - 1.0)) < 0.01 && rect.x > screen.x * 0.5) {
        int a = int(round(color.a * 255.0));
        if (a == 102) {
            return th_gui_card(p, rect, vec4(1.0, 1.0, 0.0, 0.0), vec4(TH_RADIUS, TH_RADIUS, 0.0, 0.0), vec4(0.0, 0.0, 0.0, 1.0), 0.0);
        }
        if (a == 76) {
            return th_gui_card(p, rect, vec4(1.0, 0.0, 0.0, 1.0), vec4(0.0, 0.0, TH_RADIUS, TH_RADIUS), vec4(0.0, 1.0, 0.0, 0.0), 1.0);
        }
        return th_gui_card(p, rect, vec4(1.0, 0.0, 0.0, 0.0), vec4(TH_RADIUS), vec4(0.0), 0.0);
    }
    float half_h = floor(screen.y * 0.5);
    bool centered = abs(rect.x + rect.z - mid) <= 4.01;
    bool bar = abs(h - 13.0) < 0.01 && abs(rect.y - (screen.y - 74.0)) < 0.01;
    bool big = abs(h - 52.0) < 0.01 && abs(rect.y - (half_h - 48.0)) < 0.01;
    bool sub = abs(h - 26.0) < 0.01 && abs(rect.y - (half_h + 6.0)) < 0.01;
    if (centered && (bar || big || sub)) {
        return vec4(0.0);
    }
    return th_gui_plain(color, p, rect);
}
