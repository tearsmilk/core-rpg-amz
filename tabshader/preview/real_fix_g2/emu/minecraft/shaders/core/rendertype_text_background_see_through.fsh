#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <nameshader:ns_display_fragment.glsl>

in vec4 vertexColor;

out vec4 fragColor;

void main() {
    vec4 color = vertexColor;
    color = ns_display_fragment(color, vertexColor);
    if (color.a < 0.1) {
        discard;
    }
    fragColor = color * ColorModulator;
}
