#!/usr/bin/env python3
"""tabshader - HUD previews rendered with the REAL shaders (moderngl, EGL; Mesa llvmpipe is enough).

    python3 -I tabshader/tools/preview.py              # hud.png, hud_vanilla.png, compare.png, hud.gif, crops
    python3 -I tabshader/tools/preview.py --scale 2    # another GUI scale

The HUD is laid out like the 1.21.11 client code does it (PlayerTabOverlay.render, Gui.displayScoreboardSidebar,
BossHealthOverlay, Gui.renderOverlayMessage, Gui.renderTitle): the same fills with the same vanilla colours
(0x80000000 tab blocks, 0x20FFFFFF tab rows, 0x66000000 / 0x4C000000 sidebar), the same sprites (boss bar 182x5,
ping icons, hotbar) and the same glyph quads (corners TL, BL, BR, TR, shadow first). Fills go through gui.vsh/fsh,
sprites through position_tex_color.vsh/fsh, text through rendertype_text.vsh/fsh, all taken from tabshader/pack
(or from the client jar for the vanilla picture). The world behind is a simple block landscape made from vanilla
block textures. GameTime advances 1/24000 per tick. The player name in the subtitle carries a textshader code
(wave, pink: #D6042C), so the picture also shows both hooks of the merged rendertype_text working together.
Output: tabshader/preview/
"""
import json
import math
import os
import struct
import sys
import zipfile

import moderngl
import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import glsl_check  # noqa: E402

ROOT = glsl_check.ROOT
OUT = os.path.join(ROOT, "preview")
SPRITES = os.path.join(ROOT, "pack", "assets", "minecraft", "textures", "gui", "sprites")
MARK_TEXEL = (231, 7, 77, 255)
FA = 512
PINK = (255, 143, 189)
ROSE = (255, 122, 170)
CRIMSON = (255, 61, 110)
SOFT = (255, 214, 230)
WHITE = (255, 255, 255)
TS_WAVE_PINK = (214, 4, 44)


BG_VS = """#version 330
in vec2 p; in vec2 uv; out vec2 v;
void main() { gl_Position = vec4(p, 0.0, 1.0); v = uv; }
"""
BG_FS = """#version 330
uniform sampler2D tex; in vec2 v; out vec4 o;
void main() { o = vec4(texture(tex, v).rgb, 1.0); }
"""


_CTX = []


def context():
    if not _CTX:
        _CTX.append(moderngl.create_context(standalone=True, require=330, backend="egl"))
    return _CTX[0]


def gradient(a, b, n):
    if n <= 1:
        return [a]
    return [tuple(int(a[k] + (b[k] - a[k]) * i / (n - 1)) for k in range(3)) for i in range(n)]


class Font:
    def __init__(self, z):
        spec = json.loads(z.read("assets/minecraft/font/include/default.json"))
        self.atlas = np.zeros((FA, FA, 4), np.uint8)
        self.glyphs = {}
        cx, cy, row_h = 0, 0, 8
        for p in spec["providers"]:
            if p.get("type") != "bitmap":
                continue
            gh = p.get("height", 8)
            ascent = p.get("ascent", 7)
            img = np.array(Image.open(z.open("assets/minecraft/textures/" + p["file"].split(":")[1])).convert("RGBA"))
            rows = p["chars"]
            ch = img.shape[0] // len(rows)
            cw = img.shape[1] // len(rows[0])
            if ch != gh:
                continue
            for r, row in enumerate(rows):
                for c, char in enumerate(row):
                    if char in self.glyphs or char in ("\u0000", " "):
                        continue
                    cell = img[r * ch:(r + 1) * ch, c * cw:(c + 1) * cw]
                    cols = np.where(cell[..., 3].max(axis=0) > 0)[0]
                    if len(cols) == 0:
                        continue
                    w = int(cols.max()) + 1
                    if cx + w > FA or gh > row_h:
                        cx, cy, row_h = 0, cy + row_h, gh
                    if cy + gh > FA - 16:
                        continue
                    self.atlas[cy:cy + gh, cx:cx + w] = cell[:gh, :w]
                    self.glyphs[char] = (cx, cy, w, gh, ascent)
                    cx += w
        self.atlas[FA - 8:FA, FA - 8:FA] = MARK_TEXEL
        self.glyphs["\ue000"] = (FA - 8, FA - 8, 8, 8, 7)

    def advance(self, ch, bold=False):
        if ch == " ":
            return 4 + (1 if bold else 0)
        if ch == "":
            return -9
        return self.glyphs.get(ch, self.glyphs["?"])[2] + 1 + (1 if bold else 0)

    def width(self, spans):
        return sum(self.advance(ch, b) for text, _, b in spans for ch in text)


