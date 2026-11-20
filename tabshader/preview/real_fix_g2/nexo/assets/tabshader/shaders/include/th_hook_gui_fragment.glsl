#version 330

#moj_import <tabshader:th_gui_fragment.glsl>

vec4 th_hook_gui_fragment(vec4 color, vec4 vertexColor) {
    return th_gui_fragment(color);
}
