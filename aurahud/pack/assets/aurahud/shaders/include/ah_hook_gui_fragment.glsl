#version 330

#moj_import <aurahud:ah_common.glsl>

vec4 ah_hook_gui_fragment(vec4 color, vec4 vertexColor) {
#if AH_ACTIVE
    if (vertexColor == vec4(0.0)) {
        return vec4(0.0);
    }
#endif
    return color;
}
