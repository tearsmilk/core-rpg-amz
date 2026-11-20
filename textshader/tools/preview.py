#!/usr/bin/env python3
"""textshader - previews rendered with the REAL shader pair (moderngl, EGL; Mesa llvmpipe is enough).

    python3 -I textshader/tools/preview.py                     # every effect: GIF + frame strip, sheet.png
    python3 -I textshader/tools/preview.py --only sparkle,ocean
    python3 -I textshader/tools/preview.py --world             # world_plain.png: perspective text, plain colours

GUI text is emulated like 1.21.11 draws it: vanilla bitmap glyphs (ascii.png, nonlatin_european.png from the client
jar) packed into a 256x256 font atlas, one quad per glyph (corners TL, BL, BR, TR), shadow quad first with the colour
scaled by 0.25 and offset by one pixel, orthographic projection in GUI pixels, GUI scale 3. The colour code is the
same as in the plugin: R = 214, G = effect * 4 | letter bits 2-3, B = letter bits 4-5 << 6 | colour * 4 | letter bits
0-1. GameTime advances 1/24000 per tick, 20 ticks per second.
Output: textshader/preview/
"""
import json
import math
import os
import struct
import sys
import zipfile

import moderngl
import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import glsl_check  # noqa: E402

ROOT = glsl_check.ROOT
OUT = os.path.join(ROOT, "preview")
SCALE = 3
GUI_W, GUI_H = 200, 44

EFFECTS = [
    "shake", "wave", "jump", "flip", "skew", "pulse", "rainbow", "gradient", "shimmer", "chroma", "metal", "fire",
    "fade", "blink", "glow", "outline", "thin", "pride", "lesbian", "mlm", "bi", "trans", "pan", "ace", "aro", "enby",
    "rainbowwave", "neon", "glitch", "ice", "holo", "type", "heart", "gold", "galaxy", "quake", "swing", "echo", "jeb",
    "toxic", "vampire", "ghost", "lava", "hue", "wavyoutline", "typecursor", "sparkle", "matrix", "frost", "electric",
    "sunset", "ocean", "bounce", "scramble", "extrude", "sweep",
]
PALETTE = ["white", "red", "orange", "yellow", "lime", "green", "aqua", "cyan", "blue", "purple", "magenta", "pink",
           "gold", "gray", "black", "brown"]
NEW = EFFECTS[EFFECTS.index("sparkle"):]
INDEXED = {"type", "bounce", "scramble"}
SAMPLE = "Привет, мир! Text FX 123"


def code(effect, palette, letter=0):
    letter = max(0, min(63, letter))
    return (0xD60000 | ((effect * 4) << 8) | (palette * 4) | (((letter >> 2) & 3) << 8)
            | (((letter >> 4) & 3) << 6) | (letter & 3))


def ortho(w, h):
    m = np.zeros((4, 4))
    m[0, 0], m[0, 3] = 2.0 / w, -1.0
    m[1, 1], m[1, 3] = -2.0 / h, 1.0
    m[2, 2], m[2, 3] = -2.0 / 3000.0, -1.0
    m[3, 3] = 1.0
    return m


def perspective(fov, aspect, near=0.05, far=200.0):
    f = 1.0 / math.tan(math.radians(fov) / 2)
    return np.array([[f / aspect, 0, 0, 0], [0, f, 0, 0],
                     [0, 0, (far + near) / (near - far), 2 * far * near / (near - far)], [0, 0, -1, 0]])


def mat_bytes(m):
    return np.asarray(m, np.float32).T.tobytes()


