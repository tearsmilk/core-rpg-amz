#version 330

#moj_import <chatshader:cs_gui_fragment.glsl>

vec4 cs_hook_gui_fragment(vec4 color, vec4 vertexColor) {
    return cs_gui_fragment(color, vertexColor);
}
