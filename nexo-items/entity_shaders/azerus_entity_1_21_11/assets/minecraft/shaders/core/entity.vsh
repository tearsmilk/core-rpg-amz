#version 330
// armor3d lane gather (include/armor3d.glsl): KHR shuffle, else ARB ballot, else nothing (2D armor). Both are
// optional extensions checked by their macros, so a driver without them compiles the plain vanilla path.
#ifndef AZ3D_TEST_NO_SUBGROUP
#if defined(GL_KHR_shader_subgroup_shuffle) && defined(GL_KHR_shader_subgroup_basic) && !defined(AZ3D_TEST_ARB)
#extension GL_KHR_shader_subgroup_basic : enable
#extension GL_KHR_shader_subgroup_shuffle : enable
#define AZ3D_SUBGROUP 1
#elif defined(GL_ARB_shader_ballot) && defined(GL_ARB_gpu_shader_int64)
#extension GL_ARB_gpu_shader_int64 : enable
#extension GL_ARB_shader_ballot : enable
#define AZ3D_SUBGROUP 2
#endif
#endif

#moj_import <minecraft:light.glsl>
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// ONE copy for the whole server: nexo-items/entity_shaders (external pack, overlay for pack format 75 = 1.21.11
// only; newer clients joining through ViaVersion keep their own vanilla entity shaders).
// Vanilla 1.21.11 entity vertex shader + two hooks:
// 1. rezusmenu: vertices with the menu's brightness override (block 15, sky 14 -> UV2 = (240, 224)) skip directional
//    light and the lightmap, so block items shown as menu icons (entity_cutout) keep one brightness wherever the
//    camera looks.
// 2. AZERUS armor3d (nexo-items): only in the armor pipelines (ALPHA_CUTOUT + NO_OVERLAY + PER_FACE_LIGHTING) and only
//    for our marked equipment textures — see include/armor3d.glsl. Needs GL_KHR_shader_subgroup_shuffle or
//    GL_ARB_shader_ballot usable at #version 330 in the vertex stage; without them the hook compiles to nothing and
//    the armor stays 2D. Do NOT raise #version (macOS GL 4.1 would fail to compile and lose every entity).
// Everything else is vanilla.

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler1;
uniform sampler2D Sampler2;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
#ifdef PER_FACE_LIGHTING
out vec4 vertexPerFaceColorBack;
out vec4 vertexPerFaceColorFront;
#else
out vec4 vertexColor;
#endif
out vec4 lightMapColor;
out vec4 overlayColor;
out vec2 texCoord0;

#if defined(ALPHA_CUTOUT) && defined(NO_OVERLAY) && defined(PER_FACE_LIGHTING)
#define AZ3D_PIPELINE
uniform sampler2D Sampler0;
out float az3dState;
flat out int az3dFlags;
#moj_import <minecraft:armor3d.glsl>
#endif

const ivec2 UI_LIGHT = ivec2(240, 224);

void main() {
    vec3 pos = Position;
    vec3 nrm = Normal;
    vec2 uv = UV0;
    vec4 col = Color;
#ifdef AZ3D_PIPELINE
    az3d_apply(pos, nrm, uv, col, az3dState, az3dFlags);
    gl_Position = az3dState > 1.5 ? vec4(-3.0, -3.0, -3.0, 1.0) : ProjMat * ModelViewMat * vec4(pos, 1.0);
#else
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);
#endif

    sphericalVertexDistance = fog_spherical_distance(pos);
    cylindricalVertexDistance = fog_cylindrical_distance(pos);

    bool unlit = UV2 == UI_LIGHT;
#ifdef PER_FACE_LIGHTING
    vec2 light = minecraft_compute_light(Light0_Direction, Light1_Direction, nrm);
    vertexPerFaceColorBack = unlit ? col : minecraft_mix_light_separate(-light, col);
    vertexPerFaceColorFront = unlit ? col : minecraft_mix_light_separate(light, col);
#elif defined(NO_CARDINAL_LIGHTING)
    vertexColor = col;
#else
    vertexColor = unlit ? col : minecraft_mix_light(Light0_Direction, Light1_Direction, nrm, col);
#endif
#ifndef EMISSIVE
    lightMapColor = unlit ? vec4(1.0) : texelFetch(Sampler2, UV2 / 16, 0);
#else
    lightMapColor = vec4(1.0);
#endif
    overlayColor = texelFetch(Sampler1, UV1, 0);

    texCoord0 = uv;
#ifdef APPLY_TEXTURE_MATRIX
    texCoord0 = (TextureMat * vec4(uv, 0.0, 1.0)).xy;
#endif
}
