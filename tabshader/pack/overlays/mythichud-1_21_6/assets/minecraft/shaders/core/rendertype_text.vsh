#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <textshader:ts_vertex.glsl>
#moj_import <tabshader:th_text_vertex.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;

uniform sampler2D Sampler2;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;

#define MH_VERSION 5
#moj_import <minecraft:mythichud_utils.glsl>

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(th_layout_shift(Position), 1.0);

    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    vertexColor = Color * texelFetch(Sampler2, UV2 / 16, 0);
    texCoord0 = UV0;
    ts_vertex(Color, UV0, gl_VertexID, texelFetch(Sampler2, UV2 / 16, 0), vertexColor);
    th_text_vertex(Position, Color, UV0, gl_VertexID, vertexColor);
    vec4 thPosition = gl_Position;
    float thSpherical = sphericalVertexDistance;
    float thCylindrical = cylindricalVertexDistance;
    applyCustomHud();
    if (distance(gl_Position, ProjMat * ModelViewMat * vec4(Position, 1.0)) < 0.001 * max(1.0, abs(thPosition.w))) {
        gl_Position = thPosition;
        sphericalVertexDistance = thSpherical;
        cylindricalVertexDistance = thCylindrical;
    }
}
