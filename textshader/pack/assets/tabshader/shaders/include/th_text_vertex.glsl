#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

#define TH_MARK_R 231
#define TH_MARK_TEXEL ivec4(231, 7, 77, 255)

uniform sampler2D Sampler0;

flat out int thTextKind;
flat out vec4 thTextBox;
out vec2 thTextLocal;

vec4 th_text_layout(int kind) {
    if (kind == 2) {
        return vec4(4.0, 3.0, 2.0, 1.25);
    }
    if (kind == 3) {
        return vec4(2.0, 3.0, 2.0, 2.0);
    }
    return vec4(1.0, 4.0, 2.0, 3.0);
}

void th_text_vertex(vec3 pos, vec4 color, vec2 uv, int vertexId, inout vec4 outColor) {
    thTextKind = 0;
    thTextBox = vec4(0.0);
    thTextLocal = vec2(0.0);
    ivec3 c = ivec3(round(color.rgb * 255.0));
    int kind = c.g >> 4;
    bool place = c.r >= TH_MARK_R + 1 && c.r <= TH_MARK_R + 3 && kind <= 1;
    if (!place && (c.r != TH_MARK_R || kind < 1 || kind > 4)) {
        return;
    }
    int corner = vertexId % 4;
    vec2 atlas = vec2(textureSize(Sampler0, 0));
    vec2 inward = vec2(corner >= 2 ? -0.5 : 0.5, (corner == 1 || corner == 2) ? -0.5 : 0.5) / atlas;
    ivec4 texel = ivec4(round(textureLod(Sampler0, uv + inward, 0.0) * 255.0));
    if (texel != TH_MARK_TEXEL) {
        return;
    }
    if (place || ProjMat[2][3] != 0.0) {
        outColor.a = 0.0;
        return;
    }
    float width = float(((c.g & 15) << 8) | c.b);
    vec4 lay = th_text_layout(kind);
    float s = lay.x;
    vec2 pad = lay.yz;
    vec2 size = vec2(width + 2.0 * pad.x, 8.0 + 2.0 * pad.y);
    bool right = corner >= 2;
    bool bottom = corner == 1 || corner == 2;
    vec2 g = pos.xy;
    g.x += right ? (width + pad.x - 8.0) * s : -pad.x * s;
    g.y += bottom ? pad.y * s : -pad.y * s;
    thTextKind = kind;
    thTextBox = vec4(size, lay.w, s);
    thTextLocal = vec2(right ? size.x : 0.0, bottom ? size.y : 0.0);
    gl_Position = ProjMat * ModelViewMat * vec4(g, pos.z, 1.0);
    outColor = vec4(1.0, 1.0, 1.0, color.a);
}
