"""Art of the profile-selection screen (rezusmenu "profiles"): pixel textures pf_* for the font rezusmenu:ui.

    python3 tools/profiles_art.py            # writes pack/assets/rezusmenu/textures/ui/pf_*.png and font/ui.json
    python3 tools/profiles_art.py --check    # + runs the skill's shading check on every texture

Same art kit as the RPG inventory menu (panel_*, back_*, button_hover...): dark walnut with gold trim, emerald and
cyan jewels, turquoise wells, green cloth banners, iron lanterns, a dark castle hall with a stepped vignette. The kit
primitives below (ramps, Pen, grain, gem, well, plate, section, button, wall, lantern...) are the ones the RPG menu
art was drawn with, so both screens share one palette and one light (top-left).

Uses the minecraft-nexo-gui skill scripts (shading.ramp5, lettering.volume, props.banner) - set SKILL_SCRIPTS if they
live elsewhere. Canvas = 704 x 400 px (the same canvas as the RPG menu). Every texture is a multiple of 16 px, <= 256 px
per side (font atlas), drawn 1:1; overlays keep their content in the top-left corner. The glyph order of font/ui.json
is PanelMath.ORDER: the 28 glyphs of the RPG menu first (left untouched), then PF_ORDER below.
"""
import json
import math
import os
import random
import sys

from PIL import Image

SKILL = os.environ.get('SKILL_SCRIPTS', '/root/.claude/skills/synced/c3b8cbb8-56f6-46e0-b1a5-a4a6350f4608_'
                       '9bcc0299-39b2-4127-bbdc-0f93b08ab14a/minecraft-nexo-gui/scripts')
sys.path.insert(0, SKILL)
import lettering  # noqa: E402
import props  # noqa: E402
import shading  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PACK = os.path.join(ROOT, 'pack', 'assets', 'rezusmenu')
UI = os.path.join(PACK, 'textures', 'ui')

# Keep in sync with PanelMath.ORDER (appended after the RPG-menu glyphs).
RPG_ORDER = ['panel_0', 'panel_1', 'panel_2', 'panel_3', 'tab_active', 'tab_hover', 'slot_hover', 'slot_selected',
             'button_hover', 'bar_exp', 'bar_hp', 'bar_mana', 'cursor', 'bar_energy', 'skills_bg_0', 'skills_bg_1',
             'skill_hover', 'skill_locked', 'cursor_mirror'] + [f'back_{r}_{c}' for r in range(3) for c in range(3)]
CLASSES = ['swordsman', 'archer', 'mage', 'thief']
PF_ORDER = [f'pf_bg_{r}_{c}' for r in range(3) for c in range(3)] + [
    'pf_logo', 'pf_lobby', 'pf_lobby_hover', 'pf_panel_0', 'pf_panel_1', 'pf_card', 'pf_card_empty',
    'pf_btn', 'pf_btn_green', 'pf_btn_hover', 'pf_buy', 'pf_buy_hover', 'pf_arrow_up', 'pf_arrow_down',
    'pf_arrow_hover', 'pf_row', 'pf_row_hover'] + [f'pf_cls_{k}' for k in CLASSES] + [f'pf_cls_{k}_l' for k in CLASSES]

BACK_X, BACK_W = [0, 256, 512], [256, 256, 192]
BACK_Y, BACK_H = [0, 144, 288], [144, 144, 112]
CW, CH = 704, 400
# ProfilesScreen layout (canvas px)
LOGO_X, LOGO_Y = 8, 6
LOBBY_X, LOBBY_Y = 10, 366
PANEL_X, PANEL_Y, PANEL_W, PANEL_H = 472, 36, 224, 320
FEET_X, FEET_Y = 300, 346


# ================================================================= kit (shared with the RPG menu art)

def R5(c, soft=0.0, **kw):
    return [tuple(t) for t in shading.ramp5(c, soft=soft, **kw)]   # 0 outline, 1 shadow, 2 dark, 3 base, 4 lt, 5 hi


WOOD = R5('#6a4128')             # frame walnut
WOODD = R5('#3c2618', soft=0.5)  # plates, idle buttons
WOODB = R5('#7a4c2e')            # brighter walnut: hovered button (1 tone up)
PANEL = R5('#2a1a12', soft=0.6)  # section floor
GOLD = R5('#cf8f2e')
BRASS = R5('#8c6634', soft=0.4)
TEAL = R5('#173332', soft=0.45)  # wells
CYAN = R5('#2cc7bf')
EMER = R5('#26b05a')
GREEN = R5('#2a7444', soft=0.3)  # cloth (banners, "continue" button)
GREEN_D = R5('#1d5a35', soft=0.35)
IRON = R5('#454a54')
FLAME = R5('#f2a43a')
RUBY = R5('#c43040')
CRIMSON = R5('#6e2630', soft=0.3)   # muted crimson: lobby button
CRIMSONB = R5('#86303a', soft=0.3)  # its hover
HP = R5('#d23a3a')
EXP = R5('#78c43a')
STEEL = R5('#a3abbb')
LEATHER = R5('#5c3a26', soft=0.3)
SHAFT = R5('#9a6838')
STRING = R5('#d8d0b8', soft=0.4)
VIOLET = R5('#7a4cc0')
AM = [(24, 10, 40), (40, 20, 70), (80, 40, 130), (120, 70, 180), (160, 110, 220), (210, 170, 250)]
OUT = (22, 12, 8)
PLATE_FLAT = (44, 28, 18)        # calm text band of plates


def dramp(hexc):
    """Low-contrast ramp for the dark canvas (same as the RPG background): shadows cooler, lights warmer."""
    r, g, b = int(hexc[1:3], 16), int(hexc[3:5], 16), int(hexc[5:7], 16)
    out = []
    for f, sh in ((0.45, (-2, 0, 2)), (0.66, (-1, 0, 2)), (0.83, (-1, 0, 1)), (1.0, (0, 0, 0)),
                  (1.2, (2, 1, 0)), (1.42, (4, 3, 0))):
        out.append(tuple(max(0, min(255, round(v * f) + d)) for v, d in zip((r, g, b), sh)))
    return out


STONEB = dramp('#283033')
STONEV = dramp('#20272a')
STONEW = dramp('#191f21')
FLOORB = dramp('#33231a')
DAIS = dramp('#323c3e')          # stone dais under the character (a step lighter than the wall)


def A(c):
    return tuple(c[:3]) + (255,)


class Pen:
    def __init__(self, img):
        self.img = img
        self.px = img.load()
        self.W, self.H = img.size

    def p(self, x, y, c):
        if 0 <= x < self.W and 0 <= y < self.H:
            self.px[x, y] = A(c)

    def get(self, x, y):
        return self.px[x, y]

    def rect(self, x0, y0, x1, y1, c):
        for y in range(max(0, y0), min(self.H, y1)):
            for x in range(max(0, x0), min(self.W, x1)):
                self.px[x, y] = A(c)

    def hl(self, x0, x1, y, c):
        for x in range(x0, x1):
            self.p(x, y, c)

    def vl(self, x, y0, y1, c):
        for y in range(y0, y1):
            self.p(x, y, c)

    def ring(self, x0, y0, x1, y1, tl, br=None, corner=None):
        br = br or tl
        self.hl(x0, x1, y0, tl)
        self.vl(x0, y0, y1, tl)
        self.hl(x0, x1, y1 - 1, br)
        self.vl(x1 - 1, y0, y1, br)
        if corner:
            self.p(x1 - 1, y0, corner)
            self.p(x0, y1 - 1, corner)

    def clear(self, x, y):
        if 0 <= x < self.W and 0 <= y < self.H:
            self.px[x, y] = (0, 0, 0, 0)

    def sprite(self, rows, pal, ox, oy):
        for y, r in enumerate(rows):
            for x, ch in enumerate(r):
                if ch in pal:
                    self.p(ox + x, oy + y, pal[ch])


