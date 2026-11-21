#version 330

// spacesky: ванильные звёзды не рисуем — звёзды уже в шейдере неба (sky.fsh), иначе ночью они двоились бы.

#moj_import <minecraft:dynamictransforms.glsl>

out vec4 fragColor;

void main() {
    discard;
}
