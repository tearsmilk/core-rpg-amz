#version 330

// spacesky: частица параллакса (см. stars.vsh) — ядро и мягкий ореол; наложение прохода звёзд аддитивное
// (SRC_ALPHA, ONE), альфа — яркость звёзд клиента (ColorModulator: тип мира галактики — 1). Ванильная звезда
// (мир без метки, режим everywhere: false) — как в ванили.

#moj_import <minecraft:dynamictransforms.glsl>

in vec2 ssLoc;
in vec3 ssCol;
flat in int ssVanilla;

out vec4 fragColor;

void main() {
    if (ssVanilla != 0) {
        fragColor = ColorModulator;
        return;
    }
    float q = dot(ssLoc, ssLoc);
    if (q >= 1.0) {
        discard;
    }
    float g = exp(-q * 9.0) + 0.22 * exp(-q * 3.0);
    fragColor = vec4(ssCol * g * (1.0 - q), clamp(ColorModulator.a, 0.0, 1.0));
}
