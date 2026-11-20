#version 330

#moj_import <minecraft:projection.glsl>
#moj_import <textshader:ts_plain.glsl>

flat out int tsEffect;
flat out int tsPalette;
flat out int tsLetter;
flat out int tsShade;
out vec2 tsScreen;
out vec4 tsCornerA;
out vec4 tsCornerC;
out float tsWeightA;
out float tsWeightC;

void ts_vertex(vec4 color, vec2 uv, int vertexId, vec4 light, inout vec4 outColor) {
    tsEffect = -1;
    tsPalette = 0;
    tsLetter = -1;
    tsShade = 0;
    tsScreen = vec2(0.0);
    tsCornerA = vec4(0.0);
    tsCornerC = vec4(0.0);
    tsWeightA = 0.0;
    tsWeightC = 0.0;
    ivec4 code = ts_decode(color.rgb);
    if (code.x < 0) {
        return;
    }
    if (ProjMat[2][3] != 0.0) {
        ts_plain(color, light, outColor);
        return;
    }
    tsEffect = code.x;
    tsPalette = code.y;
    tsLetter = code.z;
    tsShade = code.w;
    int corner = vertexId % 4;
    vec2 ndc = gl_Position.xy / gl_Position.w;
    if (corner == 0) {
        tsCornerA = vec4(ndc, uv);
        tsWeightA = 1.0;
    } else if (corner == 2) {
        tsCornerC = vec4(ndc, uv);
        tsWeightC = 1.0;
    }
    vec2 side = vec2(corner >= 2 ? 1.0 : -1.0, (corner == 1 || corner == 2) ? -1.0 : 1.0);
    gl_Position.xy += side * abs(vec2(ProjMat[0][0], ProjMat[1][1])) * ts_margin(code.x) * gl_Position.w;
    tsScreen = gl_Position.xy / gl_Position.w;
}