def grain(pen, x0, y0, x1, y1, ramp, horizontal=True, seed=1, dens=0.06, light=True, lo=2, hi=4):
    """Wood: base fill + grain streaks (3-8 px clusters) along the grain, a few lit streaks."""
    rnd = random.Random(seed)
    pen.rect(x0, y0, x1, y1, ramp[3])
    if horizontal:
        for y in range(y0, y1):
            x = x0 + rnd.randint(0, 6)
            while x < x1:
                L = rnd.randint(3, 8)
                r = rnd.random()
                if r < dens * 4:
                    pen.hl(x, min(x1, x + L), y, ramp[lo])
                elif light and r < dens * 5.2:
                    pen.hl(x, min(x1, x + max(2, L - 3)), y, ramp[hi])
                x += L + rnd.randint(4, 14)
    else:
        for x in range(x0, x1):
            y = y0 + rnd.randint(0, 6)
            while y < y1:
                L = rnd.randint(3, 8)
                r = rnd.random()
                if r < dens * 4:
                    pen.vl(x, y, min(y1, y + L), ramp[lo])
                elif light and r < dens * 5.2:
                    pen.vl(x, y, min(y1, y + max(2, L - 3)), ramp[hi])
                y += L + rnd.randint(4, 14)


GEM5 = ["..o..", ".oHo.", "oLBDo", ".oDo.", "..o.."]
GEM7 = ["..ooo..", ".oHLLo.", "oHLLBBo", "oLBBBDo", ".oBDDo.", "..oDo..", "...o..."]


def gem(pen, x, y, ramp, big=False):
    pen.sprite(GEM7 if big else GEM5, {'o': ramp[0], 'H': (255, 252, 236), 'L': ramp[4], 'B': ramp[3],
                                       'D': ramp[2]}, x, y)


def rivet(pen, x, y, ramp=GOLD):
    pen.p(x, y, ramp[5]); pen.p(x + 1, y, ramp[4]); pen.p(x, y + 1, ramp[4]); pen.p(x + 1, y + 1, ramp[2])


LANTERN = [
    "...ooo...",
    "...o.o...",
    "..oGGGo..",
    ".oGgggIo.",
    ".oIIIIIo.",
    ".oyYhYIo.",
    ".oYhHhyo.",
    ".oYhHhyo.",
    ".oYYhYyo.",
    ".oyYYyyo.",
    ".oIIIIIo.",
    "..oGgIo..",
    "...ooo...",
]


def lantern(pen, x, y):
    pen.sprite(LANTERN, {'o': IRON[0], 'I': IRON[2], 'G': GOLD[4], 'g': GOLD[2], 'y': FLAME[2], 'Y': FLAME[3],
                         'h': FLAME[4], 'H': FLAME[5]}, x, y)


def cut_box(pen, x0, y0, x1, y1, c):
    pen.ring(x0, y0, x1, y1, c)
    for cx, cy in ((x0, y0), (x1 - 1, y0), (x0, y1 - 1), (x1 - 1, y1 - 1)):
        pen.clear(cx, cy)


def well(pen, x0, y0, x1, y1, inner=TEAL, rim=GOLD):
    """Turquoise well: outline, raised gold rim (lit TL / dark BR), dark lip, recessed floor (shadow TL, lit BR)."""
    pen.ring(x0, y0, x1, y1, OUT)
    pen.ring(x0 + 1, y0 + 1, x1 - 1, y1 - 1, rim[4], rim[2], rim[3])
    pen.p(x0 + 1, y0 + 1, rim[5])
    pen.ring(x0 + 2, y0 + 2, x1 - 2, y1 - 2, inner[0])
    pen.rect(x0 + 3, y0 + 3, x1 - 3, y1 - 3, inner[3])
    pen.hl(x0 + 3, x1 - 3, y0 + 3, inner[1])
    pen.vl(x0 + 3, y0 + 3, y1 - 3, inner[1])
    pen.hl(x0 + 4, x1 - 4, y0 + 4, inner[2])
    pen.vl(x0 + 4, y0 + 4, y1 - 4, inner[2])
    pen.hl(x0 + 5, x1 - 3, y1 - 4, inner[4])
    pen.vl(x1 - 4, y0 + 5, y1 - 3, inner[4])


