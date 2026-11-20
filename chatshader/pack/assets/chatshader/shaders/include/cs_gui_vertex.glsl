#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <chatshader:cs_layout.glsl>

out vec4 csGuiA;
out vec4 csGuiC;
out vec2 csGuiPos;
out float csGuiTouched;
flat out vec4 csGuiFrame;

bool cs_list_grid(float y, vec2 screen) {
    float k = screen.y - CS_LIST_BOTTOM - y;
    float r = k - 12.0 * round(k / 12.0);
    return k >= -0.01 && k <= CS_LIST_REACH + 0.01 && abs(r) < 0.01;
}

int cs_gui_candidate(ivec4 k, vec3 pos, vec2 screen) {
    bool black = k.rgb == ivec3(0);
    bool column = pos.x >= -0.01 && pos.x <= CS_CHAT_RIGHT_LIMIT && pos.y >= screen.y - CS_CHAT_BOTTOM - CS_CHAT_REACH && pos.y <= screen.y - 30.0;
    if (black && k.a == 208 && cs_list_grid(pos.y, screen)) {
        return 3;
    }
    if (!column || k.a == 0) {
        return 0;
    }
    if (black) {
        return 1;
    }
    if (cs_gui_scrollbar(k)) {
        return 4;
    }
    return pos.x <= 2.01 ? 5 : 0;
}

void cs_gui_vertex(vec3 pos, vec4 color, int vertexId) {
    csGuiA = vec4(0.0);
    csGuiC = vec4(0.0);
    csGuiPos = pos.xy;
    csGuiTouched = 0.0;
    csGuiFrame = vec4(0.0);
    if (!cs_is_gui()) {
        return;
    }
    mat4 m = ProjMat * ModelViewMat;
    vec2 prior = (inverse(m) * gl_Position).xy;
    if (any(greaterThan(abs(prior - pos.xy), vec2(2.5)))) {
        return;
    }
    csGuiPos = prior;
    vec2 screen = cs_screen();
    vec2 shift = cs_shift(screen);
    csGuiFrame = vec4(screen, shift);
    int corner = vertexId % 4;
    if (corner == 0) {
        csGuiA = vec4(pos.xy, 0.0, 1.0);
    } else if (corner == 2) {
        csGuiC = vec4(pos.xy, 0.0, 1.0);
    }
    int kind = cs_gui_candidate(ivec4(round(color * 255.0)), pos, screen);
    if (kind == 0) {
        return;
    }
    bool right = corner == 0 || corner == 1;
    bool bottom = corner == 0 || corner == 3;
    vec2 g = pos.xy;
    if (kind == 1 && bottom && pos.y < screen.y - CS_CHAT_BOTTOM - 1.5) {
        g.y += CS_NOTCH;
    }
    if (kind == 3 && bottom && pos.y < screen.y - CS_LIST_BOTTOM - 0.5) {
        g.y += CS_NOTCH;
    }
    if (kind != 3) {
        if ((shift.x > 0.0 && right) || (shift.x < 0.0 && !right)) {
            g.x += shift.x;
        }
        if ((shift.y < 0.0 && !bottom) || (shift.y > 0.0 && bottom)) {
            g.y += shift.y;
        }
    }
    if (g != pos.xy) {
        csGuiTouched = 1.0;
        csGuiPos = prior + (g - pos.xy);
        gl_Position += m * vec4(g - pos.xy, 0.0, 0.0);
    }
}
