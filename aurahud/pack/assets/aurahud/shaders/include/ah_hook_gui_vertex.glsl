#version 330

#moj_import <aurahud:ah_gui_vertex.glsl>

void ah_hook_gui_position(inout vec3 pos) {
}

void ah_hook_gui_vertex(vec3 pos, vec4 color, int vertexId, inout vec4 outColor) {
    ah_gui_vertex(pos, color, vertexId, outColor);
}
