#version 330

#moj_import <aurahud:ah_text_vertex.glsl>

void ah_hook_text_position(inout vec3 pos, inout vec4 color) {
}

void ah_hook_text_vertex(vec3 pos, vec4 color, vec2 uv, int vertexId, vec4 light, inout vec4 outColor) {
    ah_text_vertex(pos, color, vertexId, outColor);
}
