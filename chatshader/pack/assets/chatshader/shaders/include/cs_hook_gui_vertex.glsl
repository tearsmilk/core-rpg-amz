#version 330

#moj_import <chatshader:cs_gui_vertex.glsl>

void cs_hook_gui_position(inout vec3 pos) {
}

void cs_hook_gui_vertex(vec3 pos, vec4 color, int vertexId, inout vec4 outColor) {
    cs_gui_vertex(pos, color, vertexId);
}
