#version 330

#moj_import <tabshader:th_text_vertex.glsl>

void th_hook_text_position(inout vec3 pos, inout vec4 color) {
    pos = th_layout_shift(pos);
}

void th_hook_text_vertex(vec3 pos, vec4 color, vec2 uv, int vertexId, vec4 light, inout vec4 outColor) {
    th_text_vertex(pos, color, uv, vertexId, outColor);
}