class Font:
    def __init__(self, jar):
        with zipfile.ZipFile(jar) as z:
            spec = json.loads(z.read("assets/minecraft/font/include/default.json"))
            images = {}
            for p in spec["providers"]:
                f = p["file"].split(":")[1]
                images[f] = Image.open(z.open("assets/minecraft/textures/" + f)).convert("RGBA")
        self.atlas = np.zeros((256, 256, 4), np.uint8)
        self.glyphs = {}
        cx, cy = 0, 0
        for p in spec["providers"]:
            if p.get("height", 8) != 8:
                continue
            img = np.array(images[p["file"].split(":")[1]])
            rows = p["chars"]
            ch = img.shape[0] // len(rows)
            cw = img.shape[1] // len(rows[0])
            for r, row in enumerate(rows):
                for c, char in enumerate(row):
                    if char in self.glyphs or char in ("\u0000", " "):
                        continue
                    cell = img[r * ch:(r + 1) * ch, c * cw:(c + 1) * cw]
                    cols = np.where(cell[..., 3].max(axis=0) > 0)[0]
                    if len(cols) == 0:
                        continue
                    w = int(cols.max()) + 1
                    if cx + w > 256:
                        cx, cy = 0, cy + 8
                    if cy + 8 > 256:
                        continue
                    self.atlas[cy:cy + 8, cx:cx + w] = cell[:8, :w]
                    self.glyphs[char] = (cx, cy, w)
                    cx += w

    def width(self, text):
        return sum(4 if ch == " " else self.glyphs.get(ch, (0, 0, 5))[2] + 1 for ch in text)

    def quads(self, text, x, y, colors):
        out = []
        for i, ch in enumerate(text):
            if ch == " ":
                x += 4
                continue
            gx, gy, w = self.glyphs.get(ch, self.glyphs["?"])
            rgb = colors(i)
            shadow = tuple(int(c * 0.25) for c in rgb)
            uv = (gx / 256.0, gy / 256.0, (gx + w) / 256.0, (gy + 8) / 256.0)
            out.append((x + 1, y + 1, w, uv, shadow))
            out.append((x, y, w, uv, rgb))
            x += w + 1
        return out


VS_BG = """#version 330
in vec2 p; in vec4 c; out vec4 vc;
uniform vec2 gui;
void main() { gl_Position = vec4(p.x / gui.x * 2.0 - 1.0, 1.0 - p.y / gui.y * 2.0, 0.0, 1.0); vc = c; }
"""
FS_BG = """#version 330
in vec4 vc; out vec4 o;
void main() { o = vc; }
"""


