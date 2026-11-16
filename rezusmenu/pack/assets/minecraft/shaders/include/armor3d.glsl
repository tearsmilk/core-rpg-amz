// AZERUS armor3d — 3D armor from equipment layer textures (vertex part). Included by core/entity.vsh ONLY in the
// armor pipelines (ALPHA_CUTOUT + NO_OVERLAY + PER_FACE_LIGHTING: armor_cutout_no_cull, armor_decal_cutout_no_cull,
// armor_translucent). Technique: like objmc (Godlander/objmc, MIT) — each vanilla armor quad becomes one baked
// Blockbench face; the limb frame comes from the 4 vertices of the vanilla quad via GL_KHR_shader_subgroup_quad.
// Own code; texture format and Python twin: nexo-items/tools/armor3d_codec.py (shade_quad = this file, line by line).
//
// Textures it reacts to (anything else: no change at all):
//  * data layer: width exactly 128, height >= 147, texel (0,128) == A3 3D 7E alpha 16 (header row 128, records from
//    row 129, one per row: 12 px corner xyz (24-bit), 4 px corner uv (12+12 bit), 1 px flags);
//  * 2D layer "00": exactly 64x32, texel (0,0) == A3 3D 7E alpha 16 — hidden once a quad proves 3D works.
// Without the subgroup extension (macOS GL 4.1, old drivers) the code below is compiled out: data layers stay at
// alpha <= 24 (cut by ALPHA_CUTOUT 0.1) and the 2D layer is shown, i.e. plain 2D armor.
// Quad check: the 4 lanes of the subgroup quad must carry exactly the 4 UV corners of ONE vanilla face; otherwise the
// vertex keeps the vanilla path (data layer → invisible, 2D layer → visible). The fragment shader discards triangles
// mixing states (az3dState not exactly 0, 1 or 2), so a broken quad never draws a stretched triangle.

const ivec4 AZ3D_MAGIC = ivec4(163, 61, 126, 16);   // A3 3D 7E, alpha 16

ivec4 az3d_texel(ivec2 p) {
    return ivec4(texelFetch(Sampler0, p, 0) * 255.0 + 0.5);
}

// (u1, v1, u2, v2) of the vanilla polygon (ModelPart.Cube), pixels of the 64x32 layout.
// part 0 body: texOffs(16,16) 8x12x4; 1 arm: (40,16) 4x12x4; 2 leg: (0,16) 4x12x4. face: DOWN UP WEST NORTH EAST SOUTH
ivec4 az3d_rect(int part, int face) {
    ivec2 to = part == 0 ? ivec2(16, 16) : (part == 1 ? ivec2(40, 16) : ivec2(0, 16));
    ivec3 d = part == 0 ? ivec3(8, 12, 4) : ivec3(4, 12, 4);
    int j = to.x, k = to.x + d.z, l = to.x + d.z + d.x, m = to.x + d.z + 2 * d.x;
    int n = to.x + 2 * d.z + d.x, o = to.x + 2 * d.z + 2 * d.x;
    int p = to.y, q = to.y + d.z, r = to.y + d.z + d.y;
    if (face == 0) return ivec4(k, p, l, q);
    if (face == 1) return ivec4(l, q, m, p);
    if (face == 2) return ivec4(j, q, k, r);
    if (face == 3) return ivec4(k, q, l, r);
    if (face == 4) return ivec4(l, q, n, r);
    return ivec4(n, q, o, r);
}

// corner 0..3 = (u2,v1) (u1,v1) (u1,v2) (u2,v2); -1 if uv is not a corner of rect
int az3d_corner(ivec4 rect, ivec2 uv) {
    if (uv == rect.zy) return 0;
    if (uv == rect.xy) return 1;
    if (uv == rect.xw) return 2;
    if (uv == rect.zw) return 3;
    return -1;
}

