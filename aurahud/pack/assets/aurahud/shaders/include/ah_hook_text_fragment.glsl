#version 330

#moj_import <aurahud:ah_common.glsl>

vec4 ah_hook_text_fragment(vec4 color, vec4 vertexColor, vec2 uv) {
#if AH_ACTIVE
    if (vertexColor == vec4(0.0)) {
        return vec4(0.0);
    }
#endif
    return color;
}
