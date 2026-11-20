#version 330

#moj_import <nameshader:ns_tag.glsl>

vec4 ns_hook_text_fragment(vec4 color, vec4 vertexColor, vec2 uv) {
    return ns_tag_plate_only(color, vertexColor, uv);
}
