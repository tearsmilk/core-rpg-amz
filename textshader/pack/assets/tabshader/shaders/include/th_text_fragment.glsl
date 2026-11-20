#version 330

#moj_import <tabshader:th_common.glsl>

flat in int thTextKind;
flat in vec4 thTextBox;
in vec2 thTextLocal;

vec4 th_text_fragment(vec4 color, vec4 vertexColor) {
    if (thTextKind == 0) {
        return color;
    }
    float s = thTextBox.w;
    vec2 g = thTextLocal * s;
    float px = max(fwidth(g.x), 1e-4);
    float r = thTextBox.z * s;
    vec4 c = th_panel(g + vec2(37.0, 11.0) * float(thTextKind), vec2(0.0) + vec2(37.0, 11.0) * float(thTextKind), thTextBox.xy * s + vec2(37.0, 11.0) * float(thTextKind), vec4(min(r, 0.5 * thTextBox.y * s)), px, vec4(0.0));
    return vec4(c.rgb, c.a * vertexColor.a);
}
