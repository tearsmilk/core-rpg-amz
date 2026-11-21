#version 330

#moj_import <minecraft:dynamictransforms.glsl>

#define SHADERHOOKS_VERSION 1
#define SHADERHOOKS_HASH h0df9785db1dc3e1c

#moj_import <textshader:ts_hook_gui_fragment.glsl>
#moj_import <tabshader:th_hook_gui_fragment.glsl>
#moj_import <nameshader:ns_hook_gui_fragment.glsl>
#moj_import <chatshader:cs_hook_gui_fragment.glsl>

in vec4 vertexColor;

out vec4 fragColor;

void main() {
    vec4 color = vertexColor;
    color = ts_hook_gui_fragment(color, vertexColor);
    color = th_hook_gui_fragment(color, vertexColor);
    color = ns_hook_gui_fragment(color, vertexColor);
    color = cs_hook_gui_fragment(color, vertexColor);
    if (color.a == 0.0) {
        discard;
    }
    fragColor = color * ColorModulator;
}
