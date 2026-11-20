#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

#define TH_GROW_TAB 2.0
#define TH_GROW_BOARD 1.0

flat out int thGuiKind;
flat out vec2 thGuiScreen;
out vec2 thGuiPos;
out vec3 thGuiA;
out vec3 thGuiC;
out vec2 thGuiOk;

int th_gui_candidate(vec4 color) {
    ivec4 k = ivec4(round(color * 255.0));
    if (k.r == 0 && k.g == 0 && k.b == 0) {
        if (k.a == 128) {
            return 1;
        }
        if (k.a == 102) {
            return 4;
        }
        if (k.a == 76) {
            return 5;
        }
        return k.a > 0 ? 3 : 0;
    }
    if (k == ivec4(255, 255, 255, 32)) {
        return 2;
    }
    return 0;
}

void th_gui_vertex(vec3 pos, vec4 color, int vertexId) {
    thGuiKind = 0;
    thGuiScreen = vec2(0.0);
    thGuiPos = pos.xy;
    thGuiA = vec3(0.0);
    thGuiC = vec3(0.0);
    thGuiOk = vec2(0.0);
    if (ProjMat[3][3] != 1.0 || ProjMat[2][3] != 0.0 || ProjMat[1][1] >= 0.0) {
        return;
    }
    int corner = vertexId % 4;
    if (corner == 0) {
        thGuiA = vec3(pos.xy, 1.0);
    } else if (corner == 2) {
        thGuiC = vec3(pos.xy, 1.0);
    }
    int kind = th_gui_candidate(color);
    if (kind == 0) {
        return;
    }
    thGuiKind = kind;
    thGuiOk = vec2(float(kind), float(kind * kind));
    thGuiScreen = vec2(2.0 / ProjMat[0][0], -2.0 / ProjMat[1][1]);
    float grow = 0.0;
    if (kind == 1) {
        grow = TH_GROW_TAB;
    } else if (kind == 4 || kind == 5) {
        grow = TH_GROW_BOARD;
    }
    if (grow > 0.0) {
        vec2 side = vec2(corner >= 2 ? 1.0 : -1.0, (corner == 1 || corner == 2) ? 1.0 : -1.0);
        vec2 grown = pos.xy + side * grow;
        thGuiPos = grown;
        gl_Position = ProjMat * ModelViewMat * vec4(grown, pos.z, 1.0);
    }
}
