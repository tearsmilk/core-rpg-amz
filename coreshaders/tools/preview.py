#!/usr/bin/env python3
"""coreshaders - previews rendered with the REAL shader pair (moderngl, EGL; Mesa llvmpipe is enough).

    pip install moderngl
    python3 -I coreshaders/tools/preview.py                     # everything
    python3 -I coreshaders/tools/preview.py --only sunset,rain  # only pano_<name>.png for these
    python3 -I coreshaders/tools/preview.py --walls             # wall_cubes.png, wall_planes.png, wall_single.png
    python3 -I coreshaders/tools/preview.py --custom NAME DIR   # a custom image from DIR (textures/item/panorama)

Walls: 3x3 cubes and 3x3 planes, panels: old look (rim on every edge), rim off (default), rim on (outer outline only),
side view. The camera sits far from the origin and every item gets its own float32 translation, as in the game.

The game side is emulated: a fake item atlas holds the panorama and glow sprites at non-zero offsets, the vertex
stream is the baked item quad order (u0,v0), (u0,v1), (u1,v1), (u1,v0), Position is camera-relative in world axes and
ModelViewMat is the camera rotation (as in 1.21.11 entity rendering), the uniform blocks are filled in std140.
A simple world (sky, grass blocks, a stone wall) is drawn first so the window effect reads in context.
Output: coreshaders/preview/*.png
"""
import math
import os
import struct
import sys

import moderngl
import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import glsl_check  # noqa: E402
import panoramas  # noqa: E402

ROOT = glsl_check.ROOT
OUT = os.path.join(ROOT, "preview")
TEX = os.path.join(ROOT, "pack", "assets", "coreshaders", "textures", "item", "panorama")
IMAGES = panoramas.IMAGES
WIDTH, HEIGHT = 960, 540
PANO_AT = (1024, 512)
GLOW_AT = (48, 16)


def mat_bytes(m):
    return np.asarray(m, np.float32).T.tobytes()


def perspective(fov, aspect, near=0.05, far=200.0):
    f = 1.0 / math.tan(math.radians(fov) / 2)
    return np.array([[f / aspect, 0, 0, 0], [0, f, 0, 0],
                     [0, 0, (far + near) / (near - far), 2 * far * near / (near - far)], [0, 0, -1, 0]])


def look_rotation(forward):
    f = np.asarray(forward, float)
    f /= np.linalg.norm(f)
    r = np.cross(f, [0, 1, 0])
    r /= np.linalg.norm(r)
    u = np.cross(r, f)
    m = np.eye(4)
    m[0, :3], m[1, :3], m[2, :3] = r, u, -f
    return m


SCENE_VS = """#version 330
in vec3 pos; in vec3 col;
uniform mat4 mvp; uniform vec3 cam;
out vec3 vcol; out vec3 wpos;
void main() { gl_Position = mvp * vec4(pos - cam, 1.0); vcol = col; wpos = pos; }
"""
SCENE_FS = """#version 330
in vec3 vcol; in vec3 wpos; out vec4 o;
void main() {
    vec3 c = vcol;
    vec3 g = fract(wpos * 4.0 + 0.001);
    float n = fract(sin(dot(floor(wpos * 16.0 + 0.001), vec3(12.9, 78.2, 37.7))) * 43758.5);
    c *= 0.88 + 0.12 * n;
    o = vec4(c, 1.0);
}
"""
SKY_VS = """#version 330
in vec2 p; out vec2 sp;
void main() { gl_Position = vec4(p, 0.9999, 1.0); sp = p; }
"""
SKY_FS = """#version 330
in vec2 sp; out vec4 o; uniform mat3 inv;
void main() {
    vec3 d = normalize(inv * vec3(sp.x * 1.24, sp.y * 0.70, -1.0));
    float t = clamp(d.y, 0.0, 1.0);
    o = vec4(mix(vec3(0.66, 0.79, 1.0), vec3(0.47, 0.65, 1.0), pow(t, 0.6)), 1.0);
}
"""