// box corner bits (x max?, y max?, z max?) of polygon corner c — ModelPart.Cube vertex order
ivec3 az3d_bits(int face, int c) {
    if (face == 0) return c == 0 ? ivec3(1, 0, 1) : (c == 1 ? ivec3(0, 0, 1) : (c == 2 ? ivec3(0, 0, 0) : ivec3(1, 0, 0)));
    if (face == 1) return c == 0 ? ivec3(1, 1, 0) : (c == 1 ? ivec3(0, 1, 0) : (c == 2 ? ivec3(0, 1, 1) : ivec3(1, 1, 1)));
    if (face == 2) return c == 0 ? ivec3(0, 0, 0) : (c == 1 ? ivec3(0, 0, 1) : (c == 2 ? ivec3(0, 1, 1) : ivec3(0, 1, 0)));
    if (face == 3) return c == 0 ? ivec3(1, 0, 0) : (c == 1 ? ivec3(0, 0, 0) : (c == 2 ? ivec3(0, 1, 0) : ivec3(1, 1, 0)));
    if (face == 4) return c == 0 ? ivec3(1, 0, 1) : (c == 1 ? ivec3(1, 0, 0) : (c == 2 ? ivec3(1, 1, 0) : ivec3(1, 1, 1)));
    return c == 0 ? ivec3(0, 0, 1) : (c == 1 ? ivec3(1, 0, 1) : (c == 2 ? ivec3(1, 1, 1) : ivec3(0, 1, 1)));
}

vec3 az3d_face_normal(int face) {
    if (face == 0) return vec3(0.0, -1.0, 0.0);
    if (face == 1) return vec3(0.0, 1.0, 0.0);
    if (face == 2) return vec3(-1.0, 0.0, 0.0);
    if (face == 3) return vec3(0.0, 0.0, -1.0);
    if (face == 4) return vec3(1.0, 0.0, 0.0);
    return vec3(0.0, 0.0, 1.0);
}

// corner position (unmirrored) relative to the part pivot, model pixels (y down)
vec3 az3d_local(int part, int face, int c, float grow) {
    vec3 mn = part == 0 ? vec3(-4.0, 0.0, -2.0) : (part == 1 ? vec3(-3.0, -2.0, -2.0) : vec3(-2.0, 0.0, -2.0));
    vec3 dm = part == 0 ? vec3(8.0, 12.0, 4.0) : vec3(4.0, 12.0, 4.0);
    vec3 b = vec3(az3d_bits(face, c));
    return mix(mn - grow, mn + dm + grow, b);
}

// deformation: list 0 (humanoid: chestplate 1.0, boots legs 0.9), list 1 (humanoid_leggings: 0.5, legs 0.4)
float az3d_grow(int listType, int part) {
    if (listType == 0) return part == 2 ? 0.9 : 1.0;
    return part == 2 ? 0.4 : 0.5;
}

float az3d_coord(ivec4 t) {
    return float(t.r * 65536 + t.g * 256 + t.b) / 65536.0 - 128.0;
}

vec2 az3d_uv(ivec4 t) {
    return vec2(float(t.r * 16 + t.g / 16), float((t.g - (t.g / 16) * 16) * 256 + t.b)) / 16.0;
}

vec3 az3d_corner_pos(int row, int t) {
    return vec3(az3d_coord(az3d_texel(ivec2(t * 3, row))), az3d_coord(az3d_texel(ivec2(t * 3 + 1, row))),
                az3d_coord(az3d_texel(ivec2(t * 3 + 2, row))));
}

