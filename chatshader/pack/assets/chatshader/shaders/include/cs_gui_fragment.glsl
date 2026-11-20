#version 330

#moj_import <chatshader:cs_common.glsl>
#moj_import <chatshader:cs_layout.glsl>

in vec4 csGuiA;
in vec4 csGuiC;
in vec2 csGuiPos;
in float csGuiTouched;
flat in vec4 csGuiFrame;

vec2 cs_gui_corner(vec4 v) {
    return v.xy / max(v.w, 1e-6);
}

bool cs_gui_inside(vec2 p, vec2 lo, vec2 hi) {
    return p.x >= lo.x && p.x <= hi.x && p.y >= lo.y && p.y <= hi.y;
}

vec4 cs_paint(vec2 g, float d_shape, float d_rim, float px, float alpha) {
    float cover = clamp(0.5 - d_shape / px, 0.0, 1.0);
    if (cover <= 0.0) {
        return vec4(0.0);
    }
    vec4 c = cs_shade(g, d_rim, px, alpha);
    return vec4(c.rgb, c.a * cover);
}

vec4 cs_line(vec2 q, vec2 lo, vec2 hi, bool first, float px, float alpha) {
    float r = CS_RADIUS;
    vec2 open = vec2(0.0, 16.0);
    if (q.y <= hi.y || first) {
        if (first) {
            float d_shape = cs_rect_sdf(q, lo, hi, vec4(r));
            float d_rim = cs_rect_sdf(q, lo - open, hi, vec4(0.0, 0.0, r, r));
            return cs_paint(q, d_shape, d_rim, px, alpha);
        }
        float d_shape = cs_rect_sdf(q, lo, hi + open, vec4(r, r, 0.0, 0.0));
        float d_rim = cs_rect_sdf(q, lo - open, hi + open, vec4(0.0));
        vec4 c = cs_paint(q, d_shape, d_rim, px, alpha);
        return q.y <= hi.y ? c : vec4(0.0);
    }
    if (q.y > hi.y + r + 1.0) {
        return vec4(0.0);
    }
    vec2 lo2 = vec2(lo.x, hi.y);
    vec2 hi2 = vec2(hi.x, hi.y + (hi.y - lo.y));
    float d_open = cs_rect_sdf(q, lo2 - open, hi2 + open, vec4(0.0));
    float d_cut = cs_rect_sdf(q, lo2, hi2 + open, vec4(r, r, 0.0, 0.0));
    float notch = clamp(0.5 - d_open / px, 0.0, 1.0) - clamp(0.5 - d_cut / px, 0.0, 1.0);
    if (notch <= 0.0) {
        return vec4(0.0);
    }
    vec4 c = cs_shade(q, d_open, px, alpha);
    return vec4(c.rgb, c.a * notch);
}

vec4 cs_pill(vec2 q, vec2 lo, vec2 hi, float r, float px, float alpha) {
    float d = cs_rect_sdf(q, lo, hi, vec4(min(r, 0.5 * min(hi.x - lo.x, hi.y - lo.y))));
    return cs_paint(q, d, d, px, alpha);
}

vec4 cs_gui_fragment(vec4 color, vec4 vertexColor) {
    vec2 p = csGuiPos;
    float px = max(fwidth(p.x), 1e-4);
    if (csGuiFrame.x <= 0.0) {
        return color;
    }
    vec2 screen = csGuiFrame.xy;
    vec2 shift = csGuiFrame.zw;
    vec2 a = cs_gui_corner(csGuiA);
    vec2 c = cs_gui_corner(csGuiC);
    vec2 lo = min(a, c);
    vec2 hi = max(a, c);
    vec2 size = hi - lo;
    ivec4 k = ivec4(round(vertexColor * 255.0));
    bool black = k.rgb == ivec3(0);
    float bottom = screen.y - CS_CHAT_BOTTOM;
    vec2 q = p - shift;
    float list = screen.y - CS_LIST_BOTTOM;
    bool listZone = hi.y <= list + 0.5 && hi.y >= list - CS_LIST_REACH;
    bool listRow = black && k.a == 208 && listZone && abs(size.y - 12.0) < 0.01 && abs(fract((list - hi.y) / 12.0 + 0.5) - 0.5) < 0.001;
    if (black && k.a > 0 && !listRow) {
        bool column = hi.x >= 20.0 && hi.x <= CS_CHAT_RIGHT_LIMIT && hi.y <= bottom + 0.5 && hi.y >= bottom - CS_CHAT_REACH;
        if (column && cs_near(lo.x, 0.0) && size.y >= 3.0 && size.y <= 30.0) {
            bool first = hi.y > bottom - 1.5;
            return cs_line(q, lo, hi, first, px, cs_alpha(vertexColor.a, CS_ALPHA));
        }
        if (lo.x >= 1.5 && lo.x <= 2.5 && hi.x <= CS_CHAT_RIGHT_LIMIT && abs(lo.y - bottom) < 1.5 && size.y >= 3.0 && size.y <= 30.0) {
            return cs_pill(q, lo, hi, CS_RADIUS, px, cs_alpha(vertexColor.a, CS_ALPHA));
        }
        if (cs_near(lo.x, 2.0) && cs_near(hi.x, screen.x - 2.0) && cs_near(lo.y, screen.y - CS_INPUT_TOP) && cs_near(hi.y, screen.y - CS_INPUT_BOTTOM)) {
            return cs_pill(p, lo, hi, CS_RADIUS, px, cs_alpha(vertexColor.a, CS_INPUT_ALPHA));
        }
    }
    if (listRow) {
        vec4 row = cs_line(p, lo, hi, cs_near(hi.y, list), px, clamp(vertexColor.a / 0.816 * CS_LIST_ALPHA, 0.0, 0.97));
        return vec4(row.rgb * CS_LIST_SHADE, row.a);
    }
    if (black && k.a == 208 && listZone && size.y <= 1.01) {
        return vec4(0.0);
    }
    bool column = lo.x >= -0.01 && hi.x <= CS_CHAT_RIGHT_LIMIT && hi.y <= bottom + 0.5 && lo.y >= bottom - CS_CHAT_REACH;
    if (column && cs_gui_scrollbar(k) && size.x <= 2.01) {
        if (k.rgb == ivec3(204, 204, 204)) {
            return vec4(0.0);
        }
        vec3 tint = k.r == 204 ? CS_PINK : CS_ROSE;
        vec4 bar = cs_pill(q, lo, hi, 1.0, px, 1.0);
        return vec4(mix(bar.rgb, tint, 0.6), bar.a * clamp(vertexColor.a * 1.4, 0.0, 1.0));
    }
    if (column && !black && k.a > 0 && cs_near(lo.x, 0.0) && hi.x <= 2.01) {
        vec4 tag = cs_pill(q, vec2(1.0, lo.y + 0.5), vec2(2.0, hi.y - 0.5), 0.6, px, 1.0);
        return vec4(color.rgb, color.a * tag.a);
    }
    if (csGuiTouched > 0.0) {
        return cs_gui_inside(p, lo, hi) ? color : vec4(0.0);
    }
    return color;
}
