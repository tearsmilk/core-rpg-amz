#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <nameshader:ns_tag.glsl>

uniform sampler2D Sampler0;

in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor;
    color = ns_tag(color, vertexColor, texCoord0);
    if (color.a < 0.1) {
        discard;
    }
    fragColor = color * ColorModulator;
}
