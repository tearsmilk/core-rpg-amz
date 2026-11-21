#version 330

#moj_import <aurahud:ah_common.glsl>

#if AH_HOTBAR_SLICE
flat in int ahSlice;
in vec2 ahLocal;
in vec4 ahUvA;
in vec4 ahUvC;
#endif

vec4 ah_sprite_fragment(vec4 color, vec4 vertexColor) {
#if AH_ACTIVE
    if (vertexColor == vec4(0.0)) {
        return vec4(0.0);
    }
#endif
#if AH_HOTBAR_SLICE
    if (ahSlice == 1) {
        vec2 a = ahUvA.xy / max(ahUvA.w, 0.000001);
        vec2 b = ahUvC.xy / max(ahUvC.w, 0.000001);
        for (int i = 8; i >= 0; i--) {
            if (((AH_HOTBAR_HIDDEN >> i) & 1) != 0) {
                continue;
            }
            vec2 p = ahLocal - AH_HOTBAR_POS[i];
            if (p.x >= 0.0 && p.y >= 0.0 && p.x < 22.0 && p.y < 22.0) {
                return texture(Sampler0, mix(a, b, (vec2(20.0 * float(i), 0.0) + p) / vec2(182.0, 22.0))) * vertexColor;
            }
        }
        return vec4(0.0);
    }
#endif
    return color;
}
