#version 330

#moj_import <chatshader:cs_text_vertex.glsl>

void cs_hook_text_position(inout vec3 pos, inout vec4 color) {
    cs_text_position(pos, color);
}

void cs_hook_text_vertex(vec3 pos, vec4 color, vec2 uv, int vertexId, vec4 light, inout vec4 outColor) {
    cs_text_vertex();
}