def box_faces(x0, y0, z0, x1, y1, z1):
    return {
        "up": [(x0, y1, z0), (x0, y1, z1), (x1, y1, z1), (x1, y1, z0)],
        "down": [(x0, y0, z1), (x0, y0, z0), (x1, y0, z0), (x1, y0, z1)],
        "north": [(x1, y1, z0), (x1, y0, z0), (x0, y0, z0), (x0, y1, z0)],
        "south": [(x0, y1, z1), (x0, y0, z1), (x1, y0, z1), (x1, y1, z1)],
        "west": [(x0, y1, z0), (x0, y0, z0), (x0, y0, z1), (x0, y1, z1)],
        "east": [(x1, y1, z1), (x1, y0, z1), (x1, y0, z0), (x1, y1, z0)],
    }


def world_mesh(wall):
    verts = []
    def quad(pts, col):
        for i in (0, 1, 2, 0, 2, 3):
            verts.extend(list(pts[i]) + list(col))
    for x in range(-12, 13):
        for z in range(-12, 13):
            f = box_faces(x, -1, z, x + 1, 0, z + 1)
            quad(f["up"], (0.42, 0.66, 0.27))
    if wall:
        for x in range(-2, 4):
            for y in range(0, 3):
                f = box_faces(x, y, -2, x + 1, y + 1, -1)
                quad(f["south"], (0.55, 0.55, 0.56))
                quad(f["up"], (0.62, 0.62, 0.63))
                quad(f["east"], (0.5, 0.5, 0.52))
                quad(f["west"], (0.5, 0.5, 0.52))
    return np.array(verts, np.float32)