class Renderer:
    def __init__(self):
        self.ctx = moderngl.create_context(standalone=True, require=330, backend="egl")
        vanilla = glsl_check.load_vanilla(glsl_check.JAR)
        self.font = Font(glsl_check.JAR)
        vs = glsl_check.expand(glsl_check.core_source(vanilla, "rendertype_text", "vsh", True), vanilla, set())
        fs = glsl_check.expand(glsl_check.core_source(vanilla, "rendertype_text", "fsh", True), vanilla, set())
        self.prog = self.ctx.program(vertex_shader=vs, fragment_shader=fs)
        self.bg = self.ctx.program(vertex_shader=VS_BG, fragment_shader=FS_BG)
        self.tex = self.ctx.texture((256, 256), 4, self.font.atlas.tobytes())
        self.tex.filter = (moderngl.NEAREST, moderngl.NEAREST)
        self.light = self.ctx.texture((16, 16), 4, bytes([255] * 16 * 16 * 4))
        self.light.filter = (moderngl.NEAREST, moderngl.NEAREST)
        self.blocks = {}
        for i, name in enumerate(("DynamicTransforms", "Projection", "Globals", "Fog")):
            if name in self.prog:
                self.prog[name].binding = i
                self.blocks[name] = (i, None)
        self.size = (GUI_W * SCALE, GUI_H * SCALE)
        self.fbo = self.ctx.framebuffer(self.ctx.renderbuffer(self.size, 4), self.ctx.depth_renderbuffer(self.size))

    def ubo(self, name, data):
        idx, old = self.blocks[name]
        if old is not None:
            old.release()
        b = self.ctx.buffer(data)
        b.bind_to_uniform_block(idx)
        self.blocks[name] = (idx, b)

    def text_vao(self, quads, z=0.0):
        rows = []
        for x, y, w, (u0, v0, u1, v1), rgb in quads:
            col = [c / 255.0 for c in rgb] + [1.0]
            for px, py, u, v in ((x, y, u0, v0), (x, y + 8, u0, v1), (x + w, y + 8, u1, v1), (x + w, y, u1, v0)):
                rows.append(struct.pack("3f4f2f2i", px, py, z, *col, u, v, 240, 240))
        vbo = self.ctx.buffer(b"".join(rows))
        idx = []
        for q in range(len(quads)):
            b = q * 4
            idx += [b, b + 1, b + 2, b + 2, b + 3, b]
        ibo = self.ctx.buffer(np.array(idx, np.int32).tobytes())
        names = ["Position", "Color", "UV0", "UV2"]
        fmts = ["3f", "4f", "2f", "2i"]
        fmt = " ".join(f if n in self.prog else {"3f": "12x", "4f": "16x", "2f": "8x", "2i": "8x"}[f]
                       for n, f in zip(names, fmts))
        return self.ctx.vertex_array(self.prog, [(vbo, fmt, *[n for n in names if n in self.prog])], ibo)

    def background(self, boxes):
        rows = []
        for x0, y0, x1, y1, col in boxes:
            for px, py in ((x0, y0), (x0, y1), (x1, y1), (x0, y0), (x1, y1), (x1, y0)):
                rows.append(struct.pack("2f4f", px, py, *col))
        vao = self.ctx.vertex_array(self.bg, [(self.ctx.buffer(b"".join(rows)), "2f 4f", "p", "c")])
        self.bg["gui"].value = (GUI_W, GUI_H)
        vao.render()

    def frame(self, lines, tick):
        ctx = self.ctx
        self.fbo.use()
        ctx.viewport = (0, 0, *self.size)
        ctx.clear(0.36, 0.45, 0.33, 1.0)
        ctx.enable(moderngl.BLEND)
        ctx.blend_func = moderngl.SRC_ALPHA, moderngl.ONE_MINUS_SRC_ALPHA
        ctx.disable(moderngl.DEPTH_TEST)
        self.background([(0, 0, GUI_W, GUI_H, (0.0, 0.0, 0.0, 0.5))])
        self.tex.use(0)
        self.light.use(2)
        self.prog["Sampler0"].value = 0
        if "Sampler2" in self.prog:
            self.prog["Sampler2"].value = 2
        self.ubo("DynamicTransforms", mat_bytes(np.eye(4)) + struct.pack("4f", 1, 1, 1, 1)
                 + struct.pack("4f", 0, 0, 0, 0) + mat_bytes(np.eye(4)))
        self.ubo("Projection", mat_bytes(ortho(GUI_W, GUI_H)))
        game_time = ((1000 + tick) % 24000) / 24000.0
        self.ubo("Globals", struct.pack("3i4x3f4x2ff f ii8x", 0, 0, 0, 0, 0, 0, *self.size, 1.0, game_time, 0, 0))
        self.ubo("Fog", struct.pack("4f6f8x", 0, 0, 0, 0, 1e9, 2e9, 1e9, 2e9, 1e9, 1e9))
        quads = []
        for text, x, y, colors in lines:
            quads += self.font.quads(text, x, y, colors)
        self.text_vao(quads).render()
        data = self.fbo.read(components=4)
        return Image.frombytes("RGBA", self.size, data).transpose(Image.FLIP_TOP_BOTTOM).convert("RGB")

    def world(self, lines):
        ctx = self.ctx
        size = (480, 270)
        fbo = ctx.framebuffer(ctx.renderbuffer(size, 4), ctx.depth_renderbuffer(size))
        fbo.use()
        ctx.viewport = (0, 0, *size)
        ctx.clear(0.55, 0.7, 0.95, 1.0)
        self.tex.use(0)
        self.light.use(2)
        self.prog["Sampler0"].value = 0
        if "Sampler2" in self.prog:
            self.prog["Sampler2"].value = 2
        self.ubo("Globals", struct.pack("3i4x3f4x2ff f ii8x", 0, 0, 0, 0, 0, 0, *size, 1.0, 0.3, 0, 0))
        self.ubo("Fog", struct.pack("4f6f8x", 0, 0, 0, 0, 1e9, 2e9, 1e9, 2e9, 1e9, 1e9))
        ctx.enable(moderngl.BLEND)
        ctx.blend_func = moderngl.SRC_ALPHA, moderngl.ONE_MINUS_SRC_ALPHA
        model = np.eye(4)
        model[:3, :3] *= 0.025
        model[1, 1] *= -1
        model[:3, 3] = (-1.6, 0.6, -4.0)
        self.ubo("DynamicTransforms", mat_bytes(model) + struct.pack("4f", 1, 1, 1, 1)
                 + struct.pack("4f", 0, 0, 0, 0) + mat_bytes(np.eye(4)))
        self.ubo("Projection", mat_bytes(perspective(70, size[0] / size[1])))
        quads = []
        for text, x, y, colors in lines:
            quads += self.font.quads(text, x, y, colors)
        self.text_vao(quads).render()
        img = Image.frombytes("RGBA", size, fbo.read(components=4)).transpose(Image.FLIP_TOP_BOTTOM).convert("RGB")
        fbo.release()
        return img


