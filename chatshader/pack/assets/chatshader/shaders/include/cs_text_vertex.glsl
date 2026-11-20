#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <chatshader:cs_layout.glsl>

#define CS_MARK ivec3(11, 12, 13)
#define CS_FX_TEXT_R 213
#define CS_FX_SHADOW_R 52
#define CS_TS_TEXT_R 214
#define CS_TS_SHADOW_R 53

bool csTextMove = false;

bool cs_text_marked(ivec3 c) {
    return (c & ivec3(15)) == CS_MARK;
}

bool cs_text_fx_payload(ivec3 c) {
    return c.g < 208 || (c.g >= 224 && c.g < 240);
}

bool cs_text_fx_shadow_payload(ivec3 c) {
    return c.g < 64 && (c.g < 52 || c.g > 55);
}

bool cs_text_column(vec3 pos, vec2 screen) {
    float bottom = screen.y - CS_CHAT_BOTTOM;
    return pos.x >= -0.01 && pos.x <= CS_CHAT_RIGHT_LIMIT && pos.y >= bottom - CS_CHAT_REACH && pos.y <= bottom + 10.0;
}

void cs_text_position(vec3 pos, inout vec4 color) {
    csTextMove = false;
    if (!cs_is_gui()) {
        return;
    }
    vec2 screen = cs_screen();
    if (cs_shift(screen) == vec2(0.0) || !cs_text_column(pos, screen)) {
        return;
    }
    ivec3 c = ivec3(round(color.rgb * 255.0));
    if (cs_text_marked(c)) {
        csTextMove = true;
    } else if (c.r == CS_FX_TEXT_R && cs_text_fx_payload(c)) {
        color.r = float(CS_TS_TEXT_R) / 255.0;
        csTextMove = true;
    } else if (c.r == CS_FX_SHADOW_R && cs_text_fx_shadow_payload(c)) {
        color.r = float(CS_TS_SHADOW_R) / 255.0;
        csTextMove = true;
    }
}

void cs_text_vertex() {
    if (csTextMove) {
        vec2 shift = cs_shift(cs_screen());
        gl_Position.xy += (ProjMat * ModelViewMat * vec4(shift, 0.0, 0.0)).xy * gl_Position.w;
    }
}
