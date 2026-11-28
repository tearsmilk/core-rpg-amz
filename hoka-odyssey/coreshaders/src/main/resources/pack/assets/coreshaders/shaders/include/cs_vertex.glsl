out vec3 csPos;
out vec2 csLocal;
out vec4 csTint;

vec2 cs_corner(int id) {
    int i = id % 4;
    if (i == 0) return vec2(0.0, 0.0);
    if (i == 1) return vec2(0.0, 1.0);
    if (i == 2) return vec2(1.0, 1.0);
    return vec2(1.0, 0.0);
}

void cs_vertex(vec3 pos, vec4 color, int vertexId) {
    csPos = pos;
    csLocal = cs_corner(vertexId);
    csTint = color;
}
