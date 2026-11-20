#version 330

#moj_import <tabshader:th_gui_vertex.glsl>

void th_hook_gui_position(inout vec3 pos) {
}

void th_hook_gui_vertex(vec3 pos, vec4 color, int vertexId, inout vec4 outColor) {
    th_gui_vertex(pos, color, vertexId);
}
