#version 330

#define WORLDSHADER_VERSION 1
#define WORLDSHADER_HASH hb20693d03c9be6ca

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <worldshader:ws_data.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;

uniform sampler2D Sampler0;

out vec4 vertexColor;
out vec2 texCoord0;
flat out ivec4 wsInfo;
flat out vec3 wsCenter;
flat out vec4 wsBox;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    vertexColor = Color;
    texCoord0 = UV0;
    wsInfo = ivec4(0);
    wsCenter = vec3(0.0);
    wsBox = vec4(0.0);

    ivec3 c = ivec3(round(Color.rgb * 255.0));
    ivec4 sig = ivec4(round(textureLod(Sampler0, UV0, 0.0) * 255.0));
    int copy = sig.a - WS_SIGNATURE_ALPHA;
    if (c.r == WS_MARKER_RED && c.g >= 1 && c.g <= WS_MAX_RADIUS && c.b % 32 > 0 && sig.rgb == WS_SIGNATURE_RGB && copy >= 0 && copy < WS_COPIES) {
        int slot = c.b / 32;
        int corner = gl_VertexID % 4;
        vec2 origin = vec2(ws_origin(copy, slot, ivec2(ScreenSize)));
        float x = origin.x + (corner >= 2 ? float(WS_ROW_PIXELS) : 0.0);
        float y = origin.y + ((corner == 0 || corner == 3) ? 1.0 : 0.0);
        gl_Position = vec4(vec2(x, y) / ScreenSize * 2.0 - 1.0, 0.0, 1.0);
        wsInfo = ivec4(1 + copy, slot, c.g, c.b - slot * 32);
        wsCenter = (ModelViewMat * vec4(Position, 1.0)).xyz;
        float r = float(c.g);
        vec2 lo = vec2(1.0);
        vec2 hi = vec2(0.0);
        int behind = 0;
        for (int i = 0; i < 8; i++) {
            vec3 k = wsCenter + r * vec3((i & 1) == 0 ? -1.0 : 1.0, (i & 2) == 0 ? -1.0 : 1.0, (i & 4) == 0 ? -1.0 : 1.0);
            vec4 q = ProjMat * vec4(k, 1.0);
            if (q.w <= 0.05) {
                behind++;
            } else {
                vec2 uv = q.xy / q.w * 0.5 + 0.5;
                lo = min(lo, uv);
                hi = max(hi, uv);
            }
        }
        wsBox = behind == 8 ? vec4(1.0, 1.0, 0.0, 0.0) : behind > 0 ? vec4(0.0, 0.0, 1.0, 1.0) : vec4(clamp(lo, 0.0, 1.0), clamp(hi, 0.0, 1.0));
    }
}
