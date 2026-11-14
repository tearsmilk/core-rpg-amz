#version 330

#moj_import <minecraft:light.glsl>
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
// >>> coreportals hook (1/2): import >>>
#moj_import <coreportals:portal_vertex.glsl>
// <<< coreportals hook <<<

// rezusmenu: vanilla 1.21.11 shader plus two cases for the in-world menu.
// Shared file: identical copy in core-portals/pack and rezusmenu/pack (both systems need it in the same pack).
//  1. Shader cursor: an item tinted with the marker colour (254, 0, 254) through the item model tint
//     "minecraft:custom_model_data" is point-reflected through the screen centre, so a world-fixed quad moves
//     with the mouse at frame rate. The server mirrors the same mapping for hover and clicks (MIRROR_K = 1).
//  2. Menu icons: displays with the brightness override (block 15, sky 14) -> UV2 = (240, 224) are drawn
//     unlit (no directional light, no lightmap), so their brightness does not depend on where the camera looks.
// Every other vertex is drawn exactly as in vanilla.

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in vec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler2;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;
out vec2 texCoord1;
out vec2 texCoord2;

const vec3 MARKER_RGB = vec3(254.0, 0.0, 254.0) / 255.0;
const float MARKER_TOL = 1.5 / 255.0;
const float MIRROR_K = 1.0;
const ivec2 UI_LIGHT = ivec2(240, 224);

bool isMarker(vec3 c) {
    vec3 d = abs(c - MARKER_RGB);
    return max(d.r, max(d.g, d.b)) <= MARKER_TOL;
}

void main() {
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    texCoord0 = UV0;
    texCoord1 = UV1;
    texCoord2 = UV2;

    if (isMarker(Color.rgb)) {
        // View space: camera at the origin looking down -Z. Reflect x and y, keep the depth.
        vec4 viewPos = ModelViewMat * vec4(Position, 1.0);
        viewPos.xy = -MIRROR_K * viewPos.xy;
        gl_Position = ProjMat * viewPos;
        vertexColor = vec4(1.0, 1.0, 1.0, Color.a);
    } else if (UV2 == UI_LIGHT) {
        gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
        vertexColor = Color;
    } else {
        gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
        vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, Color) * texelFetch(Sampler2, UV2 / 16, 0);
    }

    // >>> coreportals hook (2/2): portal vertices are unlit, the fragment shader paints them >>>
    if (cp_vertex(Color, gl_VertexID)) vertexColor = vec4(1.0);
    // <<< coreportals hook <<<
}
