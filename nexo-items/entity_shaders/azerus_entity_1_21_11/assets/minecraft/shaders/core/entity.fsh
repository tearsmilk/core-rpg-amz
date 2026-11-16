#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>

// AZERUS (nexo-items): vanilla 1.21.11 entity fragment shader + two hooks, both only in the armor pipelines:
//  * armor3d (3D armor from equipment textures, see include/armor3d.glsl and core/entity.vsh): fragments of baked
//    faces (az3dState == 1) — texture alpha 0 is a hole, anything else opaque; back faces discarded unless the face is
//    double-sided; emissive faces skip shading and lightmap. Triangles mixing states are discarded.
//  * custom_armor glow (below): glowing pixels on armor.
// Hook: only in the armor pipelines — armor_cutout_no_cull, armor_decal_cutout_no_cull (trims), armor_translucent are
// the only 1.21.11 pipelines with ALL THREE of ALPHA_CUTOUT, NO_OVERLAY and PER_FACE_LIGHTING (entity_no_outline,
// used by banners and shield patterns, has no ALPHA_CUTOUT; its pattern textures do contain alpha 251 — excluded),
// and only for texture pixels whose alpha is EXACTLY 251/255 — painted on purpose by
// nexo-items/tools/armor_textures.py. Such a pixel is drawn emissive (no lightmap, no face shading) with a slow wave of
// brightness running down the texture. Vanilla armor, leather dye, trims and other packs' armor use alpha 0 or 255
// (checked by nexo-items/tools/armor_shader_check.py), so for them this file behaves exactly like vanilla.
// Everything else is vanilla. The vertex shader is core/entity.vsh next to this file (rezusmenu hook + armor3d): it
// adds the outputs az3dState/az3dFlags in the armor pipelines. Both live only in the format-75 overlay of
// nexo-items/entity_shaders (1.21.11 clients).

uniform sampler2D Sampler0;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
#ifdef PER_FACE_LIGHTING
in vec4 vertexPerFaceColorBack;
in vec4 vertexPerFaceColorFront;
#else
in vec4 vertexColor;
#endif
in vec4 lightMapColor;
in vec4 overlayColor;
in vec2 texCoord0;

out vec4 fragColor;

#if defined(ALPHA_CUTOUT) && defined(NO_OVERLAY) && defined(PER_FACE_LIGHTING)
#define AZERUS_ARMOR_GLOW
in float az3dState;
flat in int az3dFlags;
// Exact marker: alpha 251 (0.98431). Integer compare after rounding — no tolerance band.
bool azerus_glow_pixel(vec4 c) {
    return int(c.a * 255.0 + 0.5) == 251;
}
// Brightness factor 0.80 .. 1.35: a wave down the texture rows, period ~2 s (GameTime: 1.0 = 24000 ticks).
float azerus_glow_factor() {
    float ticks = GameTime * 24000.0;
    float row = floor(texCoord0.y * 32.0);
    float wave = 0.5 + 0.5 * sin(ticks * 0.16 - row * 0.55);
    return 0.80 + 0.55 * wave;
}
#endif

void main() {
#ifdef AZERUS_ARMOR_GLOW
    if (az3dState > 0.0005) {
        if (az3dState < 0.9995 || az3dState > 1.0005) {
            discard;   // hidden quad, or a triangle mixing 3D and non-3D vertices
        }
        if (!gl_FrontFacing && (az3dFlags & 8) == 0) {
            discard;
        }
        vec4 c3 = texture(Sampler0, texCoord0);
        if (c3.a < 0.5 / 255.0) {
            discard;
        }
        c3.a = 1.0;
        if ((az3dFlags & 2) != 0) {
            c3 *= ColorModulator;
        } else {
            c3 *= (gl_FrontFacing ? vertexPerFaceColorFront : vertexPerFaceColorBack) * ColorModulator;
            c3 *= lightMapColor;
        }
        fragColor = apply_fog(c3, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
        return;
    }
#endif
    vec4 color = texture(Sampler0, texCoord0);
#ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
#endif
#ifdef AZERUS_ARMOR_GLOW
    if (azerus_glow_pixel(color)) {
        vec4 glow = vec4(min(color.rgb * azerus_glow_factor(), vec3(1.0)), 1.0) * ColorModulator;
        fragColor = apply_fog(glow, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
        return;
    }
#endif
#ifdef PER_FACE_LIGHTING
    color *= (gl_FrontFacing ? vertexPerFaceColorFront : vertexPerFaceColorBack) * ColorModulator;
#else
    color *= vertexColor * ColorModulator;
#endif
#ifndef NO_OVERLAY
    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
#endif
#ifndef EMISSIVE
    color *= lightMapColor;
#endif
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
