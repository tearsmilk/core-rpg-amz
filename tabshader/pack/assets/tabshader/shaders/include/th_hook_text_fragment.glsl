#version 330

#moj_import <tabshader:th_text_fragment.glsl>

vec4 th_hook_text_fragment(vec4 color, vec4 vertexColor, vec2 uv) {
    return th_text_fragment(color, vertexColor);
}
