#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <tabshader:th_layout.glsl>

#define TH_MARK_R 231
#define TH_MARK_TEXEL ivec4(231, 7, 77, 255)

uniform sampler2D Sampler0;

flat out int thTextKind;
flat out vec4 thTextBox;
out vec2 thTextLocal;

vec4 th_text_layout(int kind) {
    if (kind == 2) {
        return vec4(4.0, 3.0, 2.0, 1.25);
    }
    if (kind == 3) {
        return vec4(2.0, 3.0, 2.0, 2.0);
    }
    return vec4(1.0, 4.0, 2.0, 3.0);
}

void th_text_panel(int kind, vec4 rect, vec3 pos, bool right, bool bottom, vec4 color, inout vec4 outColor) {
    vec2 g = vec2(right ? rect.z : rect.x, bottom ? rect.w : rect.y);
    thTextKind = kind;
    thTextBox = rect;
    thTextLocal = g;
    gl_Position = ProjMat * ModelViewMat * vec4(g, pos.z, 1.0);
    outColor = vec4(1.0, 1.0, 1.0, color.a);
}

void th_text_vertex(vec3 pos, vec4 color, vec2 uv, int vertexId, inout vec4 outColor) {
    thTextKind = 0;
    thTextBox = vec4(0.0);
    thTextLocal = vec2(0.0);
    ivec3 c = ivec3(round(color.rgb * 255.0));
    int kind = c.g >> 4;
    bool place = c.r == TH_MARK_R + 1 || c.r == TH_MARK_R + 2;
    if (!place && (c.r != TH_MARK_R || kind < 1 || kind > 4)) {
        return;
    }
    int corner = vertexId % 4;
    vec2 atlas = vec2(textureSize(Sampler0, 0));
    vec2 inward = vec2(corner >= 2 ? -0.5 : 0.5, (corner == 1 || corner == 2) ? -0.5 : 0.5) / atlas;
    ivec4 texel = ivec4(round(textureLod(Sampler0, uv + inward, 0.0) * 255.0));
    if (texel != TH_MARK_TEXEL) {
        return;
    }
    if (ProjMat[2][3] != 0.0) {
        outColor.a = 0.0;
        return;
    }
    bool right = corner >= 2;
    bool bottom = corner == 1 || corner == 2;
    if (place) {
        outColor.a = 0.0;
        if (!th_is_gui()) {
            return;
        }
        vec2 screen = th_screen();
        if (c.r == TH_MARK_R + 1) {
            int tab = th_tab_code(pos.y);
            if (tab < 0) {
                return;
            }
            vec2 shift = th_tab_shift(tab, screen);
            float line = pos.y - (bottom ? 8.0 : 0.0);
            float mid = floor(screen.x * 0.5);
            float half_w = th_tab_half() + 3.0;
            vec4 rect = vec4(mid - half_w, line - 2.0, mid + half_w, line + 9.0 * float(c.g & 15)) + shift.xyxy;
            th_text_panel(5, rect, pos, right, bottom, color, outColor);
            return;
        }
        int board = th_board_code(pos.x, screen.x);
        if (board < 0) {
            return;
        }
        vec2 shift = th_board_shift(board, screen);
        vec4 frame = th_board_frame(board, screen);
        float width = float(((c.g & 15) << 8) | c.b);
        bool from_right = th_board_right(th_board_slot(board));
        float edge = frame.x + (from_right ? -1.0 : 1.0) * float(c.g >> 4);
        float lo = from_right ? edge - width - 3.0 : edge - 3.0;
        float hi = from_right ? edge + 3.0 : edge + width + 3.0;
        th_text_panel(6, vec4(lo, frame.y, hi, frame.z) + shift.xyxy, pos, right, bottom, color, outColor);
        return;
    }
    float width = float(((c.g & 15) << 8) | c.b);
    vec4 lay = th_text_layout(kind);
    float s = lay.x;
    vec2 pad = lay.yz;
    vec2 size = vec2(width + 2.0 * pad.x, 8.0 + 2.0 * pad.y);
    vec2 g = pos.xy;
    g.x += right ? (width + pad.x - 8.0) * s : -pad.x * s;
    g.y += bottom ? pad.y * s : -pad.y * s;
    thTextKind = kind;
    thTextBox = vec4(size, lay.w, s);
    thTextLocal = vec2(right ? size.x : 0.0, bottom ? size.y : 0.0);
    gl_Position = ProjMat * ModelViewMat * vec4(g, pos.z, 1.0);
    outColor = vec4(1.0, 1.0, 1.0, color.a);
}