class Renderer:
    def __init__(self):
        self.ctx = moderngl.create_context(standalone=True, require=330, backend="egl")
        vs, fs = self._sources()
        self.prog = self.ctx.program(vertex_shader=vs, fragment_shader=fs)
        self.scene = self.ctx.program(vertex_shader=SCENE_VS, fragment_shader=SCENE_FS)
        self.sky = self.ctx.program(vertex_shader=SKY_VS, fragment_shader=SKY_FS)
        self.sky_vao = self.ctx.vertex_array(self.sky, [(self.ctx.buffer(np.array(
            [-1, -1, 1, -1, 1, 1, -1, -1, 1, 1, -1, 1], np.float32).tobytes()), "2f", "p")])
        self.fbo = self.ctx.framebuffer(self.ctx.renderbuffer((WIDTH, HEIGHT), 4, samples=0),
                                        self.ctx.depth_renderbuffer((WIDTH, HEIGHT)))
        self.light = self.ctx.texture((16, 16), 4, bytes([255] * 16 * 16 * 4))
        self.light.filter = (moderngl.NEAREST, moderngl.NEAREST)
        self.blocks = {}
        for i, name in enumerate(("DynamicTransforms", "Projection", "Globals", "Fog", "Lighting")):
            if name in self.prog:
                self.prog[name].binding = i
                self.blocks[name] = (i, None)

    def _sources(self):
        import zipfile
        with zipfile.ZipFile(glsl_check.JAR) as z:
            vanilla = {n: z.read(n).decode() for n in z.namelist() if n.startswith("assets/minecraft/shaders/")}
        core = os.path.join(glsl_check.ASSETS, "minecraft", "shaders", "core", glsl_check.CORE)
        vs = glsl_check.expand(open(core + ".vsh", encoding="utf-8").read(), vanilla, set())
        fs = glsl_check.expand(open(core + ".fsh", encoding="utf-8").read(), vanilla, set())
        return vs, fs

    def atlas(self, name, tex=None):
        tex = tex or TEX
        pano = np.array(Image.open(os.path.join(tex, name + ".png")).convert("RGBA"))
        glow = np.array(Image.open(os.path.join(tex, name + "_glow.png")).convert("RGBA"))
        a = np.zeros((1024, 2048, 4), np.uint8)
        a[..., :] = (120, 80, 200, 255)
        y, x = PANO_AT[1], PANO_AT[0]
        a[y:y + 512, x:x + 1024] = pano
        a[GLOW_AT[1]:GLOW_AT[1] + 16, GLOW_AT[0]:GLOW_AT[0] + 16] = glow
        t = self.ctx.texture((2048, 1024), 4, a.tobytes())
        t.filter = (moderngl.NEAREST, moderngl.NEAREST)
        return t

    def ubo(self, name, data):
        idx, old = self.blocks[name]
        if old is not None:
            old.release()
        b = self.ctx.buffer(data)
        b.bind_to_uniform_block(idx)
        self.blocks[name] = (idx, b)

    def item_vao(self, quads, tint):
        rows = []
        for q in quads:
            pts, (u0, v0, u1, v1), normal = q[:3]
            qt = q[3] if len(q) > 3 else tint
            uvs = [(u0, v0), (u0, v1), (u1, v1), (u1, v0)]
            for p, uv in zip(pts, uvs):
                rows.append(struct.pack("3f4f2f2f2i3f", *p, *qt, *uv, 0.0, 0.0, 240, 240, *normal))
        vbo = self.ctx.buffer(b"".join(rows))
        idx = []
        for q in range(len(quads)):
            b = q * 4
            idx += [b, b + 1, b + 2, b, b + 2, b + 3]
        ibo = self.ctx.buffer(np.array(idx, np.int32).tobytes())
        names = ["Position", "Color", "UV0", "UV1", "UV2", "Normal"]
        fmts = ["3f", "4f", "2f", "2f", "2i", "3f"]
        fmt = " ".join(f if n in self.prog else f.replace("f", "x4").replace("2i", "8x").replace("3x4", "12x")
                       .replace("4x4", "16x").replace("2x4", "8x") for n, f in zip(names, fmts))
        attrs = [n for n in names if n in self.prog]
        return self.ctx.vertex_array(self.prog, [(vbo, fmt, *attrs)], ibo)

    def render(self, name, cam, target, items, wall=False, game_time=0.3, tint=(1, 1, 1, 1), tex=None, origin=None):
        ctx = self.ctx
        self.fbo.use()
        ctx.viewport = (0, 0, WIDTH, HEIGHT)
        ctx.clear(0, 0, 0, 1, depth=1.0)
        cam = np.asarray(cam, float)
        rot = look_rotation(np.asarray(target, float) - cam)
        proj = perspective(70, WIDTH / HEIGHT)
        ctx.disable(moderngl.DEPTH_TEST)
        self.sky["inv"].write(np.asarray(rot[:3, :3], np.float32).tobytes())
        self.sky_vao.render()
        ctx.enable(moderngl.DEPTH_TEST)
        wm = world_mesh(wall)
        svao = ctx.vertex_array(self.scene, [(ctx.buffer(wm.tobytes()), "3f 3f", "pos", "col")])
        self.scene["mvp"].write(mat_bytes(proj @ rot))
        self.scene["cam"].value = tuple(cam)
        svao.render()
        atlas = self.atlas(name, tex)
        atlas.use(0)
        self.light.use(2)
        if "Sampler0" in self.prog:
            self.prog["Sampler0"].value = 0
        if "Sampler2" in self.prog:
            self.prog["Sampler2"].value = 2
        self.ubo("DynamicTransforms", mat_bytes(rot) + struct.pack("4f", 1, 1, 1, 1) + struct.pack("4f", 0, 0, 0, 0)
                 + mat_bytes(np.eye(4)))
        self.ubo("Projection", mat_bytes(proj))
        self.ubo("Globals", struct.pack("3i4x3f4x2ff f ii8x", 0, 0, 0, 0, 0, 0, WIDTH, HEIGHT, 1.0, game_time, 0, 0))
        self.ubo("Fog", struct.pack("4f6f8x", 0.7, 0.8, 1.0, 1.0, 1000, 2000, 1000, 2000, 1000, 1000))
        if "Lighting" in self.blocks:
            self.ubo("Lighting", struct.pack("3f4x3f4x", 0.2, 1.0, -0.7, -0.2, 1.0, 0.7))
        ctx.enable(moderngl.BLEND)
        ctx.blend_func = moderngl.SRC_ALPHA, moderngl.ONE_MINUS_SRC_ALPHA
        aw, ah = 2048.0, 1024.0
        pano_uv = (PANO_AT[0] / aw, PANO_AT[1] / ah, (PANO_AT[0] + 1024) / aw, (PANO_AT[1] + 512) / ah)
        glow_uv = (GLOW_AT[0] / aw, GLOW_AT[1] / ah, (GLOW_AT[0] + 16) / aw, (GLOW_AT[1] + 16) / ah)
        far = np.asarray(origin if origin is not None else (0.0, 0.0, 0.0), float)
        for it in items:
            kind, data = it[0], it[1]
            masks = it[2] if len(it) > 2 else None
            if kind == "cube":
                lo = np.asarray(data, float)
                trans = np.float32(lo + 0.5 + far - (cam + far))
                f = box_faces(-0.5, -0.5, -0.5, 0.5, 0.5, 0.5)
                normals = {"up": (0, 1, 0), "down": (0, -1, 0), "north": (0, 0, -1), "south": (0, 0, 1),
                           "west": (-1, 0, 0), "east": (1, 0, 0)}
                quads = []
                for k, pts in f.items():
                    m = 0 if masks is None else masks[k]
                    quads.append(([tuple(np.float32(trans + np.float32(p))) for p in pts], pano_uv, normals[k],
                                  encode_tint(tint, m)))
                vao = self.item_vao(quads, tint)
                vao.render()
            else:
                center = np.asarray(data, float)
                trans = np.float32(center + far - (cam + far))
                m = 0 if masks is None else masks
                for half, uv in ((0.5 + PLANE_PAD / 16.0, pano_uv), (0.5 * 22 / 16, glow_uv)):
                    pts = [(-half, half, 0.0), (-half, -half, 0.0), (half, -half, 0.0), (half, half, 0.0)]
                    quad = ([tuple(np.float32(trans + np.float32(p))) for p in pts], uv, (0, 0, 1), encode_tint(tint, m))
                    vao = self.item_vao([quad], tint)
                    vao.render()
        ctx.disable(moderngl.BLEND)
        atlas.release()
        img = Image.frombytes("RGBA", (WIDTH, HEIGHT), self.fbo.read(components=4)).transpose(Image.FLIP_TOP_BOTTOM)
        return img.convert("RGB")


