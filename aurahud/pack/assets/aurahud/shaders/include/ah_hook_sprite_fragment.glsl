#version 330

#moj_import <aurahud:ah_sprite_fragment.glsl>

vec4 ah_hook_sprite_fragment(vec4 color, vec4 tex, vec2 uv, vec4 vertexColor) {
    return ah_sprite_fragment(color, vertexColor);
}