def spans_width(font, spans):
    return font.width([(t, c, b) for t, c, b in spans if t not in ("",)])


class Hud:
    def __init__(self, own, scale, size, sprite_shader=None, everywhere=None):
        self.own = own
        self.everywhere = everywhere
        self.scale = scale
        self.size = size
        self.gw = size[0] / scale
        self.gh = size[1] / scale
        self.gwi = math.ceil(self.gw - 1e-3)
        self.ghi = math.ceil(self.gh - 1e-3)
        z = zipfile.ZipFile(glsl_check.JAR)
        self.zip = z
        self.font = Font(z)
        self.ctx = context()
        vanilla = glsl_check.load_vanilla(glsl_check.JAR)
        self.progs = {}
        for name in ("gui", "position_tex_color", "rendertype_text"):
            mine = own if name != "position_tex_color" or sprite_shader is None else sprite_shader
            vs, fs = glsl_check.program(vanilla, name, mine)
            self.progs[name] = self.ctx.program(vertex_shader=vs, fragment_shader=fs)
        self.blocks = {}
        for prog in self.progs.values():
            for i, name in enumerate(("DynamicTransforms", "Projection", "Globals", "Fog")):
                if name in prog:
                    prog[name].binding = i
        self.font_tex = self.ctx.texture((FA, FA), 4, self.font.atlas.tobytes())
        self.font_tex.filter = (moderngl.NEAREST, moderngl.NEAREST)
        self.light = self.ctx.texture((16, 16), 4, bytes([255] * 16 * 16 * 4))
        self.light.filter = (moderngl.NEAREST, moderngl.NEAREST)
        self.build_atlas()
        self.bg = self.background()
        self.bg_tex = self.ctx.texture(self.size, 3, self.bg.tobytes())
        self.bg_prog = self.ctx.program(vertex_shader=BG_VS, fragment_shader=BG_FS)
        quad = np.array([-1, -1, 0, 1, 1, -1, 1, 1, -1, 1, 0, 0, 1, 1, 1, 0], np.float32)
        self.bg_vao = self.ctx.vertex_array(self.bg_prog, [(self.ctx.buffer(quad.tobytes()), "2f 2f", "p", "uv")])
        self.fbo = self.ctx.framebuffer(self.ctx.renderbuffer(size, 4))
        self.ops = []

    def sprite_image(self, rel):
        own = os.path.join(SPRITES, rel)
        if self.own and os.path.exists(own):
            return Image.open(own).convert("RGBA")
        return Image.open(self.zip.open("assets/minecraft/textures/gui/sprites/" + rel)).convert("RGBA")

    def build_atlas(self):
        names = []
        for c in ("pink", "blue", "red", "green", "yellow", "purple", "white"):
            names += [f"boss_bar/{c}_background.png", f"boss_bar/{c}_progress.png"]
        for n in (6, 10, 12, 20):
            names += [f"boss_bar/notched_{n}_background.png", f"boss_bar/notched_{n}_progress.png"]
        names += ["hud/hotbar.png", "hud/hotbar_selection.png", "hud/crosshair.png", "icon/ping_5.png",
                  "icon/ping_4.png", "icon/ping_3.png", "hud/heart/full.png", "hud/heart/container.png",
                  "hud/food_full.png", "hud/food_empty.png", "hud/experience_bar_background.png",
                  "hud/experience_bar_progress.png"]
        atlas = Image.new("RGBA", (512, 256), (0, 0, 0, 0))
        self.sprites = {}
        x, y, row_h = 0, 0, 0
        for n in names:
            img = self.sprite_image(n)
            if x + img.width > 512:
                x, y, row_h = 0, y + row_h + 1, 0
            atlas.paste(img, (x, y))
            self.sprites[n] = (x, y, img.width, img.height)
            x += img.width + 1
            row_h = max(row_h, img.height)
        for k, skin in enumerate(("steve", "alex")):
            s = Image.open(self.zip.open(f"assets/minecraft/textures/entity/player/wide/{skin}.png")).convert("RGBA")
            face = s.crop((8, 8, 16, 16))
            hat = s.crop((40, 8, 48, 16))
            face.alpha_composite(hat)
            atlas.paste(face, (500 - k * 9, 240))
            self.sprites[f"face/{skin}"] = (500 - k * 9, 240, 8, 8)
        self.atlas_size = atlas.size
        self.atlas = self.ctx.texture(atlas.size, 4, atlas.tobytes())
        self.atlas.filter = (moderngl.NEAREST, moderngl.NEAREST)

    def background(self):
        w, h = self.size
        tex = lambda p: Image.open(self.zip.open("assets/minecraft/textures/block/" + p)).convert("RGBA")
        img = Image.new("RGBA", (w, h))
        sky = np.zeros((h, w, 4), np.uint8)
        for yy in range(h):
            t = yy / h
            sky[yy, :] = (int(120 + 60 * t), int(167 + 40 * t), 255, 255)
        img = Image.fromarray(sky, "RGBA")
        tile = max(24, int(h / 9))
        grass_top = tex("grass_block_side.png")
        over = np.array(tex("grass_block_side_overlay.png")).astype(float)
        over[..., :3] *= np.array([0.49, 0.74, 0.31])
        grass = grass_top.copy()
        grass.alpha_composite(Image.fromarray(over.astype(np.uint8), "RGBA"))
        dirt, stone, log, planks = tex("dirt.png"), tex("stone.png"), tex("oak_log.png"), tex("oak_planks.png")
        leaves = np.array(tex("oak_leaves.png")).astype(float)
        leaves[..., :3] *= np.array([0.36, 0.62, 0.22])
        leaves = Image.fromarray(leaves.astype(np.uint8), "RGBA")
        rs = np.random.RandomState(7)
        cols = w // tile + 2
        height = [5 + int(2.2 * math.sin(i * 0.45) + 1.2 * math.sin(i * 1.3 + 1)) for i in range(cols)]
        for cx in range(cols):
            top = h - height[cx] * tile
            for k in range(height[cx] + 1):
                yy = top + k * tile
                src = grass if k == 0 else (dirt if k < 3 else stone)
                img.alpha_composite(src.resize((tile, tile), Image.NEAREST), (cx * tile, yy))
        for tx in (3, 11, 17):
            if tx >= cols:
                continue
            base = h - height[tx] * tile
            for k in range(1, 4):
                img.alpha_composite(log.resize((tile, tile), Image.NEAREST), (tx * tile, base - k * tile))
            for dx in range(-2, 3):
                for dy in range(4, 7):
                    if abs(dx) == 2 and dy == 6:
                        continue
                    img.alpha_composite(leaves.resize((tile, tile), Image.NEAREST),
                                        ((tx + dx) * tile, base - dy * tile))
        hx = 7
        if hx + 3 < cols:
            base = h - height[hx] * tile
            for k in range(1, 3):
                for dx in range(3):
                    img.alpha_composite(planks.resize((tile, tile), Image.NEAREST), ((hx + dx) * tile, base - k * tile))
        shade = Image.new("RGBA", (w, h), (0, 0, 0, 30))
        img.alpha_composite(shade)
        return img.convert("RGB")

    def fill(self, x0, y0, x1, y1, argb):
        a, r, g, b = (argb >> 24) & 255, (argb >> 16) & 255, (argb >> 8) & 255, argb & 255
        self.ops.append(("gui", (x0, y0, x1, y1, (r, g, b, a))))

    def gradient_fill(self, x0, y0, x1, y1, top, bottom):
        def rgba(argb):
            return ((argb >> 16) & 255, (argb >> 8) & 255, argb & 255, (argb >> 24) & 255)
        self.ops.append(("gui", (x0, y0, x1, y1, (rgba(top), rgba(bottom), rgba(bottom), rgba(top)))))

    def backdrop(self, x, y, w, scale=1.0, ox=0.0, oy=0.0):
        if self.everywhere is None:
            return
        a = self.everywhere << 24
        self.fill(ox + (x - 2) * scale, oy + (y - 2) * scale, ox + (x + w + 2) * scale, oy + (y + 9 + 2) * scale, a)

    def blit(self, name, x, y, w=None, h=None, u_w=None):
        sx, sy, sw, sh = self.sprites[name]
        w = sw if w is None else w
        h = sh if h is None else h
        uw = sw if u_w is None else u_w
        aw, ah = self.atlas_size
        self.ops.append(("tex", (x, y, x + w, y + h, sx / aw, sy / ah, (sx + uw) / aw, (sy + sh) / ah)))

    def text(self, spans, x, y, scale=1.0, ox=0.0, oy=0.0, shadow=True, alpha=255):
        quads = []
        pen = x
        for text, colour, bold in spans:
            cols = colour if isinstance(colour, list) else [colour] * len(text)
            for i, ch in enumerate(text):
                adv = self.font.advance(ch, bold)
                if ch == " " or ch == "":
                    pen += adv
                    continue
                gx, gy, gw, gh, asc = self.font.glyphs.get(ch, self.font.glyphs["?"])
                rgb = cols[min(i, len(cols) - 1)]
                uv = (gx / FA, gy / FA, (gx + gw) / FA, (gy + gh) / FA)
                marker = ch == ""
                passes = [(0, rgb)] if marker or not shadow else [(1, tuple(int(c * 0.25) for c in rgb)), (0, rgb)]
                for off, col in passes:
                    for bo in ((0, 1) if bold and not marker else (0,)):
                        px0 = ox + (pen + off + bo) * scale
                        py0 = oy + (y + off + 7 - asc) * scale
                        quads.append((px0, py0, px0 + gw * scale, py0 + gh * scale, uv, col + (alpha,)))
                pen += adv
        self.ops.append(("text", quads))

    def marked(self, kind, spans):
        w = spans_width(self.font, spans)
        mark = (231, (kind << 4) | (w >> 8), w & 255)
        return [("", mark, False), ("", mark, False)] + spans, w

    def ubos(self, tick):
        def put(name, data):
            idx = ("DynamicTransforms", "Projection", "Globals", "Fog").index(name)
            old = self.blocks.get(name)
            if old is not None:
                old.release()
            b = self.ctx.buffer(data)
            b.bind_to_uniform_block(idx)
            self.blocks[name] = b

        mv = np.eye(4)
        mv[2, 3] = -11000.0
        proj = np.zeros((4, 4))
        proj[0, 0], proj[0, 3] = 2.0 / self.gw, -1.0
        proj[1, 1], proj[1, 3] = -2.0 / self.gh, 1.0
        proj[2, 2], proj[2, 3] = -2.0 / 10000.0, -11000.0 / 10000.0 - 1000.0 / 10000.0
        proj[3, 3] = 1.0
        mat = lambda m: np.asarray(m, np.float32).T.tobytes()
        put("DynamicTransforms", mat(mv) + struct.pack("4f", 1, 1, 1, 1) + struct.pack("4f", 0, 0, 0, 0) + mat(np.eye(4)))
        put("Projection", mat(proj))
        gt = ((6000 + tick) % 24000) / 24000.0
        put("Globals", struct.pack("3i4x3f4x2ff f ii8x", 0, 0, 0, 0, 0, 0, *self.size, 1.0, gt, 0, 0))
        put("Fog", struct.pack("4f6f8x", 0, 0, 0, 0, 1e9, 2e9, 1e9, 2e9, 1e9, 1e9))

    def draw(self, kind, items):
        ctx = self.ctx
        rows, idx = [], []
        if kind == "gui":
            prog = self.progs["gui"]
            for q, (x0, y0, x1, y1, col) in enumerate(items):
                cols = col if isinstance(col[0], tuple) else (col,) * 4
                for (px, py), cc in zip(((x0, y0), (x0, y1), (x1, y1), (x1, y0)), cols):
                    rows.append(struct.pack("3f4f", px, py, 0.0, *[v / 255.0 for v in cc]))
                idx += [q * 4, q * 4 + 1, q * 4 + 2, q * 4 + 2, q * 4 + 3, q * 4]
            fmt, names = "3f 4f", ["Position", "Color"]
        elif kind == "tex":
            prog = self.progs["position_tex_color"]
            self.atlas.use(0)
            prog["Sampler0"].value = 0
            for q, (x0, y0, x1, y1, u0, v0, u1, v1) in enumerate(items):
                for px, py, u, v in ((x0, y0, u0, v0), (x0, y1, u0, v1), (x1, y1, u1, v1), (x1, y0, u1, v0)):
                    rows.append(struct.pack("3f2f4f", px, py, 0.0, u, v, 1, 1, 1, 1))
                idx += [q * 4, q * 4 + 1, q * 4 + 2, q * 4 + 2, q * 4 + 3, q * 4]
            fmt, names = "3f 2f 4f", ["Position", "UV0", "Color"]
        else:
            prog = self.progs["rendertype_text"]
            self.font_tex.use(0)
            self.light.use(2)
            prog["Sampler0"].value = 0
            if "Sampler2" in prog:
                prog["Sampler2"].value = 2
            for q, (x0, y0, x1, y1, (u0, v0, u1, v1), col) in enumerate(items):
                c = [v / 255.0 for v in col]
                for px, py, u, v in ((x0, y0, u0, v0), (x0, y1, u0, v1), (x1, y1, u1, v1), (x1, y0, u1, v0)):
                    rows.append(struct.pack("3f4f2f2i", px, py, 0.0, *c, u, v, 240, 240))
                idx += [q * 4, q * 4 + 1, q * 4 + 2, q * 4 + 2, q * 4 + 3, q * 4]
            fmt, names = "3f 4f 2f 2i", ["Position", "Color", "UV0", "UV2"]
        if not rows:
            return
        skip = {"3f": "12x", "4f": "16x", "2f": "8x", "2i": "8x"}
        fmt = " ".join(f if n in prog else skip[f] for n, f in zip(names, fmt.split()))
        vao = ctx.vertex_array(prog, [(ctx.buffer(b"".join(rows)), fmt, *[n for n in names if n in prog])],
                               ctx.buffer(np.array(idx, np.int32).tobytes()))
        vao.render()
        vao.release()

    def render(self, tick):
        ctx = self.ctx
        self.fbo.use()
        ctx.viewport = (0, 0, *self.size)
        ctx.disable(moderngl.BLEND)
        ctx.disable(moderngl.DEPTH_TEST)
        self.bg_tex.use(0)
        self.bg_prog["tex"].value = 0
        self.bg_vao.render(moderngl.TRIANGLE_STRIP)
        ctx.enable(moderngl.BLEND)
        ctx.blend_func = moderngl.SRC_ALPHA, moderngl.ONE_MINUS_SRC_ALPHA, moderngl.ONE, moderngl.ONE_MINUS_SRC_ALPHA
        self.ubos(tick)
        for kind, payload in self.batched():
            self.draw(kind, payload)
        data = self.fbo.read(components=4)
        return Image.frombytes("RGBA", self.size, data).transpose(Image.FLIP_TOP_BOTTOM).convert("RGB")

    def batched(self):
        out = []
        for kind, item in self.ops:
            items = item if kind == "text" else [item]
            if out and out[-1][0] == kind:
                out[-1][1].extend(items)
            else:
                out.append((kind, list(items)))
        return out


