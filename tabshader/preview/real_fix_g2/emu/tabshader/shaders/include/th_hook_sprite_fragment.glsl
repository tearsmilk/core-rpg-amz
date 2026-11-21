#version 330

#moj_import <tabshader:th_sprite_fragment.glsl>

vec4 th_hook_sprite_fragment(vec4 color, vec4 tex, vec2 uv, vec4 vertexColor) {
    return th_sprite_fragment(color, tex, uv);
}
