#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>

#define SHADERHOOKS_VERSION 1

uniform sampler2D Sampler0;

#moj_import <textshader:ts_hook_text_fragment.glsl>
#moj_import <tabshader:th_hook_text_fragment.glsl>
#moj_import <nameshader:ns_hook_text_fragment.glsl>
#moj_import <chatshader:cs_hook_text_fragment.glsl>

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    color = ts_hook_text_fragment(color, vertexColor, texCoord0);
    color = th_hook_text_fragment(color, vertexColor, texCoord0);
    color = ns_hook_text_fragment(color, vertexColor, texCoord0);
    color = cs_hook_text_fragment(color, vertexColor, texCoord0);
    if (color.a < 0.1) {
        discard;
    }
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
