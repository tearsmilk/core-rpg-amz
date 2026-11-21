#version 330

#define WS_MARKER_RED 215
#define WS_MAX_RADIUS 64
#define WS_SLOTS 8
#define WS_TAG_A 0
#define WS_INFO 1
#define WS_CENTER 2
#define WS_PROJ 5
#define WS_BOX 21
#define WS_TAG_B 25
#define WS_ROW_PIXELS 26
#define WS_SIGNATURE_RGB ivec3(23, 211, 97)
#define WS_SIGNATURE_ALPHA 13
#define WS_COPIES 2

ivec2 ws_origin(int copy, int slot, ivec2 screen) {
    return copy == 0 ? ivec2(0, slot) : ivec2(screen.x - WS_ROW_PIXELS, screen.y - 1 - slot);
}

ivec3 ws_tag(int slot) {
    return ivec3(87, 83, 200 + slot);
}

vec3 ws_bytes(float v) {
    uint b = floatBitsToUint(v);
    return vec3(float(b >> 24u), float((b >> 16u) & 255u), float((b >> 8u) & 255u));
}

float ws_float(ivec3 bytes) {
    uvec3 u = uvec3(clamp(bytes, ivec3(0), ivec3(255)));
    return uintBitsToFloat((u.x << 24u) | (u.y << 16u) | (u.z << 8u));
}
