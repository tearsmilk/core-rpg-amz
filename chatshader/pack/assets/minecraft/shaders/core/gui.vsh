#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

#define SHADERHOOKS_VERSION 2
#define SHADERHOOKS_HASH h112703f677f079fe

#moj_import <textshader:ts_hook_gui_vertex.glsl>
#moj_import <tabshader:th_hook_gui_vertex.glsl>
#moj_import <nameshader:ns_hook_gui_vertex.glsl>
#moj_import <chatshader:cs_hook_gui_vertex.glsl>
#moj_import <aurahud:ah_hook_gui_vertex.glsl>

in vec3 Position;
in vec4 Color;

out vec4 vertexColor;

void main() {
    vec3 pos = Position;
    ts_hook_gui_position(pos);
    th_hook_gui_position(pos);
    ns_hook_gui_position(pos);
    cs_hook_gui_position(pos);
    ah_hook_gui_position(pos);
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    vertexColor = Color;
    ts_hook_gui_vertex(Position, Color, gl_VertexID, vertexColor);
    th_hook_gui_vertex(Position, Color, gl_VertexID, vertexColor);
    ns_hook_gui_vertex(Position, Color, gl_VertexID, vertexColor);
    cs_hook_gui_vertex(Position, Color, gl_VertexID, vertexColor);
    ah_hook_gui_vertex(Position, Color, gl_VertexID, vertexColor);
}
