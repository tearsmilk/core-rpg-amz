#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <tabshader:th_layout.glsl>

out vec3 thSprA;
out vec3 thSprC;

void th_sprite_vertex(vec3 pos, vec2 uv, int vertexId) {
    int corner = vertexId % 4;
    thSprA = corner == 0 ? vec3(uv, 1.0) : vec3(0.0);
    thSprC = corner == 2 ? vec3(uv, 1.0) : vec3(0.0);
    vec3 moved = th_layout_shift(pos);
    if (moved != pos) {
        gl_Position = ProjMat * ModelViewMat * vec4(moved, 1.0);
    }
}