PLANE_PAD = 0.02
FACE_AXES = {"north": ((-1, 0, 0), (0, -1, 0)), "south": ((1, 0, 0), (0, -1, 0)), "east": ((0, 0, -1), (0, -1, 0)),
             "west": ((0, 0, 1), (0, -1, 0)), "up": ((1, 0, 0), (0, 0, 1)), "down": ((1, 0, 0), (0, 0, -1))}
FACE_NORMALS = {"up": (0, 1, 0), "down": (0, -1, 0), "north": (0, 0, -1), "south": (0, 0, 1), "west": (-1, 0, 0),
                "east": (1, 0, 0)}


def encode_tint(tint, mask):
    c = [int(round(v * 255)) for v in tint[:3]]
    c[1] = (c[1] & 0xFC) | (mask & 3)
    c[2] = (c[2] & 0xFC) | ((mask >> 2) & 3)
    return (c[0] / 255.0, c[1] / 255.0, c[2] / 255.0, tint[3])


def cube_masks(cubes, pos, rim=True):
    """The same rule as Faces.cubeMasks, written in world axes of the preview quads."""
    cs = set(map(tuple, cubes))
    out = {}
    for face, (u, v) in FACE_AXES.items():
        n = FACE_NORMALS[face]
        add = lambda a, b: tuple(int(x) + int(y) for x, y in zip(a, b))
        if not rim or add(pos, n) in cs:
            out[face] = 0
            continue
        m = 0
        for bit, e in enumerate((tuple(-x for x in u), u, tuple(-x for x in v), v)):
            if add(pos, e) not in cs and add(add(pos, n), e) not in cs:
                m |= 1 << bit
        out[face] = m
    return out


def plane_mask(planes, pos, rim=True):
    ps = set(map(tuple, planes))
    if not rim:
        return 0
    m = 0
    for bit, e in enumerate(((-1, 0), (1, 0), (0, 1), (0, -1))):
        if (pos[0] + e[0], pos[1] + e[1]) not in ps:
            m |= 1 << bit
    return m


