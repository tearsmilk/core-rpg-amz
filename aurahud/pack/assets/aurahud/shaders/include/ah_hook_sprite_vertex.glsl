#version 330

#moj_import <aurahud:ah_sprite_vertex.glsl>

void ah_hook_sprite_position(inout vec3 pos) {
}

void ah_hook_sprite_vertex(vec3 pos, vec2 uv, vec4 color, int vertexId, inout vec4 outColor) {
    ah_sprite_vertex(pos, uv, color, vertexId);
}