def lines_for(effect, palette2):
    e = EFFECTS.index(effect)
    p2 = PALETTE.index(palette2)
    indexed = effect in INDEXED
    return [
        (SAMPLE, 4, 6, lambda i: rgb(code(e, 0, i if indexed else 0))),
        (effect + " : " + palette2, 4, 24, lambda i: rgb(code(e, p2, i if indexed else 0))),
    ]


def rgb(c):
    return ((c >> 16) & 255, (c >> 8) & 255, c & 255)


def label(img, text):
    d = ImageDraw.Draw(img)
    d.text((img.width - 7 * len(text) - 6, 4), text, fill=(255, 255, 255))
    return img


def main(argv):
    os.makedirs(OUT, exist_ok=True)
    r = Renderer()
    if "--world" in argv:
        lines = []
        for k, name in enumerate(("rainbow", "wave", "matrix", "sunset")):
            e = EFFECTS.index(name)
            lines.append((name + " Мир", 0, k * 10, lambda i, e=e: rgb(code(e, 0))))
        r.world(lines).save(os.path.join(OUT, "world_plain.png"))
        print("world_plain.png")
        return 0
    only = None
    if "--only" in argv:
        only = argv[argv.index("--only") + 1].split(",")
    names = [n for n in EFFECTS if n != "typecursor" and (only is None or n in only)]
    second = {n: PALETTE[(EFFECTS.index(n) % 15) + 1] for n in EFFECTS}
    sheet_cols = 4
    sheet = []
    for name in names:
        long_cycle = name in INDEXED
        step = 3 if long_cycle else 2
        count = 50 if long_cycle else 30
        frames = [r.frame(lines_for(name, second[name]), k * step) for k in range(count)]
        frames[0].save(os.path.join(OUT, f"fx_{name}.gif"), save_all=True, append_images=frames[1:],
                       duration=step * 50, loop=0)
        picks = [frames[int(k * (count - 1) / 5)] for k in range(6)]
        strip = Image.new("RGB", (frames[0].width, frames[0].height * 6))
        for k, f in enumerate(picks):
            strip.paste(f, (0, k * f.height))
        strip.save(os.path.join(OUT, f"strip_{name}.png"))
        row = [frames[int(k * (count - 1) / (sheet_cols - 1))].copy() for k in range(sheet_cols)]
        label(row[0], name)
        sheet.append(row)
        print(name)
    if only is None:
        w, h = sheet[0][0].size
        img = Image.new("RGB", (w * sheet_cols, h * len(sheet)))
        for y, row in enumerate(sheet):
            for x, f in enumerate(row):
                img.paste(f, (x * w, y * h))
        img.save(os.path.join(OUT, "sheet.png"))
        new_rows = [row for name, row in zip(names, sheet) if name in NEW]
        img = Image.new("RGB", (w * sheet_cols, h * len(new_rows)))
        for y, row in enumerate(new_rows):
            for x, f in enumerate(row):
                img.paste(f, (x * w, y * h))
        img.save(os.path.join(OUT, "sheet_new.png"))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