class Scene:
    def __init__(self, hud):
        self.h = hud

    def hotbar(self):
        h = self.h
        gw, gh = h.gwi, h.ghi
        x = gw // 2 - 91
        h.blit("hud/hotbar.png", x, gh - 22)
        h.blit("hud/hotbar_selection.png", x - 1 + 2 * 20, gh - 23)
        h.blit("hud/crosshair.png", (gw - 15) // 2, (gh - 15) // 2)
        h.blit("hud/experience_bar_background.png", x, gh - 29)
        h.blit("hud/experience_bar_progress.png", x, gh - 29, 120, 5, 120)
        for i in range(10):
            h.blit("hud/heart/container.png", x + i * 8, gh - 39)
            h.blit("hud/heart/full.png", x + i * 8, gh - 39)
            h.blit("hud/food_empty.png", x + 182 - 9 - i * 8, gh - 39)
            h.blit("hud/food_full.png", x + 182 - 9 - i * 8, gh - 39)

    def tab(self, header, players, footer):
        h = self.h
        f = h.font
        w = h.gwi
        names = [p[0] for p in players]
        name_w = max(f.width(n) for n in names)
        rows = len(players)
        cols = 1
        while rows > 20:
            cols += 1
            rows = (len(players) + cols - 1) // cols
        col_w = min(cols * (9 + name_w + 0 + 13), w - 50) // cols
        left = w // 2 - (col_w * cols + (cols - 1) * 5) // 2
        y = 10
        width = col_w * cols + (cols - 1) * 5
        for line in header + footer:
            width = max(width, f.width(line))
        if header:
            h.fill(w // 2 - width // 2 - 1, y - 1, w // 2 + width // 2 + 1, y + len(header) * 9, 0x80000000)
        for line in header:
            lw = f.width(line)
            h.text(line, w // 2 - lw // 2, y)
            y += 9
        if header:
            y += 1
        h.fill(w // 2 - width // 2 - 1, y - 1, w // 2 + width // 2 + 1, y + rows * 9, 0x80000000)
        for i, (name, skin, ping) in enumerate(players):
            c, r = i // rows, i % rows
            x = left + c * col_w + c * 5
            yy = y + r * 9
            h.fill(x, yy, x + col_w, yy + 8, 0x20FFFFFF if h.everywhere is None else h.everywhere << 24)
            h.blit(f"face/{skin}", x, yy)
            h.text(name, x + 9, yy)
            h.blit(f"icon/ping_{ping}.png", x + col_w - 11, yy, 10, 8)
        if not footer:
            return
        y += rows * 9 + 1
        h.fill(w // 2 - width // 2 - 1, y - 1, w // 2 + width // 2 + 1, y + len(footer) * 9, 0x80000000)
        for line in footer:
            lw = f.width(line)
            h.text(line, w // 2 - lw // 2, y)
            y += 9

    def sidebar(self, title, lines):
        h = self.h
        f = h.font
        tw = f.width(title)
        mw = max([tw] + [f.width(l) for l in lines])
        n = len(lines)
        bottom = h.ghi // 2 + n * 9 // 3
        x0 = h.gwi - mw - 3
        x1 = h.gwi - 3 + 2
        top = bottom - n * 9
        h.fill(x0 - 2, top - 9 - 1, x1, top - 1, 0x66000000 if h.everywhere is None else h.everywhere << 24)
        h.fill(x0 - 2, top - 1, x1, bottom, 0x4C000000 if h.everywhere is None else h.everywhere << 24)
        h.text(title, x0 + mw // 2 - tw // 2, top - 9, shadow=False)
        for i, line in enumerate(lines):
            h.text(line, x0, bottom - (n - i) * 9, shadow=False)

    def bossbar(self, name, progress, colour="pink", notches=None):
        h = self.h
        f = h.font
        x = h.gwi // 2 - 91
        y = 12
        h.blit(f"boss_bar/{colour}_background.png", x, y)
        if notches:
            h.blit(f"boss_bar/notched_{notches}_background.png", x, y)
        pw = int(progress * 182)
        if pw > 0:
            h.blit(f"boss_bar/{colour}_progress.png", x, y, pw, 5, pw)
            if notches:
                h.blit(f"boss_bar/notched_{notches}_progress.png", x, y, pw, 5, pw)
        nw = f.width(name)
        h.text(name, h.gwi // 2 - nw // 2, y - 9)

    def actionbar(self, spans):
        h = self.h
        spans, w = h.marked(1, spans) if h.own else (spans, h.font.width(spans))
        h.backdrop(-(w // 2), -4, w, ox=h.gwi // 2, oy=h.ghi - 68)
        h.text(spans, -(w // 2), -4, ox=h.gwi // 2, oy=h.ghi - 68)

    def title(self, title, subtitle):
        h = self.h
        t, tw = h.marked(2, title) if h.own else (title, h.font.width(title))
        h.backdrop(-(tw // 2), -10, tw, scale=4, ox=h.gwi // 2, oy=h.ghi // 2)
        h.text(t, -(tw // 2), -10, scale=4, ox=h.gwi // 2, oy=h.ghi // 2)
        s, sw = h.marked(3, subtitle) if h.own else (subtitle, h.font.width(subtitle))
        h.backdrop(-(sw // 2), 5, sw, scale=2, ox=h.gwi // 2, oy=h.ghi // 2)
        h.text(s, -(sw // 2), 5, scale=2, ox=h.gwi // 2, oy=h.ghi // 2)


def g(text, a=PINK, b=CRIMSON, bold=False):
    return (text, gradient(a, b, len(text)), bold)


def edge_cases(hud):
    s = Scene(hud)
    hud.ops = []
    s.hotbar()
    gw, gh = hud.gwi, hud.ghi
    hud.gradient_fill(20, 20, 120, 60, 0xC0000000, 0x00000000)
    hud.gradient_fill(gw - 121, 70, gw - 1, 110, 0x66000000, 0x00000000)
    hud.gradient_fill(gw // 2 - 60, 9, gw // 2 + 60, 46, 0x80000000, 0x00000000)
    hud.gradient_fill(20, 70, 120, 110, 0x00000000, 0x80000000)
    hud.fill(140, 20, 200, 28, 0x20FFFFFF)
    hud.fill(140, 40, 200, 80, 0x4C000000)
    hud.fill(gw // 2 - 40, gh - 120, gw // 2 + 40, gh - 100, 0x80000000)
    return hud


def tab_variants(hud, variant):
    s = Scene(hud)
    hud.ops = []
    s.hotbar()
    s.bossbar([g("Онлайн 4/100", SOFT, PINK, False), ("  -  ", ROSE, False), ("14:32", WHITE, False)], 0.62)
    players = [([("tearsmilk", WHITE, False)], "steve", 5), ([("Kotik_228", WHITE, False)], "alex", 5),
               ([("Neko", WHITE, False)], "steve", 4)]
    blank = [[(" ", WHITE, False)]]
    if variant == "off":
        s.tab(blank, players, blank)
    elif variant == "nofooter":
        s.tab([[g("Amazake", PINK, CRIMSON, True)]], players, [])
    else:
        s.tab([], players, [])
    return hud


def build(hud, tick=0, parts=("hotbar", "boss", "action", "title", "board", "tab")):
    s = Scene(hud)
    hud.ops = []
    if "hotbar" in parts:
        s.hotbar()
    if "boss" in parts:
        s.bossbar([g("Онлайн 4/100", SOFT, PINK, False), ("  -  ", ROSE, False), ("14:32", WHITE, False)], 0.62)
    if "action" in parts:
        s.actionbar([("Тест актионбара: ", SOFT, False), g("розово-кримсон", PINK, CRIMSON)])
    if "title" in parts:
        s.title([g("Amazake", PINK, CRIMSON, True)], [("Добро пожаловать, ", SOFT, False), ("tearsmilk", TS_WAVE_PINK, False)])
    if "board" in parts:
        s.sidebar([g("Amazake", PINK, CRIMSON, True)],
                  [[("Игрок: ", ROSE, False), ("tearsmilk", WHITE, False)],
                   [("Онлайн: ", ROSE, False), ("4", WHITE, False)],
                   [("Пинг: ", ROSE, False), ("24 мс", WHITE, False)],
                   [("Мир: ", ROSE, False), ("world", WHITE, False)],
                   [("XYZ: ", ROSE, False), ("128 64 -305", WHITE, False)],
                   [("Время: ", ROSE, False), ("14:32", WHITE, False)]])
    if "tab" in parts:
        header = [[g("Amazake", PINK, CRIMSON, True)],
                  [("Онлайн: ", ROSE, False), ("4", WHITE, False), ("   Пинг: ", ROSE, False), ("24 мс", WHITE, False)]]
        footer = [[("TPS: ", ROSE, False), ("20.0", WHITE, False), ("   Время: ", ROSE, False),
                   ("14:32", WHITE, False)]]
        players = [([("tearsmilk", WHITE, False)], "steve", 5), ([("Kotik_228", WHITE, False)], "alex", 5),
                   ([("Neko", WHITE, False)], "steve", 4), ([("SkT1", WHITE, False)], "alex", 3)]
        s.tab(header, players, footer)
    return hud


def crop(img, scale, box):
    x0, y0, x1, y1 = (int(v * scale) for v in box)
    return img.crop((x0, y0, x1, y1))


SCENES = {
    "tab": ("hotbar", "boss", "board", "tab"),
    "title": ("hotbar", "boss", "action", "title", "board"),
}


def main(argv):
    os.makedirs(OUT, exist_ok=True)
    scale = int(argv[argv.index("--scale") + 1]) if "--scale" in argv else 3
    size = (480 * scale, 270 * scale)
    if "--size" in argv:
        size = tuple(int(v) for v in argv[argv.index("--size") + 1].split("x"))
    suffix = "" if scale == 3 and "--size" not in argv else f"_s{scale}_{size[0]}x{size[1]}"
    ours = Hud(True, scale, size)
    van = Hud(False, scale, size)
    shots = {}
    for name, parts in SCENES.items():
        a = build(ours, 0, parts).render(0)
        b = build(van, 0, parts).render(0)
        shots[name] = a
        a.save(os.path.join(OUT, f"hud_{name}{suffix}.png"))
        cmp_img = Image.new("RGB", (a.width * 2 + 8, a.height), (20, 20, 20))
        cmp_img.paste(b, (0, 0))
        cmp_img.paste(a, (a.width + 8, 0))
        cmp_img.save(os.path.join(OUT, f"compare_{name}{suffix}.png"))
    if suffix:
        print("saved", suffix)
        return 0
    gw, gh = ours.gwi, ours.ghi
    a = shots["tab"]
    crop(a, scale, (gw / 2 - 90, 0, gw / 2 + 90, 70)).resize(
        (int(180 * scale * 4 / 3), int(70 * scale * 4 / 3)), Image.NEAREST).save(os.path.join(OUT, "tab_zoom.png"))
    crop(a, scale, (gw - 110, gh / 2 - 50, gw, gh / 2 + 30)).resize(
        (int(110 * scale * 2), int(80 * scale * 2)), Image.NEAREST).save(os.path.join(OUT, "sidebar_zoom.png"))
    a = shots["title"]
    crop(a, scale, (gw / 2 - 100, 0, gw / 2 + 100, 22)).resize(
        (int(200 * scale * 2), int(22 * scale * 2)), Image.NEAREST).save(os.path.join(OUT, "bossbar_zoom.png"))
    crop(a, scale, (gw / 2 - 90, gh - 80, gw / 2 + 90, gh - 55)).resize(
        (int(180 * scale * 2), int(25 * scale * 2)), Image.NEAREST).save(os.path.join(OUT, "actionbar_zoom.png"))
    crop(a, scale, (gw / 2 - 120, gh / 2 - 52, gw / 2 + 120, gh / 2 + 36)).save(os.path.join(OUT, "title_crop.png"))
    for name, parts in SCENES.items():
        frames = []
        for k in range(40):
            t = k * 6
            frames.append(build(ours, t, parts).render(t).resize((size[0] * 2 // 3, size[1] * 2 // 3), Image.LANCZOS))
        frames[0].save(os.path.join(OUT, f"hud_{name}.gif"), save_all=True, append_images=frames[1:], duration=150,
                       loop=0)
        strip = Image.new("RGB", (frames[0].width, frames[0].height * 4))
        for k in range(4):
            strip.paste(frames[k * 10], (0, k * frames[0].height))
        strip.save(os.path.join(OUT, f"strip_{name}.png"))
    for opacity in (127, 102):
        ev = Hud(True, scale, size, everywhere=opacity)
        evv = Hud(False, scale, size, everywhere=opacity)
        for name, parts in SCENES.items():
            a = build(ev, 0, parts).render(0)
            b = build(evv, 0, parts).render(0)
            cmp_img = Image.new("RGB", (a.width * 2 + 8, a.height), (20, 20, 20))
            cmp_img.paste(b, (0, 0))
            cmp_img.paste(a, (a.width + 8, 0))
            cmp_img.save(os.path.join(OUT, f"everywhere{opacity}_{name}.png"))
    a = edge_cases(ours).render(0)
    b = edge_cases(van).render(0)
    diff = np.abs(np.asarray(a, np.int16) - np.asarray(b, np.int16)).max()
    cmp_img = Image.new("RGB", (a.width * 2 + 8, a.height), (20, 20, 20))
    cmp_img.paste(b, (0, 0))
    cmp_img.paste(a, (a.width + 8, 0))
    cmp_img.save(os.path.join(OUT, "edge_cases.png"))
    print("edge cases max channel difference vs vanilla:", int(diff))
    tiles = []
    for variant in ("off", "nofooter", "bare"):
        img = tab_variants(ours, variant).render(0)
        tiles.append(crop(img, scale, (gw / 2 - 70, 0, gw / 2 + 70, 60)))
    strip = Image.new("RGB", (tiles[0].width * 3 + 16, tiles[0].height), (20, 20, 20))
    for k, t in enumerate(tiles):
        strip.paste(t, (k * (t.width + 8), 0))
    strip.save(os.path.join(OUT, "tab_variants.png"))
    fb = Hud(True, scale, size, sprite_shader=False)
    a = build(fb, 0, ("hotbar", "boss")).render(0)
    crop(a, scale, (gw / 2 - 100, 0, gw / 2 + 100, 22)).resize(
        (int(200 * scale * 2), int(22 * scale * 2)), Image.NEAREST).save(os.path.join(OUT, "bossbar_fallback.png"))
    print("saved hud_*.png compare_*.png hud_*.gif strip_*.png and zoomed crops")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
