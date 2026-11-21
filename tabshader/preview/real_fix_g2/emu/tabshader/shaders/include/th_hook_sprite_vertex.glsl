#version 330

#moj_import <tabshader:th_sprite_vertex.glsl>

void th_hook_sprite_position(inout vec3 pos) {
}

void th_hook_sprite_vertex(vec3 pos, vec2 uv, vec4 color, int vertexId, inout vec4 outColor) {
    th_sprite_vertex(pos, uv, vertexId);
}