def plate(pen, x0, y0, x1, y1, seed=3, caps=True, wood=WOODD, ring=None):
    """Walnut text plate: cut-corner outline, lit top row, calm grain, darker lip, brass end caps with rivets."""
    grain(pen, x0 + 1, y0 + 1, x1 - 1, y1 - 1, wood, seed=seed, dens=0.035, lo=2, hi=4)
    pen.hl(x0 + 1, x1 - 1, y0 + 1, wood[4])
    pen.hl(x0 + 1, x1 - 1, y1 - 2, wood[1])
    pen.vl(x0 + 1, y0 + 1, y1 - 1, wood[4])
    pen.vl(x1 - 2, y0 + 2, y1 - 1, wood[2])
    pen.p(x0 + 1, y0 + 1, wood[5])
    pen.rect(x0 + 3, y0 + 3, x1 - 3, y1 - 3, PLATE_FLAT if wood is WOODD else wood[2])
    cut_box(pen, x0, y0, x1, y1, OUT)
    if ring:
        pen.ring(x0 + 1, y0 + 1, x1 - 1, y1 - 1, ring[0], ring[1])
    if caps:
        h = y1 - y0
        for cx, flip in ((x0, False), (x1 - 4, True)):
            pen.rect(cx + 1, y0 + 1, cx + 3, y1 - 1, GOLD[3])
            pen.vl(cx + 1, y0 + 1, y1 - 1, GOLD[4] if not flip else GOLD[3])
            pen.vl(cx + 2, y0 + 1, y1 - 1, GOLD[3] if not flip else GOLD[2])
            pen.p(cx + 1, y0 + 1, GOLD[5])
            pen.p(cx + 2, y1 - 2, GOLD[1])
            pen.vl(cx + 3 if not flip else cx, y0 + 1, y1 - 1, OUT)
            rivet(pen, cx + 1, y0 + h // 2 - 1)


def inset(pen, x0, y0, x1, y1):
    """Recessed calm text band in a plate: shadowed top/left, lit lip bottom/right."""
    pen.rect(x0, y0, x1, y1, PLATE_FLAT)
    pen.hl(x0, x1, y0, OUT)
    pen.vl(x0, y0, y1, OUT)
    pen.hl(x0 + 1, x1, y0 + 1, WOODD[1])
    pen.vl(x0 + 1, y0 + 1, y1, WOODD[1])
    pen.hl(x0 + 1, x1, y1 - 1, WOODD[4])
    pen.vl(x1 - 1, y0 + 1, y1, WOODD[4])


def bracket(pen, x, y, L, corner):
    """Small gold L corner bracket (2 px thick, legs L) with outline, light from TL."""
    sx = 1 if corner in ('tl', 'bl') else -1
    sy = 1 if corner in ('tl', 'tr') else -1
    for i in range(L):
        for t in range(2):
            c = GOLD[4] if t == 0 else GOLD[2]
            if sy < 0:
                c = GOLD[3] if t == 0 else GOLD[2]
            pen.p(x + sx * i, y + sy * t, c)
            c2 = GOLD[4] if t == 0 else GOLD[2]
            if sx < 0:
                c2 = GOLD[3] if t == 0 else GOLD[2]
            pen.p(x + sx * t, y + sy * i, c2)
    pen.p(x, y, GOLD[5])
    for i in range(2, L):
        pen.p(x + sx * i, y + sy * 2, OUT)
        pen.p(x + sx * 2, y + sy * i, OUT)
    pen.p(x + sx * L, y, OUT); pen.p(x + sx * L, y + sy, OUT)
    pen.p(x, y + sy * L, OUT); pen.p(x + sx, y + sy * L, OUT)


def section(pen, x0, y0, x1, y1, seed=5):
    """Recessed panel: outline, gold trim ring (lit TL), dark groove, walnut floor with AO under the rim."""
    pen.ring(x0, y0, x1, y1, OUT)
    pen.ring(x0 + 1, y0 + 1, x1 - 1, y1 - 1, GOLD[4], GOLD[2], GOLD[3])
    pen.ring(x0 + 2, y0 + 2, x1 - 2, y1 - 2, OUT)
    grain(pen, x0 + 3, y0 + 3, x1 - 3, y1 - 3, PANEL, seed=seed, dens=0.03, lo=2, hi=4)
    pen.hl(x0 + 3, x1 - 3, y0 + 3, PANEL[1])
    pen.vl(x0 + 3, y0 + 3, y1 - 3, PANEL[1])
    pen.hl(x0 + 4, x1 - 4, y0 + 4, PANEL[2])
    pen.vl(x0 + 4, y0 + 4, y1 - 4, PANEL[2])
    pen.hl(x0 + 4, x1 - 3, y1 - 4, PANEL[4])
    pen.vl(x1 - 4, y0 + 4, y1 - 3, PANEL[4])
    for (cx, cy, c) in ((x0 + 1, y0 + 1, 'tl'), (x1 - 2, y0 + 1, 'tr'), (x0 + 1, y1 - 2, 'bl'),
                        (x1 - 2, y1 - 2, 'br')):
        bracket(pen, cx, cy, 9, c)


def button(pen, x, y, w, h, state='idle', jewel=CYAN, face=None):
    """RPG-menu button: raised walnut (or cloth) face, gold ring, a jewel at each end; hover = a tone up,
    bright gold ring and glowing amber jewels (exactly like the RPG button_hover)."""
    x1, y1 = x + w, y + h
    wood = face or (WOODB if state == 'hover' else WOODD)
    grain(pen, x + 3, y + 3, x1 - 3, y1 - 3, wood, seed=x * 3 + w + (9 if state == 'hover' else 0), dens=0.04)
    pen.hl(x + 3, x1 - 3, y + 3, wood[5] if state == 'hover' else wood[4])
    pen.hl(x + 3, x1 - 3, y + 4, wood[4])
    pen.vl(x + 3, y + 3, y1 - 3, wood[4])
    pen.vl(x1 - 4, y + 4, y1 - 3, wood[2])
    pen.hl(x + 3, x1 - 3, y1 - 5, wood[2])
    pen.hl(x + 3, x1 - 3, y1 - 4, wood[1])
    pen.ring(x + 2, y + 2, x1 - 2, y1 - 2, OUT)
    if state == 'hover':
        pen.ring(x + 1, y + 1, x1 - 1, y1 - 1, (250, 215, 120), GOLD[4], (250, 215, 120))
    else:
        pen.ring(x + 1, y + 1, x1 - 1, y1 - 1, GOLD[4], GOLD[2], GOLD[3])
    cut_box(pen, x, y, x1, y1, OUT)
    my = y + (h - 5) // 2
    if state == 'hover':
        for gx in (x + 6, x1 - 11):
            for dy in range(-3, 4):
                for dx in range(-3, 4):
                    if abs(dx) + abs(dy) == 3:
                        pen.p(gx + 2 + dx, my + 2 + dy, jewel[4])
    gem(pen, x + 6, my, jewel)
    gem(pen, x1 - 11, my, jewel)


EMBLEM = [
    ".....oo.....",
    ".....HLo....",
    ".....LBo....",
    "...ooLBoo...",
    "..oLLLBBDo..",
    ".oLBBLBBBDo.",
    ".oLBEeBBBDo.",
    ".oLBeeBBBDo.",
    ".oBBBLBBDDo.",
    "..oBBLBBDo..",
    "...oDLBDo...",
    "....oLBo....",
    ".....LBo....",
    "....oHBDo...",
    ".....oDo....",
    "......o.....",
]


def hexc(c):
    return '#%02x%02x%02x' % tuple(c[:3])


def banner(pen, x, y, w, h, seed):
    """Green hanging banner (RPG left wing / title pennants) with gold trim and the gold sword-and-shield emblem."""
    im = props.banner(w, h, color=hexc(GREEN[3]), trim=hexc(GOLD[4]), rod=hexc(WOOD[2]), tail='swallow',
                      emblem=None, folds='swag', age=0.35, torn=1, seed=seed)
    pen.img.alpha_composite(im, (x, y))
    pen.sprite(EMBLEM, {'o': GOLD[0], 'H': GOLD[5], 'L': GOLD[4], 'B': GOLD[3], 'D': GOLD[2], 'e': EMER[3],
                        'E': EMER[5]}, x + w // 2 - 6, y + 16)


# ================================================================= background: the castle hall of the RPG menu

_RAMPS = None


def darken(pen, x, y, n=2):
    """Contact / drop shadow on the wall: move a wall pixel n steps down its own ramp."""
    global _RAMPS
    if _RAMPS is None:
        _RAMPS = {}
        for rp in (STONEB, STONEV, STONEW, FLOORB, DAIS):
            for i, c in enumerate(rp):
                _RAMPS.setdefault(A(c), (rp, i))
    if not (0 <= x < pen.W and 0 <= y < pen.H):
        return
    hit = _RAMPS.get(pen.get(x, y))
    if hit:
        rp, i = hit
        pen.p(x, y, rp[max(0, i - n)])


def wall(pen):
    """Castle wall of big dark blocks 32x16 (staggered), lit top edges, cool cracks; stepped vignette (3 rings)."""
    rnd = random.Random(77)
    cx, cy = CW / 2, CH / 2 - 10
    for row, yb in enumerate(range(-6, CH, 16)):
        off = 0 if row % 2 == 0 else 16
        for xb in range(-off, CW, 32):
            bx, by = xb + 16, yb + 8
            d = math.hypot((bx - cx) / (CW * 0.55), (by - cy) / (CH * 0.62))
            ramp = STONEB if d < 0.62 else (STONEV if d < 0.82 else STONEW)
            tone = 3 if rnd.random() < 0.62 else 2
            for y in range(yb, yb + 16):
                for x in range(xb, xb + 32):
                    if not (0 <= x < CW and 0 <= y < CH):
                        continue
                    if y == yb or x == xb:
                        c = ramp[1]
                    elif y == yb + 1:
                        c = ramp[min(5, tone + 1)]
                    elif y == yb + 15 or x == xb + 31:
                        c = ramp[max(1, tone - 1)]
                    else:
                        c = ramp[tone]
                    pen.p(x, y, c)
            if rnd.random() < 0.35:
                kx, ky = xb + rnd.randint(4, 26), yb + rnd.randint(4, 11)
                for dx, dy in ((0, 0), (1, 0), (2, 1), (3, 1))[:rnd.randint(2, 4)]:
                    pen.p(kx + dx, ky + dy, ramp[1])
                pen.p(kx, ky - 1, ramp[min(5, tone + 1)])


def wainscot(pen):
    """Dark walnut skirting y 384..400 + ceiling beam y 0..6 with iron straps."""
    grain(pen, 0, 386, CW, CH, FLOORB, seed=91, dens=0.05, lo=2, hi=4)
    pen.hl(0, CW, 385, OUT); pen.hl(0, CW, 386, FLOORB[4]); pen.hl(0, CW, 384, STONEW[0])
    pen.hl(0, CW, CH - 1, FLOORB[1])
    for x in range(10, CW, 48):
        pen.vl(x, 387, CH, FLOORB[1]); pen.vl(x + 1, 387, CH, FLOORB[4])
    grain(pen, 0, 0, CW, 6, FLOORB, seed=92, dens=0.07, lo=2, hi=4)
    pen.hl(0, CW, 0, FLOORB[4]); pen.hl(0, CW, 5, FLOORB[1]); pen.hl(0, CW, 6, OUT); pen.hl(0, CW, 7, STONEW[1])
    for x in range(24, CW, 96):
        pen.rect(x, 0, x + 4, 6, IRON[2]); pen.vl(x, 0, 6, IRON[4]); pen.vl(x + 3, 0, 6, IRON[1])
        pen.p(x + 1, 2, IRON[5]); pen.p(x + 2, 2, IRON[3])


def glow(pen, cx, cy, r):
    """Warm lantern light on the wall: wall tones inside r move up their ramp (two stepped rings)."""
    for y in range(cy - r, cy + r + 1):
        for x in range(cx - r, cx + r + 1):
            d = math.hypot((x - cx) * 0.85, y - cy)
            if d > r or not (0 <= x < CW and 0 <= y < CH):
                continue
            n = 2 if d < r * 0.55 else 1
            c = A(pen.get(x, y))
            for rp in (STONEW, STONEV, STONEB):
                tones = [A(q) for q in rp]
                if c in tones:
                    i = tones.index(c)
                    if 1 <= i <= 4:
                        pen.p(x, y, rp[min(4, i + n)])
                    break


def hanging_lantern(pen, x, ytop, y):
    for yy in range(ytop, y):
        pen.p(x + 4, yy, IRON[2] if yy % 2 else IRON[4])
    glow(pen, x + 4, y + 7, 13)
    lantern(pen, x, y)


def sconce(pen, x, y):
    """Wall lantern on an iron bracket (RPG panel side lanterns), with its glow on the wall."""
    glow(pen, x + 4, y + 6, 15)
    pen.rect(x + 2, y - 6, x + 7, y - 4, IRON[3]); pen.hl(x + 2, x + 7, y - 6, IRON[4]); pen.hl(x + 2, x + 7, y - 4, IRON[1])
    pen.vl(x + 4, y - 4, y, IRON[2])
    pen.ring(x + 1, y - 7, x + 8, y - 3, IRON[0])
    lantern(pen, x, y)


def crystal(pen, x, yb, h):
    for y in range(yb - h + 2, yb):
        pen.p(x - 1, y, AM[0]); pen.p(x + 3, y, AM[0])
        pen.p(x, y, AM[4]); pen.p(x + 1, y, AM[3]); pen.p(x + 2, y, AM[2])
    pen.p(x, yb - h + 1, AM[0]); pen.p(x + 2, yb - h + 1, AM[0])
    pen.p(x + 1, yb - h + 1, AM[5]); pen.p(x + 1, yb - h, AM[0])
    pen.p(x, yb - h + 2, AM[5])
    pen.hl(x - 1, x + 4, yb, AM[0])
    pen.p(x + 2, yb - 1, AM[1])


def crystals(pen, x, yb, mirror=False):
    spec = ((0, 9), (4, 18), (8, 12)) if not mirror else ((0, 12), (4, 18), (8, 9))
    order = sorted(spec, key=lambda t: -t[1])
    for dx, h in order[1:]:
        crystal(pen, x + dx + 1, yb, h)
    dx, h = order[0]
    crystal(pen, x + dx + 1, yb, h)


def dais(pen, cx, cy):
    """Round stone dais the character stands on: lit top with a gold rim, a stepped front face, a cyan rune
    circle (the portrait niche of the RPG menu, larger), amethyst crystals on both sides."""
    rx, ry, face = 96, 18, 10

    def inside(x, y, a, b, yc):
        return ((x + 0.5 - cx) / a) ** 2 + ((y + 0.5 - yc) / b) ** 2 <= 1
    # contact shadow on the wall / floor below the dais
    for y in range(cy - ry, cy + ry + face + 4):
        for x in range(cx - rx - 4, cx + rx + 5):
            if inside(x, y, rx + 3, ry + 2, cy + face + 1):
                darken(pen, x, y, 2)
    # front face (cylinder side): vertical bands, light on the left
    for y in range(cy - ry, cy + ry + face + 1):
        for x in range(cx - rx, cx + rx + 1):
            if inside(x, y, rx, ry, cy + face) and not inside(x, y, rx, ry, cy):
                u = (x + 0.5 - cx) / rx
                t = 3 if u < -0.55 else 2 if u < 0.35 else 1
                pen.p(x, y, DAIS[t])
    for x in range(cx - rx, cx + rx + 1):                 # mortar joints on the face, stone blocks 24 wide
        if (x - cx) % 24 == 0:
            for y in range(cy, cy + ry + face):
                if inside(x, y, rx, ry, cy + face) and not inside(x, y, rx, ry, cy):
                    pen.p(x, y, DAIS[0])
    # top: flagstones, lit upper-left rim
    for y in range(cy - ry, cy + ry + 1):
        for x in range(cx - rx, cx + rx + 1):
            if inside(x, y, rx, ry, cy):
                pen.p(x, y, DAIS[3])
                if not inside(x, y - 2, rx, ry, cy):
                    pen.p(x, y, DAIS[4])
    # gold rim on the top edge (1 px, lit front half lighter) and the outline
    for y in range(cy - ry - 1, cy + ry + face + 2):
        for x in range(cx - rx - 1, cx + rx + 2):
            top = inside(x, y, rx, ry, cy)
            body = top or inside(x, y, rx, ry, cy + face)
            if not body:
                if any(inside(x + dx, y + dy, rx, ry, cy) or inside(x + dx, y + dy, rx, ry, cy + face)
                       for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                    pen.p(x, y, OUT)
            elif top and not inside(x, y + 1, rx, ry, cy) and y >= cy:
                pen.p(x, y, GOLD[3] if x < cx + rx * 0.4 else GOLD[2])
                pen.p(x, y + 1, OUT)
    rune_circle(pen, cx, cy, 66, 12, 54, 9)
    crystals(pen, cx - rx + 6, cy + 4)
    crystals(pen, cx + rx - 18, cy + 4, mirror=True)


def rune_circle(pen, cx, cy, rx1, ry1, rx2, ry2):
    pts_outer, pts_inner = set(), set()

    def ell(rx, ry, out):
        for k in range(1440):
            a = k / 1440 * 2 * math.pi
            out.add((int(round(cx - 0.5 + rx * math.cos(a))), int(round(cy + ry * math.sin(a)))))
    ell(rx1, ry1, pts_outer)
    ell(rx2, ry2, pts_inner)
    for y in range(cy - ry1, cy + ry1 + 1):
        for x in range(cx - rx1 - 1, cx + rx1 + 1):
            if ((x + 0.5 - cx) / rx1) ** 2 + ((y - cy) / ry1) ** 2 <= 1:
                v = ((x + 0.5 - cx) / rx2) ** 2 + ((y - cy) / ry2) ** 2
                pen.p(x, y, CYAN[1] if v > 1 else CYAN[2])
    for (x, y) in pts_outer:
        pen.p(x, y, CYAN[4] if y < cy else CYAN[3])
    for (x, y) in pts_inner:
        pen.p(x, y, CYAN[3] if y < cy else CYAN[4])
    nr = 20
    for k in range(nr):
        a = k / nr * 2 * math.pi + 0.1
        rx, ry = (rx1 + rx2) / 2, (ry1 + ry2) / 2
        x, y = int(round(cx - 0.5 + rx * math.cos(a))), int(round(cy + ry * math.sin(a)))
        if k % 2:
            pen.p(x, y, CYAN[5]); pen.p(x + 1, y, CYAN[4])
        else:
            pen.p(x, y, CYAN[4]); pen.p(x - 1, y, CYAN[3])
    for k in range(8):                                    # inner star (two rotated squares, flattened)
        a0 = k / 8 * 2 * math.pi
        a1 = (k + 3) / 8 * 2 * math.pi
        for t in range(90):
            s = t / 90
            x = cx - 0.5 + rx2 * 0.9 * ((1 - s) * math.cos(a0) + s * math.cos(a1))
            y = cy + ry2 * 0.9 * ((1 - s) * math.sin(a0) + s * math.sin(a1))
            pen.p(int(round(x)), int(round(y)), CYAN[3])
    for sx, sy in ((cx - 50, cy - 26), (cx + 44, cy - 34), (cx - 66, cy - 44), (cx + 62, cy - 20),
                   (cx - 24, cy - 58), (cx + 30, cy - 70)):
        pen.p(sx, sy, CYAN[5]); pen.p(sx - 1, sy, CYAN[3]); pen.p(sx + 1, sy, CYAN[3])
        pen.p(sx, sy - 1, CYAN[3]); pen.p(sx, sy + 1, CYAN[3])


def shadow_of(pen, im, ox, oy, d=2, n=2):
    """Drop shadow of an overlay texture (its opaque pixels) on the wall, offset d px down-right."""
    px = im.load()
    W, H = im.size
    for y in range(H + d):
        for x in range(W + d):
            sx, sy = x - d, y - d
            mine = 0 <= x < W and 0 <= y < H and px[x, y][3] > 128
            cast = 0 <= sx < W and 0 <= sy < H and px[sx, sy][3] > 128
            if cast and not mine:
                darken(pen, ox + x, oy + y, n)


def scene(logo_im, lobby_im):
    img = Image.new('RGBA', (CW, CH), (0, 0, 0, 255))
    pen = Pen(img)
    wall(pen)
    wainscot(pen)
    # tall green banners behind the character, lanterns between them, pennants by the panel
    banner(pen, 212, 6, 34, 128, seed=3)
    banner(pen, 354, 6, 34, 128, seed=8)
    hanging_lantern(pen, 296, 7, 40)
    sconce(pen, 150, 196)
    sconce(pen, 432, 196)
    dais(pen, FEET_X, FEET_Y)
    # drop shadows of the overlays that stand on the wall
    panel_mask = Image.new('RGBA', (PANEL_W, PANEL_H), (0, 0, 0, 255))
    shadow_of(pen, panel_mask, PANEL_X, PANEL_Y, d=3)
    shadow_of(pen, logo_im, LOGO_X, LOGO_Y)
    shadow_of(pen, lobby_im, LOBBY_X, LOBBY_Y)
    # sparks / dust motes (small plus-shaped glints, low contrast) as on the RPG background
    for sx, sy in ((30, 340), (452, 46), (140, 160), (446, 262), (214, 392), (126, 300), (690, 380), (20, 140),
                   (360, 150), (240, 178)):
        pen.p(sx, sy, FLAME[3]); pen.p(sx - 1, sy, FLAME[1]); pen.p(sx + 1, sy, FLAME[1])
        pen.p(sx, sy - 1, FLAME[1]); pen.p(sx, sy + 1, FLAME[1])
    return img


# ================================================================= logo: walnut + gold sign "AZERUS" / "QXCER"

def corner_cap(pen, x, y, s=9):
    pen.rect(x, y, x + s, y + s, GOLD[3])
    pen.hl(x + 1, x + s - 1, y + 1, GOLD[4]); pen.vl(x + 1, y + 1, y + s - 1, GOLD[4])
    pen.hl(x + 1, x + s - 1, y + s - 2, GOLD[2]); pen.vl(x + s - 2, y + 1, y + s - 1, GOLD[2])
    pen.p(x + 1, y + 1, GOLD[5]); pen.p(x + 2, y + 1, GOLD[5])
    pen.ring(x, y, x + s, y + s, GOLD[0])
    gem(pen, x + (s - 5) // 2, y + (s - 5) // 2, EMER)


def sign_board(w, h, seed, boards=3):
    """Walnut sign: frame of the RPG panel (walnut body, gold inlay, outlines), horizontal boards inside,
    gold corner caps with emeralds."""
    im = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    pen = Pen(im)
    grain(pen, 1, 1, w - 1, h - 1, WOOD, seed=seed, dens=0.08)
    pen.hl(1, w - 1, 1, WOOD[4]); pen.vl(1, 1, h - 1, WOOD[4])
    pen.hl(2, w - 1, h - 2, WOOD[1]); pen.vl(w - 2, 2, h - 1, WOOD[1])
    pen.ring(0, 0, w, h, OUT)
    pen.ring(4, 4, w - 4, h - 4, GOLD[4], GOLD[2], GOLD[3])
    for x in range(14, w - 14, 37):
        pen.hl(x, x + 3, 4, GOLD[5])
    pen.ring(5, 5, w - 5, h - 5, OUT)
    # boards: darker walnut, seams = dark gap + lit lip
    grain(pen, 6, 6, w - 6, h - 6, WOODD, seed=seed + 1, dens=0.05)
    bh = (h - 12) // boards
    for i in range(1, boards):
        y = 6 + i * bh
        pen.hl(6, w - 6, y, WOODD[0]); pen.hl(6, w - 6, y + 1, WOODD[4])
    pen.hl(6, w - 6, 6, WOODD[1]); pen.vl(6, 6, h - 6, WOODD[1])        # AO under the gold inlay
    for cx, cy in ((0, 0), (w - 9, 0), (0, h - 9), (w - 9, h - 9)):
        corner_cap(pen, cx, cy)
    return im


def logo():
    W, H = 192, 112
    im = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    pen = Pen(im)
    green = R5('#5cc23c')
    big = lettering.volume('AZERUS', green, scale=3, depth=3)
    small = lettering.volume('QXCER', green, scale=2, depth=2)
    lw = small.width + 30
    lx = (W - lw) // 2
    # gold chains joining the boards (behind both)
    for x0 in (lx + 12, lx + lw - 15):
        for k, y in enumerate(range(60, 76, 3)):
            if k % 2 == 0:
                pen.ring(x0, y, x0 + 3, y + 4, GOLD[0]); pen.p(x0 + 1, y + 1, GOLD[4]); pen.p(x0 + 1, y + 2, GOLD[2])
            else:
                pen.rect(x0 + 1, y, x0 + 2, y + 4, GOLD[0]); pen.p(x0 + 1, y + 1, GOLD[3]); pen.p(x0 + 1, y + 2, GOLD[2])
    lower = sign_board(lw, 36, seed=4, boards=2)
    lower.alpha_composite(small, ((lw - small.width) // 2, (36 - small.height) // 2 + 1))
    im.alpha_composite(lower, (lx, 72))
    main = sign_board(W - 6, 66, seed=2, boards=3)
    main.alpha_composite(big, ((main.width - big.width) // 2, (66 - big.height) // 2 + 1))
    im.alpha_composite(main, (3, 0))
    return im


# ================================================================= panel, cards, buttons

def panel():
    W, H = PANEL_W, PANEL_H
    im = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    pen = Pen(im)
    section(pen, 0, 0, W, H, seed=51)
    # header: green cloth band with gold edges (the RPG title ribbon), text band y 8..25
    G = GREEN_D
    x0, x1, y0, y1 = 6, W - 6, 5, 29
    pen.rect(x0, y0, x1, y1, G[3])
    pen.ring(x0 - 1, y0 - 1, x1 + 1, y1 + 1, OUT)
    pen.hl(x0, x1, y0, GOLD[4]); pen.hl(x0, x1, y0 + 1, GOLD[2])
    pen.hl(x0, x1, y1 - 2, GOLD[3]); pen.hl(x0, x1, y1 - 1, GOLD[1])
    pen.hl(x0, x1, y0 + 2, G[1])
    pen.rect(x0 + 2, y0 + 3, x1 - 2, y1 - 3, G[2])
    pen.hl(x0 + 2, x1 - 2, y0 + 3, G[1])
    pen.hl(x0 + 2, x1 - 2, y1 - 4, G[3])
    for fx in (x0 + 4, x1 - 9):                         # cloth folds near the ends
        pen.vl(fx, y0 + 3, y1 - 3, G[4]); pen.vl(fx + 1, y0 + 3, y1 - 3, G[3]); pen.vl(fx + 4, y0 + 3, y1 - 3, G[1])
    # gold rule under the header with an emerald in the middle
    for x in range(14, W - 14):
        pen.p(x, 30, OUT); pen.p(x, 31, GOLD[4]); pen.p(x, 32, GOLD[2]); pen.p(x, 33, OUT)
    gem(pen, W // 2 - 3, 29, EMER, big=False)
    # emerald corner jewels (RPG wing cards)
    for gx, gy in ((0, 0), (W - 5, 0), (0, H - 5), (W - 5, H - 5)):
        gem(pen, gx, gy, EMER)
    # ornament between the buy plate and the lower card button: tiny gold dots
    for x in range(40, W - 40, 4):
        pen.p(x, 283, GOLD[2])
    return im.crop((0, 0, W, 160)), im.crop((0, 160, W, 320))


ICON_LEVEL = ["...a...", "..aca..", "aaacaaa", "acccccb", ".abcbb.", ".ab.bb.", ".a...a."]
ICON_PIN = [".aaaaa.", "abccdba", "abcddba", ".abdba.", ".abba..", "..aa...", "..a...."]
ICON_SWORD = [".....aa", "....acd", "...acda", "a.acda.", ".aada..", ".cba...", "b..a..."]
ICON_FEATHER = ["....aaa", "...accd", "..acdda", ".acdda.", ".adda..", "a.aa...", "a......"]
ICON_HEART = [".aa.aa.", "acdacda", "accdcba", "abccbba", ".abbba.", "..aba..", "...a..."]


def mini(pen, x, y, rows, ramp):
    pen.sprite(rows, {'a': ramp[0], 'b': ramp[2], 'c': ramp[3], 'd': ramp[5]}, x, y)


def card_base(pen, w, h, wood=WOODD, bright=False, seed=7):
    grain(pen, 2, 2, w - 2, h - 2, wood, seed=seed, dens=0.04)
    pen.hl(2, w - 2, 2, wood[4]); pen.vl(2, 2, h - 2, wood[4])
    pen.hl(2, w - 2, h - 3, wood[1]); pen.vl(w - 3, 2, h - 2, wood[2])
    if bright:
        pen.ring(1, 1, w - 1, h - 1, (250, 215, 120), GOLD[4], (250, 215, 120))
    else:
        pen.ring(1, 1, w - 1, h - 1, GOLD[4], GOLD[2], GOLD[3])
    cut_box(pen, 0, 0, w, h, OUT)


def card(empty=False):
    W, H = 208, 96
    im = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    pen = Pen(im)
    card_base(pen, W, H, seed=12 if empty else 11)
    # bust well: floor x 4..67, y 4..91 (the bust covers x 4..67, y 12..91)
    pen.ring(2, 2, 70, 94, OUT)
    pen.ring(3, 3, 69, 93, GOLD[4], GOLD[2], GOLD[3])
    pen.rect(4, 4, 68, 92, TEAL[3])
    pen.hl(4, 68, 4, TEAL[1]); pen.vl(4, 4, 92, TEAL[1])
    pen.hl(5, 68, 5, TEAL[2]); pen.vl(5, 5, 92, TEAL[2])
    pen.vl(67, 6, 92, TEAL[4]); pen.hl(6, 68, 91, TEAL[4])
    # cyan rune glints behind the head
    for sx, sy in ((14, 8), (58, 9)):
        pen.p(sx, sy, CYAN[4]); pen.p(sx - 1, sy, CYAN[2]); pen.p(sx + 1, sy, CYAN[2])
    gem(pen, 33, 0, EMER)
    if empty:
        # big gold plus in the well
        cx, cy = 36, 48
        pen.rect(cx - 4, cy - 14, cx + 4, cy + 15, GOLD[0])
        pen.rect(cx - 14, cy - 4, cx + 15, cy + 4, GOLD[0])
        pen.rect(cx - 3, cy - 13, cx + 3, cy + 14, GOLD[3])
        pen.rect(cx - 13, cy - 3, cx + 14, cy + 3, GOLD[3])
        pen.vl(cx - 3, cy - 13, cy + 14, GOLD[4]); pen.hl(cx - 13, cx + 14, cy - 3, GOLD[4])
        pen.vl(cx + 2, cy - 12, cy + 14, GOLD[2]); pen.hl(cx - 12, cx + 14, cy + 2, GOLD[2])
        pen.p(cx - 3, cy - 13, GOLD[5]); pen.p(cx - 13, cy - 3, GOLD[5])
        gem(pen, cx - 2, cy - 2, EMER)
        inset(pen, 74, 18, 204, 76)
        return im
    inset(pen, 73, 4, 205, 47)
    inset(pen, 73, 50, 205, 93)
    mini(pen, 77, 23, ICON_LEVEL, EXP)                    # level   (text y 20..32)
    mini(pen, 77, 35, ICON_PIN, RUBY)                     # location (y 32..44)
    mini(pen, 77, 55, ICON_SWORD, STEEL)                  # stats rows y 52..64, 66..78, 80..92
    mini(pen, 77, 69, ICON_FEATHER, CYAN)
    mini(pen, 77, 83, ICON_HEART, HP)
    return im


def btn(kind):
    im = Image.new('RGBA', (208, 32), (0, 0, 0, 0))
    pen = Pen(im)
    if kind == 'idle':
        button(pen, 0, 0, 208, 20, 'idle', CYAN)
    elif kind == 'green':
        button(pen, 0, 0, 208, 20, 'idle', EMER, face=GREEN)
    else:
        button(pen, 0, 0, 208, 20, 'hover', FLAME)
    return im


def buy(hover=False):
    im = Image.new('RGBA', (208, 32), (0, 0, 0, 0))
    pen = Pen(im)
    if hover:
        plate(pen, 0, 0, 208, 24, seed=31, wood=WOODB, ring=((250, 215, 120), GOLD[4]))
    else:
        plate(pen, 0, 0, 208, 24, seed=31)
        pen.ring(1, 1, 207, 23, GOLD[4], GOLD[2])
    for gx in (8, 195):
        gem(pen, gx, 9, FLAME if hover else EMER)
    return im


def lobby(hover=False):
    im = Image.new('RGBA', (80, 32), (0, 0, 0, 0))
    button(Pen(im), 0, 0, 80, 22, 'hover' if hover else 'idle', FLAME if hover else RUBY,
           face=CRIMSONB if hover else CRIMSON)
    return im


def arrow(up, hover=False):
    im = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    pen = Pen(im)
    wood = WOODB if hover else WOODD
    pen.rect(2, 2, 14, 14, wood[3])
    pen.hl(2, 14, 2, wood[4]); pen.vl(2, 2, 14, wood[4])
    pen.hl(2, 14, 13, wood[1]); pen.vl(13, 3, 14, wood[2])
    if hover:
        pen.ring(1, 1, 15, 15, (250, 215, 120), GOLD[4])
    else:
        pen.ring(1, 1, 15, 15, GOLD[4], GOLD[2])
    cut_box(pen, 0, 0, 16, 16, OUT)
    tri = FLAME if hover else GOLD
    for i in range(4):                                    # 4-row triangle with an outline
        y = (5 + i) if up else (10 - i)
        pen.hl(7 - i, 9 + i, y, tri[3])
        pen.p(7 - i, y, tri[4])
        pen.p(8 + i, y, tri[2])
    pen.p(7, 5 if up else 10, tri[5])
    for i in range(4):
        y = (5 + i) if up else (10 - i)
        pen.p(6 - i, y, OUT); pen.p(9 + i, y, OUT)
    pen.hl(7, 9, 4 if up else 11, OUT)
    pen.hl(3, 13, 9 if up else 6, OUT)
    return im


def row(hover=False):
    im = Image.new('RGBA', (208, 64), (0, 0, 0, 0))
    pen = Pen(im)
    card_base(pen, 208, 56, wood=WOODB if hover else WOODD, bright=hover, seed=21)
    well(pen, 6, 8, 46, 48)                               # icon well 40 x 40, class icon 24 x 24 at (14, 16)
    gem(pen, 24, 5, FLAME if hover else EMER)
    inset(pen, 50, 4, 204, 52)
    return im


# ================================================================= class icons (16 px for cards, 24 px for rows)

def bres(x0, y0, x1, y1):
    pts = []
    dx, dy = abs(x1 - x0), -abs(y1 - y0)
    sx, sy = (1 if x0 < x1 else -1), (1 if y0 < y1 else -1)
    err = dx + dy
    while True:
        pts.append((x0, y0))
        if x0 == x1 and y0 == y1:
            return pts
        e2 = 2 * err
        if e2 >= dy:
            err += dy
            x0 += sx
        if e2 <= dx:
            err += dx
            y0 += sy


def thick(pts, w=2):
    out = set(pts)
    if w >= 2:
        out |= {(x + 1, y) for x, y in pts}
    return out


class Icon:
    """Layered pixel icon: each part = a mask + a ramp. Parts are painted back to front, shaded for a top-left
    light (lit where the top/left neighbour is outside the part, dark where the bottom/right one is) and
    outlined with the darkest tone of their own ramp (never black)."""

    def __init__(self, s):
        self.s = s
        self.parts = []

    def add(self, mask, ramp, hi=(), flat=False, outline=True):
        self.parts.append(({(x, y) for x, y in mask if 0 <= x < self.s and 0 <= y < self.s}, ramp, set(hi), flat,
                           outline))

    def render(self):
        s = self.s
        col, owner = {}, {}
        for idx, (mask, ramp, hi, flat, outline) in enumerate(self.parts):
            for (x, y) in (mask if outline else ()):          # outline over the parts behind this one
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    q = (x + dx, y + dy)
                    if 0 <= q[0] < s and 0 <= q[1] < s and q not in mask and owner.get(q, -1) < idx:
                        col[q] = ramp[0]
                        owner[q] = idx
            for (x, y) in mask:
                if flat:
                    c = ramp[3]
                elif (x, y - 1) not in mask or (x - 1, y) not in mask:
                    c = ramp[4]
                elif (x, y + 1) not in mask or (x + 1, y) not in mask:
                    c = ramp[2]
                else:
                    c = ramp[3]
                col[(x, y)] = ramp[5] if (x, y) in hi else c
                owner[(x, y)] = idx
        im = Image.new('RGBA', (s, s), (0, 0, 0, 0))
        for (x, y), c in col.items():
            im.putpixel((x, y), A(c))
        return im


def heater(x0, y0, x1, y1):
    """Heater shield mask: straight top, sides straight down to the middle, then curving to a point."""
    m = set()
    cx = (x0 + x1) / 2
    mid = y0 + (y1 - y0) * 0.45
    for y in range(y0, y1 + 1):
        if y <= mid:
            half = (x1 - x0) / 2
        else:
            t = (y - mid) / (y1 - mid)
            half = (x1 - x0) / 2 * math.sqrt(max(0.0, 1 - t * t))
        for x in range(x0, x1 + 1):
            if abs(x + 0.5 - (cx + 0.5)) <= half + 0.01:
                m.add((x, y))
    return m


def shrink(mask):
    return {(x, y) for x, y in mask if all((x + dx, y + dy) in mask for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))}


def disk(cx, cy, r):
    return {(x, y) for y in range(int(cy - r - 1), int(cy + r + 2)) for x in range(int(cx - r - 1), int(cx + r + 2))
            if (x + 0.5 - cx) ** 2 + (y + 0.5 - cy) ** 2 <= r * r}


def icon_swordsman(s):
    k = s / 16
    ic = Icon(s)
    tip = (s - 2, 1)
    hilt = (round(4 * k), s - 1 - round(4 * k))                # guard centre
    blade = bres(hilt[0] + 1, hilt[1] - 1, tip[0] - 1, tip[1] + 1)
    ic.add(thick(blade) | {tip}, STEEL, hi={blade[len(blade) * 3 // 4], (tip[0] - 2, tip[1] + 2)})
    g = round(2 * k) + 1
    ic.add(thick(bres(hilt[0] - g + 1, hilt[1] - g + 1, hilt[0] + g - 1, hilt[1] + g - 1), 1)
           | thick(bres(hilt[0] - g + 2, hilt[1] - g + 1, hilt[0] + g, hilt[1] + g - 1), 1), GOLD)
    grip = bres(hilt[0] - 1, hilt[1] + 1, hilt[0] - round(2 * k) - 1, hilt[1] + round(2 * k) + 1)
    ic.add(thick(grip, 1) | {(x, y + 1) for x, y in grip[:-1]}, LEATHER)
    px, py = grip[-1]
    ic.add({(px - 1, py + 1), (px, py + 1), (px - 1, py)}, GOLD, hi={(px - 1, py)})
    # heater shield in front, lower right
    sx0, sy0, sx1, sy1 = round(s * 0.5), round(s * 0.46), s - 2, s - 1
    sh = heater(sx0, sy0, sx1, sy1)
    ic.add(sh, GOLD, hi={(sx0, sy0), (sx0 + 1, sy0)})
    face = shrink(sh)
    ic.add(face, RUBY, outline=False)
    # steel boss in the middle of the face
    bx, by = (sx0 + sx1) // 2, sy0 + round((sy1 - sy0) * 0.38)
    boss = disk(bx + 0.5, by + 0.5, 1.2 * k)
    ic.add(boss, STEEL, hi={(bx, by)} if s > 16 else set(), outline=s > 16)
    return ic.render()


def icon_archer(s):
    k = s / 16
    ic = Icon(s)
    xs = round(s * 0.62)
    depth = round(s * 0.40)
    y0, y1 = 1, s - 2
    # bowstring (behind)
    ic.add({(xs, y) for y in range(y0 + 1, y1)}, STRING, flat=True)
    # bow arc bulging left
    pts = []
    n = 64
    for i in range(n + 1):
        t = i / n
        pts.append((round(xs - depth * math.sin(math.pi * t)), round(y0 + t * (y1 - y0))))
    arc = []
    for a, b in zip(pts, pts[1:]):
        for q in bres(*a, *b):
            if not arc or arc[-1] != q:
                arc.append(q)
    body = thick(arc) if s > 16 else set(arc) | {(x + 1, y) for x, y in arc if abs(y - (y0 + y1) / 2) < s * 0.3}
    ic.add(body, SHAFT, hi={arc[len(arc) // 4]})
    mid = (y0 + y1) // 2
    gx = xs - depth
    ic.add({(gx, mid - 1), (gx + 1, mid - 1), (gx, mid), (gx + 1, mid), (gx, mid + 1), (gx + 1, mid + 1)}
           | ({(gx, mid + 2), (gx + 1, mid + 2), (gx, mid - 2), (gx + 1, mid - 2)} if s > 16 else set()), LEATHER)
    ic.add({(xs, y0), (xs, y1)}, GOLD, flat=True)
    # arrow: diagonal across the bow, head top-right, green fletching bottom-left
    a0, a1 = (round(2 * k), s - 1 - round(2 * k)), (s - 1 - round(3 * k), round(3 * k))
    shaft = bres(*a0, *a1)
    ic.add(set(shaft), STRING if s <= 16 else SHAFT)
    hx, hy = a1
    head = {(hx, hy), (hx + 1, hy - 1), (hx + 1, hy), (hx, hy - 1), (hx - 1, hy - 1), (hx + 1, hy + 1),
            (hx + 2, hy - 2)}
    if s > 16:
        head |= {(hx - 2, hy - 1), (hx + 1, hy + 2), (hx + 2, hy - 1), (hx + 1, hy - 2), (hx + 3, hy - 3)}
    ic.add(head, STEEL, hi={(hx + 1, hy - 1)})
    fx, fy = a0
    fl = {(fx, fy - 1), (fx + 1, fy - 1), (fx + 1, fy), (fx + 1, fy + 1), (fx, fy - 2), (fx + 2, fy + 1)}
    if s > 16:
        fl |= {(fx + 1, fy - 2), (fx + 2, fy + 2), (fx + 2, fy), (fx + 1, fy - 1)}
    ic.add(fl - set(shaft), EMER)
    return ic.render()


def icon_mage(s):
    k = s / 16
    ic = Icon(s)
    r = 2.6 * k
    ocx, ocy = s - 1 - r - 1.2 * k, r + 1.2 * k
    top = (round(ocx - r * 0.7) - 1, round(ocy + r * 0.7) + 1)
    shaft = bres(round(1 * k), s - 2, *top)
    ic.add(thick(shaft), SHAFT, hi={shaft[len(shaft) // 2]})
    # gold band and gold claw holding the orb
    b = shaft[len(shaft) * 2 // 3]
    ic.add({b, (b[0] + 1, b[1]), (b[0] + 1, b[1] - 1), (b[0] + 2, b[1] - 1)}, GOLD)
    orb = disk(ocx, ocy, r)
    claw = set()
    for x, y in disk(ocx, ocy, r + 1.1) - orb:
        if (x + 0.5 - ocx) - (y + 0.5 - ocy) < -r * 0.3:       # lower-left half of the ring
            claw.add((x, y))
    ic.add(claw, GOLD)
    ox, oy = int(ocx - r * 0.4), int(ocy - r * 0.4)
    ic.add(orb, CYAN, hi={(ox, oy)} | ({(ox + 1, oy)} if s > 16 else set()))
    # sparkles (4-point stars, the highlight tone)
    for sx, sy in ((round(ocx - r - 3 * k), round(ocy - r + 1)), (round(ocx + 1), round(ocy + r + 3 * k))):
        pts = {(sx, sy)} | ({(sx - 1, sy), (sx + 1, sy), (sx, sy - 1), (sx, sy + 1)} if s > 16 else set())
        ic.add(pts, CYAN, hi={(sx, sy)}, flat=True)
    return ic.render()


def dagger(s, k, mirror):
    """One dagger, hilt bottom-left, tip top-right (mirrored: hilt bottom-right)."""
    tip = (s - 2, 1)
    guard = (round(5 * k), s - 1 - round(5 * k))
    blade = bres(guard[0] + 1, guard[1] - 1, tip[0] - 1, tip[1] + 1)
    parts = []
    parts.append((thick(blade) | {tip}, STEEL, {blade[len(blade) * 2 // 3]}))
    g = round(1.6 * k) + 1
    parts.append(({(guard[0] - i + 1, guard[1] - i) for i in range(-g + 1, g)} |
                  {(guard[0] - i, guard[1] - i) for i in range(-g + 1, g)}, GOLD, set()))
    grip = bres(guard[0] - 1, guard[1] + 1, guard[0] - round(2 * k) - 1, guard[1] + round(2 * k) + 1)
    parts.append((thick(grip, 1) | {(x + 1, y) for x, y in grip[1:]}, VIOLET, set()))
    px, py = grip[-1]
    parts.append(({(px - 1, py + 1), (px, py + 1), (px - 1, py)}, GOLD, {(px - 1, py)}))
    if mirror:
        f = lambda m: {(s - 1 - x, y) for x, y in m}  # noqa: E731
        parts = [(f(m), r, f(h)) for m, r, h in parts]
    return parts


def icon_thief(s):
    k = s / 16
    ic = Icon(s)
    for mirror in (True, False):
        for m, r, h in dagger(s, k, mirror):
            ic.add(m, r, hi=h)
    return ic.render()


ICONS = {'swordsman': icon_swordsman, 'archer': icon_archer, 'mage': icon_mage, 'thief': icon_thief}


def class_icon(key, large):
    im = ICONS[key](24 if large else 16)
    if not large:
        return im
    out = Image.new('RGBA', (32, 32), (0, 0, 0, 0))
    out.alpha_composite(im, (0, 0))
    return out


# ================================================================= build

def build():
    tex = {}
    tex['pf_logo'] = logo()
    tex['pf_lobby'] = lobby()
    tex['pf_lobby_hover'] = lobby(True)
    bg = scene(tex['pf_logo'], tex['pf_lobby'])
    for r in range(3):
        for c in range(3):
            tex[f'pf_bg_{r}_{c}'] = bg.crop((BACK_X[c], BACK_Y[r], BACK_X[c] + BACK_W[c], BACK_Y[r] + BACK_H[r]))
    tex['pf_panel_0'], tex['pf_panel_1'] = panel()
    tex['pf_card'] = card()
    tex['pf_card_empty'] = card(empty=True)
    tex['pf_btn'] = btn('idle')
    tex['pf_btn_green'] = btn('green')
    tex['pf_btn_hover'] = btn('hover')
    tex['pf_buy'] = buy()
    tex['pf_buy_hover'] = buy(True)
    tex['pf_arrow_up'] = arrow(True)
    tex['pf_arrow_down'] = arrow(False)
    tex['pf_arrow_hover'] = arrow(True, hover=True)
    tex['pf_row'] = row()
    tex['pf_row_hover'] = row(True)
    for kkey in CLASSES:
        tex[f'pf_cls_{kkey}'] = class_icon(kkey, False)
        tex[f'pf_cls_{kkey}_l'] = class_icon(kkey, True)
    return tex


def write(tex):
    os.makedirs(UI, exist_ok=True)
    for name in PF_ORDER:
        im = tex[name]
        w, h = im.size
        assert w % 16 == 0 and h % 16 == 0 and w <= 256 and h <= 256, (name, im.size)
        px = im.load()
        if all(px[w - 1, y][3] == 0 for y in range(h)):
            px[w - 1, h - 1] = (0, 0, 0, 1)  # glyph advance = last non-empty column + 1: pin it to the full width
        im.save(os.path.join(UI, name + '.png'), optimize=True)
    path = os.path.join(PACK, 'font', 'ui.json')
    with open(path, encoding='utf-8') as f:
        providers = json.load(f)['providers']
    keep = [p for p in providers if not str(p.get('file', '')).startswith(('rezusmenu:ui/pf_', 'rezusmenu:ui/char_'))]
    # glyphs of the "Мой персонаж" tab (tools/rpg_char_art.py) come after ours: keep them there
    chars = [p for p in providers if str(p.get('file', '')).startswith('rezusmenu:ui/char_')]
    # bitmap glyphs of the RPG menu first (U+E000 + index), then ours, then the pixel / space providers
    bitmaps = [p for p in keep if p.get('type') == 'bitmap' and p['file'] != 'rezusmenu:ui/pixel.png']
    rest = [p for p in keep if p not in bitmaps]
    assert [p['file'] for p in bitmaps] == [f'rezusmenu:ui/{n}.png' for n in RPG_ORDER], 'ui.json order changed'
    for i, name in enumerate(PF_ORDER):
        w, h = tex[name].size
        bitmaps.append({'type': 'bitmap', 'file': f'rezusmenu:ui/{name}.png', 'ascent': h, 'height': h,
                        'chars': [chr(0xE000 + len(RPG_ORDER) + i)]})
    with open(path, 'w', encoding='utf-8') as f:
        json.dump({'providers': bitmaps + chars + rest}, f, indent=2)
        f.write('\n')
    for i, name in enumerate(PF_ORDER):
        print(f'{0xE000 + len(RPG_ORDER) + i:04X} {name} {tex[name].size[0]}x{tex[name].size[1]}')


def check():
    ramps = [(k, [tuple(c[:3]) for c in v]) for k, v in globals().items()
             if isinstance(v, list) and len(v) == 6 and all(isinstance(c, tuple) for c in v)]
    for name in PF_ORDER:
        shading.check(os.path.join(UI, name + '.png'), ramps=ramps)


if __name__ == '__main__':
    write(build())
    if '--check' in sys.argv:
        check()