// pos/nrm/uv/col: in-out vertex data. state: 0 vanilla, 1 baked 3D face, 2 hidden. flags: record flags.
void az3d_apply(inout vec3 pos, inout vec3 nrm, inout vec2 uv, inout vec4 col, out float state, out int flags) {
    state = 0.0;
    flags = 0;
#ifdef AZ3D_SUBGROUP
    // Broadcasts first, by every lane (uniform control flow). Cheap: armor pipelines only.
    vec3 qp0 = subgroupQuadBroadcast(Position, 0u);
    vec3 qp1 = subgroupQuadBroadcast(Position, 1u);
    vec3 qp2 = subgroupQuadBroadcast(Position, 2u);
    vec3 qp3 = subgroupQuadBroadcast(Position, 3u);
    vec2 qt0 = subgroupQuadBroadcast(UV0, 0u);
    vec2 qt1 = subgroupQuadBroadcast(UV0, 1u);
    vec2 qt2 = subgroupQuadBroadcast(UV0, 2u);
    vec2 qt3 = subgroupQuadBroadcast(UV0, 3u);

    ivec2 tsize = textureSize(Sampler0, 0);
    int kind = 0;
    if (tsize == ivec2(64, 32)) {
        if (az3d_texel(ivec2(0, 0)) == AZ3D_MAGIC) kind = 2;
    } else if (tsize.x == 128 && tsize.y >= 147) {
        if (az3d_texel(ivec2(0, 128)) == AZ3D_MAGIC) kind = 1;
    }
    if (kind == 0) return;

    vec3 qp[4] = vec3[4](qp0, qp1, qp2, qp3);
    ivec2 qu[4] = ivec2[4](ivec2(round(qt0 * vec2(64.0, 32.0))), ivec2(round(qt1 * vec2(64.0, 32.0))),
                           ivec2(round(qt2 * vec2(64.0, 32.0))), ivec2(round(qt3 * vec2(64.0, 32.0))));
    ivec2 umin = min(min(qu[0], qu[1]), min(qu[2], qu[3]));
    ivec2 umax = max(max(qu[0], qu[1]), max(qu[2], qu[3]));
    int part = -1;
    int face = -1;
    ivec4 rect = ivec4(0);
    for (int p = 0; p < 3; p++) {
        for (int f = 0; f < 6; f++) {
            ivec4 r = az3d_rect(p, f);
            if (ivec4(min(r.xy, r.zw), max(r.xy, r.zw)) == ivec4(umin, umax)) {
                part = p;
                face = f;
                rect = r;
            }
        }
    }
    if (part < 0) return;
    int cs[4];
    int mask = 0;
    for (int i = 0; i < 4; i++) {
        cs[i] = az3d_corner(rect, qu[i]);
        if (cs[i] < 0) return;
        mask |= 1 << cs[i];
    }
    if (mask != 15) return;
    if (kind == 2) {          // 2D layer: 3D works for this quad → hide it
        state = 2.0;
        return;
    }

    int listType = az3d_texel(ivec2(1, 128)).g;
    if (listType > 1) return;
    vec3 W[4];
    for (int i = 0; i < 4; i++) W[cs[i]] = qp[i];
    float g = az3d_grow(listType, part);
    vec3 L0 = az3d_local(part, face, 0, g);
    vec3 L1 = az3d_local(part, face, 1, g);
    vec3 L2 = az3d_local(part, face, 2, g);
    vec3 nf = az3d_face_normal(face);
    vec3 ea = W[0] - W[1];
    vec3 eb = W[2] - W[1];
    vec3 la = L0 - L1;
    vec3 lb = L2 - L1;
    // left arm / left leg are mirror() parts: same UVs, x reflected, polygon order reversed
    bool mirrored = dot(cross(ea, eb), Normal) * dot(cross(la, lb), nf) < 0.0;
    if (mirrored && part == 0) return;
    vec3 flip = vec3(mirrored ? -1.0 : 1.0, 1.0, 1.0);
    la *= flip;
    lb *= flip;
    nf *= flip;
    float s = (length(ea) + length(eb)) / (length(la) + length(lb));
    float sgn = dot(cross(la, lb), nf) >= 0.0 ? 1.0 : -1.0;
    vec3 Nw = normalize(cross(ea, eb)) * sgn;
    // A: limb-local pixels → Position space (rotation × scale), exact from the vanilla quad
    mat3 A = outerProduct(ea, la / dot(la, la)) + outerProduct(eb, lb / dot(lb, lb)) + outerProduct(s * Nw, nf);

    int slot;
    if (part == 0) slot = 0;
    else if (listType == 0) slot = part == 1 ? (mirrored ? 2 : 1) : (mirrored ? 4 : 3);
    else {
        if (part != 2) return;
        slot = mirrored ? 2 : 1;
    }
    int row = 129 + slot * 6 + face;
    if (row >= tsize.y) return;
    ivec4 fl = az3d_texel(ivec2(16, row));
    if ((fl.r & 1) == 0) {    // no baked face for this quad in this layer
        state = 2.0;
        return;
    }
    flags = fl.r;
    int cself = az3d_corner(rect, ivec2(round(UV0 * vec2(64.0, 32.0))));
    int t = mirrored ? 3 - cself : cself;
    vec3 T0 = az3d_corner_pos(row, 0);
    vec3 T1 = az3d_corner_pos(row, 1);
    vec3 T2 = az3d_corner_pos(row, 2);
    vec3 Tt = t == 0 ? T0 : (t == 1 ? T1 : (t == 2 ? T2 : az3d_corner_pos(row, 3)));
    vec3 Ls = az3d_local(part, face, cself, g) * flip;
    pos = Position + A * (Tt - Ls);
    vec3 nl = -cross(T0 - T1, T2 - T1);
    nrm = length(nl) > 1e-6 ? normalize(A * nl) : Nw;
    uv = az3d_uv(az3d_texel(ivec2(12 + t, row))) / vec2(tsize);
    if ((flags & 4) == 0) col = vec4(1.0);
    state = 1.0;
#endif
}
