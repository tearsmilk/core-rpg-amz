#version 330

#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

#moj_import <tabshader:th_sprite_fragment.glsl>

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor;
    color = th_sprite_fragment(color, texture(Sampler0, texCoord0), texCoord0);
    if (color.a == 0.0) {
        discard;
    }
    fragColor = color * ColorModulator;
}
