#version 330

#define WORLDSHADER_VERSION 1
#define WORLDSHADER_HASH ha6470c06787d5451

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <worldshader:ws_data.glsl>

uniform sampler2D Sampler0;

in vec4 vertexColor;
in vec2 texCoord0;
flat in ivec4 wsInfo;
flat in vec3 wsCenter;
flat in vec4 wsBox;

out vec4 fragColor;

vec3 ws_marker_texel(int x) {
    int slot = wsInfo.y;
    if (x == WS_TAG_A || x == WS_TAG_B) {
        return vec3(ws_tag(slot));
    }
    if (x == WS_INFO) {
        return vec3(float(wsInfo.z), float(wsInfo.w), float(slot));
    }
    if (x >= WS_CENTER && x < WS_CENTER + 3) {
        return ws_bytes(wsCenter[x - WS_CENTER]);
    }
    if (x >= WS_BOX && x < WS_BOX + 4) {
        return ws_bytes(wsBox[x - WS_BOX]);
    }
    if (x >= WS_PROJ && x < WS_PROJ + 16) {
        int i = x - WS_PROJ;
        return ws_bytes(ProjMat[i / 4][i % 4]);
    }
    return vec3(0.0);
}

void main() {
    if (wsInfo.x >= 1) {
        int x = int(gl_FragCoord.x) - ws_origin(wsInfo.x - 1, wsInfo.y, ivec2(ScreenSize)).x;
        fragColor = vec4(ws_marker_texel(x) / 255.0, 0.0);
        return;
    }
    vec4 color = texture(Sampler0, texCoord0);
    if (color.a == 0.0) {
        discard;
    }
    fragColor = vec4(ColorModulator.rgb * vertexColor.rgb, ColorModulator.a);
}
