#version 330

#define NS_MARK ivec4(92, 14, 42, 180)

out vec4 nsDisp;
out vec3 nsPos;

void ns_display_vertex(vec4 color, vec3 pos, int vertexId) {
    int corner = vertexId % 4;
    float mark = ivec4(round(color * 255.0)) == NS_MARK ? 1.0 : 0.0;
    nsDisp = vec4(corner >= 2 ? 1.0 : 0.0, (corner == 1 || corner == 2) ? 1.0 : 0.0, mark, 0.0);
    nsPos = pos;
}
