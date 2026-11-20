#version 330

#moj_import <textshader:ts_vertex.glsl>

void ts_hook_text_position(inout vec3 pos) {
}

void ts_hook_text_vertex(vec3 pos, vec4 color, vec2 uv, int vertexId, vec4 light, inout vec4 outColor) {
    ts_vertex(color, uv, vertexId, light, outColor);
}
