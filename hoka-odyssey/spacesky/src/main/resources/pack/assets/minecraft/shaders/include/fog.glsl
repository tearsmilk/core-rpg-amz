#version 330

// spacesky: ванильный fog.glsl 1.21.11, изменена только apply_fog (см. ниже).
// Миры профилей неба (spacesky:profiles.glsl) узнаются по МЕТКЕ в цвете тумана: метка — служебный цвет, поэтому
// туман там рисуется цветом профиля (у планеты — по времени суток, со своей дымкой; под водой — вода профиля).
// Остальные миры: режим everywhere: true — туман дальности уходит в цвет космоса (профиль space), туман окружения
// (вода, лава, слепота, снег) — как в ванили; режим false — ванильный туман без изменений.
#moj_import <spacesky:mode.glsl>
#moj_import <spacesky:profiles.glsl>

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
    int code = spacesky_code(fogColor.rgb);
    int prof = spacesky_code_profile(code);
    if (prof >= 0) {
        // мир профиля: цвет и дымка — из профиля (метка — не цвет, а номер)
        float es = environmentalStart;
        float ee = environmantalEnd;
        vec3 c = spacesky_profile_fog(prof, code >= 16, es, ee);
        float v = total_fog_value(sphericalVertexDistance, cylindricalVertexDistance, es, ee, renderDistanceStart, renderDistanceEnd);
        return vec4(mix(inColor.rgb, c, v * fogColor.a), inColor.a);
    }
#if SPACESKY_EVERYWHERE
    // spacesky: туман окружения (вода, лава, слепота, снег) — как в ванили, цветом FogColor; туман дальности
    // прорисовки — в цвет космоса, чтобы край мира уходил в темноту, а не в голубую дымку.
    float envFog = linear_fog_value(sphericalVertexDistance, environmentalStart, environmantalEnd);
    float distFog = linear_fog_value(cylindricalVertexDistance, renderDistanceStart, renderDistanceEnd);
    vec3 rgb = mix(inColor.rgb, fogColor.rgb, envFog * fogColor.a);
    if (envFog < distFog) {
        float es0 = 0.0;
        float ee0 = 0.0;
        rgb = mix(rgb, spacesky_profile_fog(0, false, es0, ee0), (distFog - envFog) / max(1.0 - envFog, 1e-4) * fogColor.a);
    }
    return vec4(rgb, inColor.a);
#else
    float fogValue = total_fog_value(sphericalVertexDistance, cylindricalVertexDistance, environmentalStart, environmantalEnd, renderDistanceStart, renderDistanceEnd);
    return vec4(mix(inColor.rgb, fogColor.rgb, fogValue * fogColor.a), inColor.a);
#endif
}

float fog_spherical_distance(vec3 pos) {
    return length(pos);
}

float fog_cylindrical_distance(vec3 pos) {
    float distXZ = length(pos.xz);
    float distY = abs(pos.y);
    return max(distXZ, distY);
}
