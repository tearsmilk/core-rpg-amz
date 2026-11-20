#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

#define SHADERHOOKS_VERSION 1
#define SHADERHOOKS_HASH h8fe86ffd4187fbe8

#moj_import <textshader:ts_hook_sprite_vertex.glsl>
#moj_import <tabshader:th_hook_sprite_vertex.glsl>
#moj_import <nameshader:ns_hook_sprite_vertex.glsl>
#moj_import <chatshader:cs_hook_sprite_vertex.glsl>

in vec3 Position;
in vec2 UV0;
in vec4 Color;

out vec2 texCoord0;
out vec4 vertexColor;

void main() {
    vec3 pos = Position;
    ts_hook_sprite_position(pos);
    th_hook_sprite_position(pos);
    ns_hook_sprite_position(pos);
    cs_hook_sprite_position(pos);
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    texCoord0 = UV0;
    vertexColor = Color;
    ts_hook_sprite_vertex(Position, UV0, Color, gl_VertexID, vertexColor);
    th_hook_sprite_vertex(Position, UV0, Color, gl_VertexID, vertexColor);
    ns_hook_sprite_vertex(Position, UV0, Color, gl_VertexID, vertexColor);
    cs_hook_sprite_vertex(Position, UV0, Color, gl_VertexID, vertexColor);
}