def wall_scenes(r, name="sky_sea", tex=None, prefix="wall"):
    far = (1234.37, 0.0, -876.61)
    cubes = [(x, y, -1) for x in range(-1, 2) for y in range(0, 3)]
    cam, tgt = (0.9, 1.7, 3.6), (0.5, 1.3, -0.5)
    full = {f: 15 for f in FACE_AXES}
    legacy = r.render(name, cam, tgt, [("cube", c, full) for c in cubes], tex=tex, origin=far)
    clean = r.render(name, cam, tgt, [("cube", c, cube_masks(cubes, c, False)) for c in cubes], tex=tex, origin=far)
    outline = r.render(name, cam, tgt, [("cube", c, cube_masks(cubes, c, True)) for c in cubes], tex=tex, origin=far)
    side = r.render(name, (3.4, 2.2, 2.2), (0.0, 1.2, -0.5), [("cube", c, cube_masks(cubes, c, False)) for c in cubes],
                    tex=tex, origin=far)
    sheet([legacy, clean, outline, side], 2).save(os.path.join(OUT, f"{prefix}_cubes.png"))
    planes = [(x, y) for x in range(-1, 2) for y in range(0, 3)]
    pitems = lambda rim: [("plane", (x + 0.5, y + 0.5, -0.985), plane_mask(planes, (x, y), rim)) for x, y in planes]
    pcam, ptgt = (0.6, 1.6, 3.0), (0.5, 1.4, -2.0)
    p_legacy = r.render(name, pcam, ptgt, [("plane", (x + 0.5, y + 0.5, -0.985), 15) for x, y in planes], wall=True,
                        tex=tex, origin=far)
    p_clean = r.render(name, pcam, ptgt, pitems(False), wall=True, tex=tex, origin=far)
    p_outline = r.render(name, pcam, ptgt, pitems(True), wall=True, tex=tex, origin=far)
    p_side = r.render(name, (2.9, 1.2, 0.6), (0.0, 1.5, -2.0), pitems(False), wall=True, tex=tex, origin=far)
    sheet([p_legacy, p_clean, p_outline, p_side], 2).save(os.path.join(OUT, f"{prefix}_planes.png"))
    single = [r.render(name, VIEWS[0][1], VIEWS[0][2], [("cube", (0, 0, 0), {f: 15 for f in FACE_AXES})], tex=tex),
              r.render(name, VIEWS[0][1], VIEWS[0][2], [("cube", (0, 0, 0), cube_masks([(0, 0, 0)], (0, 0, 0), False))],
                       tex=tex)]
    sheet(single, 2).save(os.path.join(OUT, f"{prefix}_single.png"))


VIEWS = [
    ("front", (0.5, 1.62, 2.6), (0.5, 0.5, 0.5)),
    ("left", (-1.6, 1.62, 2.0), (0.5, 0.5, 0.5)),
    ("right", (2.4, 1.0, 1.4), (0.5, 0.6, 0.5)),
]


def pano_sheet(r, name, scale=0.6, tex=None):
    shots = [r.render(name, VIEWS[0][1], VIEWS[0][2], [("cube", (0, 0, 0))], tex=tex),
             r.render(name, (0.9, 1.4, 1.9), (0.5, 1.2, -1.0), [("plane", (0.5, 1.5, -0.99))], wall=True, tex=tex)]
    w, h = int(WIDTH * scale), int(HEIGHT * scale)
    eq = Image.open(os.path.join(tex or TEX, name + ".png")).convert("RGB").resize((w * 2, w), Image.LANCZOS)
    out = Image.new("RGB", (w * 2, h + w), (20, 20, 20))
    for i, im in enumerate(shots):
        out.paste(im.resize((w, h), Image.LANCZOS), (i * w, 0))
    out.paste(eq, (0, h))
    out.save(os.path.join(OUT, f"pano_{name}.png"))


