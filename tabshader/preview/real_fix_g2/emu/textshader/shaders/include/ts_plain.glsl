#version 330

#moj_import <textshader:ts_common.glsl>

void ts_plain(vec4 color, vec4 light, inout vec4 outColor) {
    ivec4 code = ts_decode(color.rgb);
    if (code.x < 0) {
        return;
    }
    vec3 rgb = ts_world_color(code.x, code.y);
    outColor = vec4(code.w == 1 ? rgb * 0.25 : rgb, color.a) * light;
}
