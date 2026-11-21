#version 330

// spacesky (только режим everywhere: true): ванильный fog.glsl 1.21.11, изменена только apply_fog (см. ниже).
// Цвет космоса у горизонта SPACESKY_BASE — общий для неба (sky.fsh) и тумана дальности — в spacesky:mode.glsl.
#moj_import <spacesky:mode.glsl>

layout(std140) uniform Fog {
    vec4 FogColor;
    float FogEnvironmentalStart;
    float FogEnvironmentalEnd;
    float FogRenderDistanceStart;
    float FogRenderDistanceEnd;
    float FogSkyEnd;
    float FogCloudsEnd;
};

float linear_fog_value(float vertexDistance, float fogStart, float fogEnd) {
    if (vertexDistance <= fogStart) {
        return 0.0;
    } else if (vertexDistance >= fogEnd) {
        return 1.0;
    }

    return (vertexDistance - fogStart) / (fogEnd - fogStart);
}

float total_fog_value(float sphericalVertexDistance, float cylindricalVertexDistance, float environmentalStart, float environmantalEnd, float renderDistanceStart, float renderDistanceEnd) {
    return max(linear_fog_value(sphericalVertexDistance, environmentalStart, environmantalEnd), linear_fog_value(cylindricalVertexDistance, renderDistanceStart, renderDistanceEnd));
}

vec4 apply_fog(vec4 inColor, float sphericalVertexDistance, float cylindricalVertexDistance, float environmentalStart, float environmantalEnd, float renderDistanceStart, float renderDistanceEnd, vec4 fogColor) {
    // spacesky: туман окружения (вода, лава, слепота, снег) — как в ванили, цветом FogColor; туман дальности
    // прорисовки — в цвет космоса, чтобы край мира уходил в темноту, а не в голубую дымку.
    float envFog = linear_fog_value(sphericalVertexDistance, environmentalStart, environmantalEnd);
    float distFog = linear_fog_value(cylindricalVertexDistance, renderDistanceStart, renderDistanceEnd);
    vec3 rgb = mix(inColor.rgb, fogColor.rgb, envFog * fogColor.a);
    if (envFog < distFog) {
        rgb = mix(rgb, SPACESKY_BASE, (distFog - envFog) / max(1.0 - envFog, 1e-4) * fogColor.a);
    }
    return vec4(rgb, inColor.a);
}

float fog_spherical_distance(vec3 pos) {
    return length(pos);
}

float fog_cylindrical_distance(vec3 pos) {
    float distXZ = length(pos.xz);
    float distY = abs(pos.y);
    return max(distXZ, distY);
}
