#version 330

#moj_import <minecraft:dynamictransforms.glsl>

#define SHADERHOOKS_VERSION 1
#define SHADERHOOKS_HASH h0ccd4fb99906d5d6

uniform sampler2D Sampler0;

#moj_import <textshader:ts_hook_sprite_fragment.glsl>
#moj_import <tabshader:th_hook_sprite_fragment.glsl>
#moj_import <nameshader:ns_hook_sprite_fragment.glsl>
#moj_import <chatshader:cs_hook_sprite_fragment.glsl>

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    vec4 tex = texture(Sampler0, texCoord0);
    vec4 color = tex * vertexColor;
    color = ts_hook_sprite_fragment(color, tex, texCoord0, vertexColor);
    color = th_hook_sprite_fragment(color, tex, texCoord0, vertexColor);
    color = ns_hook_sprite_fragment(color, tex, texCoord0, vertexColor);
    color = cs_hook_sprite_fragment(color, tex, texCoord0, vertexColor);
    if (color.a == 0.0) {
        discard;
    }
    fragColor = color * ColorModulator;
}
