#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

#define SHADERHOOKS_VERSION 1

uniform sampler2D Sampler0;
uniform sampler2D Sampler2;

#moj_import <textshader:ts_hook_text_vertex.glsl>
#moj_import <tabshader:th_hook_text_vertex.glsl>
#moj_import <nameshader:ns_hook_text_vertex.glsl>
#moj_import <chatshader:cs_hook_text_vertex.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;

void main() {
    vec3 pos = Position;
    ts_hook_text_position(pos);
    th_hook_text_position(pos);
    ns_hook_text_position(pos);
    cs_hook_text_position(pos);
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    vec4 light = texelFetch(Sampler2, UV2 / 16, 0);
    vertexColor = Color * light;
    texCoord0 = UV0;
    ts_hook_text_vertex(Position, Color, UV0, gl_VertexID, light, vertexColor);
    th_hook_text_vertex(Position, Color, UV0, gl_VertexID, light, vertexColor);
    ns_hook_text_vertex(Position, Color, UV0, gl_VertexID, light, vertexColor);
    cs_hook_text_vertex(Position, Color, UV0, gl_VertexID, light, vertexColor);
}
