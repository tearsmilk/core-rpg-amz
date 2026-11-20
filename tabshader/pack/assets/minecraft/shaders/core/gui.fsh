#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <tabshader:th_gui_fragment.glsl>

in vec4 vertexColor;

out vec4 fragColor;

void main() {
    vec4 color = vertexColor;
    color = th_gui_fragment(color);
    if (color.a == 0.0) {
        discard;
    }
    fragColor = color * ColorModulator;
}
