#version 330

#moj_import <textshader:ts_fragment.glsl>

vec4 ts_hook_text_fragment(vec4 color, vec4 vertexColor, vec2 uv) {
    return ts_fragment(color, vertexColor, ColorModulator);
}
