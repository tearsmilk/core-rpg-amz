#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
// >>> coreportals hook (1/2): import >>>
#moj_import <coreportals:portal_fragment.glsl>
// <<< coreportals hook <<<

// >>> coreskyrift hook (1/2): import >>>
#moj_import <coreskyrift:skyrift_fragment.glsl>
// <<< coreskyrift hook <<<

uniform sampler2D Sampler0;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;
in vec2 texCoord1;

out vec4 fragColor;

void main() {
    // >>> coreskyrift hook (2/2): sky fog dome / rift / screen flash (no vanilla fog: this is the sky) >>>
    if (srKind > 0) {
        vec4 srColor = sr_fragment();
        if (srColor.a < 0.004) discard;
        fragColor = srColor;
        return;
    }
    // <<< coreskyrift hook <<<
    // >>> coreportals hook (2/2): animated portal surface / edges / flames / ground cracks >>>
    if (cpKind > 0) {
        vec4 cpColor = cp_fragment(cpKind, cpLocal);
        if (cpColor.a < 0.004) discard;
        fragColor = apply_fog(cpColor, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
        return;
    }
    // <<< coreportals hook <<<
    vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    if (color.a < 0.1) {
        discard;
    }
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
