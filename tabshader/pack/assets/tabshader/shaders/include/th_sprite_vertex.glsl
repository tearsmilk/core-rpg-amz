#version 330

out vec3 thSprA;
out vec3 thSprC;

void th_sprite_vertex(vec2 uv, int vertexId) {
    int corner = vertexId % 4;
    thSprA = corner == 0 ? vec3(uv, 1.0) : vec3(0.0);
    thSprC = corner == 2 ? vec3(uv, 1.0) : vec3(0.0);
}