def sheet(images, cols, scale=0.5):
    w, h = int(WIDTH * scale), int(HEIGHT * scale)
    rows = (len(images) + cols - 1) // cols
    out = Image.new("RGB", (w * cols, h * rows), (20, 20, 20))
    for i, im in enumerate(images):
        out.paste(im.resize((w, h), Image.LANCZOS), ((i % cols) * w, (i // cols) * h))
    return out


def main():
    os.makedirs(OUT, exist_ok=True)
    r = Renderer()
    if "--custom" in sys.argv:
        i = sys.argv.index("--custom")
        name, tex = sys.argv[i + 1], sys.argv[i + 2]
        pano_sheet(r, name, tex=tex)
        wall_scenes(r, name, tex=tex, prefix="custom_" + name)
        print("custom", name)
        return
    if "--walls" in sys.argv:
        wall_scenes(r)
        print("walls")
        return
    if "--only" in sys.argv:
        for name in sys.argv[sys.argv.index("--only") + 1].split(","):
            pano_sheet(r, name)
            print("pano", name)
        return
    all_cube = []
    for name in IMAGES:
        shots = []
        for vname, cam, tgt in VIEWS:
            im = r.render(name, cam, tgt, [("cube", (0, 0, 0))])
            shots.append(im)
        sheet(shots, 3).save(os.path.join(OUT, f"cube_{name}.png"))
        all_cube.append(shots[0])
        print("cube", name)
    sheet(all_cube, 4).save(os.path.join(OUT, "cubes_all.png"))
    planes = []
    for name in IMAGES:
        im = r.render(name, (0.9, 1.4, 1.9), (0.5, 1.2, -1.0), [("plane", (0.5, 1.5, -0.99))], wall=True)
        planes.append(im)
    sheet(planes, 4).save(os.path.join(OUT, "planes_all.png"))
    for name in IMAGES:
        pano_sheet(r, name)
    big = r.render("sky_sea", (0.5, 1.6, 3.2), (0.5, 0.5, 0.5), [("cube", (0, 0, 0)), ("cube", (1, 0, 0)),
                                                                  ("cube", (-1, 0, -1))])
    big.save(os.path.join(OUT, "sky_sea_cubes_big.png"))
    turn = [r.render("sky_sea", (0.5, 1.6, 3.2), tgt, [("cube", (0, 0, 0))])
            for tgt in ((0.5, 0.5, 0.5), (1.6, 0.5, 0.5), (-0.6, 0.5, 0.5))]
    sheet(turn, 3).save(os.path.join(OUT, "sky_sea_camera_turn.png"))
    tinted = [r.render("sky_sea", (0.5, 1.6, 3.2), (0.5, 0.5, 0.5), [("cube", (0, 0, 0))], tint=t)
              for t in ((1, 0.55, 0.55, 1), (0.6, 1, 0.6, 1), (1, 0.85, 0.4, 1))]
    sheet(tinted, 3).save(os.path.join(OUT, "sky_sea_tints.png"))
    anim = [r.render("aurora", (0.5, 1.6, 3.2), (0.5, 0.5, 0.5), [("cube", (0, 0, 0))], game_time=g)
            for g in (0.1, 0.1005, 0.101)]
    sheet(anim, 3).save(os.path.join(OUT, "aurora_time.png"))
    drift = [r.render("sky_sea", (0.5, 1.6, 3.2), (0.5, 0.5, 0.5), [("cube", (0, 0, 0))], game_time=g)
             for g in (0.1, 0.1125, 0.125)]
    sheet(drift, 3).save(os.path.join(OUT, "sky_sea_drift.png"))
    rain = [r.render("rain", (0.5, 1.6, 3.2), (0.5, 0.5, 0.5), [("cube", (0, 0, 0))], game_time=g)
            for g in (117.3 / 1200.0, 117.5 / 1200.0, 0.1)]
    sheet(rain, 3).save(os.path.join(OUT, "rain_time.png"))
    walk = [r.render("walk", (0.9, 1.4, 1.9), (0.5, 1.2, -1.0), [("plane", (0.5, 1.5, -0.99))], wall=True, game_time=g)
            for g in (0.1, 0.1125, 0.125)]
    sheet(walk, 3).save(os.path.join(OUT, "walk_drift.png"))
    wall_scenes(r)
    print("previews in", OUT)


if __name__ == "__main__":
    main()
