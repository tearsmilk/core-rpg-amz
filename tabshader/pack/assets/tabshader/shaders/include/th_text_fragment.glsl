#version 330

#moj_import <tabshader:th_common.glsl>

flat in int thTextKind;
flat in vec4 thTextBox;
in vec2 thTextLocal;

vec4 th_text_fragment(vec4 color, vec4 vertexColor) {
    if (thTextKind == 0) {
        return color;
    }
    if (thTextKind >= 5) {
        vec2 g = thTextLocal;
        float px = max(fwidth(g.x), 1e-4);
        vec4 radii = thTextKind == 5 ? vec4(TH_RADIUS_HUD, TH_RADIUS_HUD, 0.0, 0.0) : vec4(TH_RADIUS_HUD);
        vec4 open = thTextKind == 5 ? vec4(0.0, 0.0, 0.0, 1.0) : vec4(0.0);
        vec4 c = th_panel(g, thTextBox.xy, thTextBox.zw, radii, px, open);
        if (thTextKind == 6 && c.a > 0.0) {
            float line = 1.0 - smoothstep(0.0, px, abs(g.y - thTextBox.y - 10.0) - px * 0.5);
            float fade = smoothstep(thTextBox.x + 2.0, thTextBox.x + 8.0, g.x) * smoothstep(thTextBox.z - 2.0, thTextBox.z - 8.0, g.x);
            c.rgb = mix(c.rgb, vec3(1.0, 0.9, 0.94), line * fade * 0.6);
        }
        return vec4(c.rgb, c.a * vertexColor.a);
    }
    float s = thTextBox.w;
    vec2 g = thTextLocal * s;
    float px = max(fwidth(g.x), 1e-4);
    float r = thTextBox.z * s;
    vec4 c = th_panel(g + vec2(37.0, 11.0) * float(thTextKind), vec2(0.0) + vec2(37.0, 11.0) * float(thTextKind), thTextBox.xy * s + vec2(37.0, 11.0) * float(thTextKind), vec4(min(r, 0.5 * thTextBox.y * s)), px, vec4(0.0));
    return vec4(c.rgb, c.a * vertexColor.a);
}
