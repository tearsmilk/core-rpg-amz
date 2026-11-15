"""Art of the profile-selection screen (rezusmenu "profiles"): pixel textures pf_* for the font rezusmenu:ui.

    python3 tools/profiles_art.py            # writes pack/assets/rezusmenu/textures/ui/pf_*.png and font/ui.json

Uses the minecraft-nexo-gui skill scripts (ramp5 colour ramps, volumetric lettering) — set SKILL_SCRIPTS if they live
elsewhere. Canvas = 704 x 400 px (the same canvas as the RPG menu). Every texture is a multiple of 16 px, <= 256 px
per side (font atlas), drawn 1:1; overlays keep their content in the top-left corner. The glyph order of font/ui.json
is PanelMath.ORDER: the 28 glyphs of the RPG menu first (left untouched), then PF_ORDER below.
"""
import json
import math
import os
import random
import sys

from PIL import Image, ImageDraw

SKILL = os.environ.get('SKILL_SCRIPTS', '/root/.claude/skills/synced/c3b8cbb8-56f6-46e0-b1a5-a4a6350f4608_'
                       '9bcc0299-39b2-4127-bbdc-0f93b08ab14a/minecraft-nexo-gui/scripts')
sys.path.insert(0, SKILL)
import lettering  # noqa: E402
import shading  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PACK = os.path.join(ROOT, 'pack', 'assets', 'rezusmenu')
UI = os.path.join(PACK, 'textures', 'ui')

# Keep in sync with PanelMath.ORDER (appended after the RPG-menu glyphs).
RPG_ORDER = ['panel_0', 'panel_1', 'panel_2', 'panel_3', 'tab_active', 'tab_hover', 'slot_hover', 'slot_selected',
             'button_hover', 'bar_exp', 'bar_hp', 'bar_mana', 'cursor', 'bar_energy', 'skills_bg_0', 'skills_bg_1',
             'skill_hover', 'skill_locked', 'cursor_mirror'] + [f'back_{r}_{c}' for r in range(3) for c in range(3)]
PF_ORDER = [f'pf_bg_{r}_{c}' for r in range(3) for c in range(3)] + [
    'pf_logo', 'pf_lobby', 'pf_lobby_hover', 'pf_panel_0', 'pf_panel_1', 'pf_card', 'pf_card_empty',
    'pf_btn', 'pf_btn_green', 'pf_btn_hover', 'pf_buy', 'pf_buy_hover', 'pf_arrow_up', 'pf_arrow_down',
    'pf_arrow_hover', 'pf_row', 'pf_row_hover']

BACK_X, BACK_W = [0, 256, 512], [256, 256, 192]
BACK_Y, BACK_H = [0, 144, 288], [144, 144, 112]
# Where the character stands on the canvas (ProfilesScreen.CHAR_*): feet centre.
FEET_X, FEET_Y = 300, 346


def rgba(c, a=255):
    return (c[0], c[1], c[2], a)


def ramp(hexc):
    """ramp5: [outline, shadow, dark mid, base, light mid, highlight]."""
    return shading.ramp5(hexc)


def rect(d, box, c):
    x0, y0, x1, y1 = box
    if x1 > x0 and y1 > y0:
        d.rectangle((x0, y0, x1 - 1, y1 - 1), fill=c)


# ----------------------------------------------------------------- background scene (drawn at 352 x 200, x2)

def scene():
    W, H = 352, 200
    rnd = random.Random(7)
    im = Image.new('RGBA', (W, H))
    d = ImageDraw.Draw(im)
    sky = [(118, 176, 240), (132, 188, 244), (148, 199, 247), (166, 210, 249), (186, 222, 251), (204, 232, 252)]
    horizon = 104
    band = horizon // len(sky)
    for i, c in enumerate(sky):
        rect(d, (0, i * band, W, horizon if i == len(sky) - 1 else (i + 1) * band), rgba(c))
    # blocky Minecraft clouds: flat light slabs with a shaded underside
    for cx, cy, w in ((40, 14, 46), (120, 26, 30), (196, 10, 54), (270, 30, 36), (318, 16, 28), (80, 40, 22)):
        rect(d, (cx, cy, cx + w, cy + 6), rgba((250, 252, 255)))
        rect(d, (cx + 4, cy - 3, cx + w - 8, cy), rgba((250, 252, 255)))
        rect(d, (cx, cy + 6, cx + w, cy + 8), rgba((214, 226, 242)))
    # far hills (two layers), lighter = farther
    for col, base, amp, period, ph in (((150, 186, 206), 96, 12, 61.0, 0.4), ((104, 150, 122), 106, 8, 37.0, 1.9)):
        for x in range(W):
            h = base - int(amp * (0.6 * math.sin(x / period * 6.283 + ph) + 0.4 * math.sin(x / (period * 0.43) + ph * 2)))
            rect(d, (x, h, x + 1, horizon + 8), rgba(col))
    # grass field with stepped depth bands
    grass = [(98, 150, 64), (90, 142, 58), (84, 134, 54), (76, 124, 50), (68, 114, 46)]
    gy = horizon + 4
    rows = [gy, gy + 10, gy + 24, gy + 42, gy + 64, H]
    for i, c in enumerate(grass):
        rect(d, (0, rows[i], W, rows[i + 1]), rgba(c))
    for _ in range(900):                                  # grass tufts: lighter / darker 1x2 strokes
        x, y = rnd.randrange(W), rnd.randrange(gy, H)
        k = rnd.choice((14, -12))
        base = im.getpixel((x, y))
        c = tuple(max(0, min(255, v + k)) for v in base[:3])
        rect(d, (x, y, x + 1, y + 2), rgba(c))
    # dirt path from the bottom to the character
    fx, fy = FEET_X // 2, FEET_Y // 2
    dirt = [(150, 108, 70), (136, 96, 62), (122, 86, 56)]
    for y in range(gy + 18, H):
        t = (y - (gy + 18)) / (H - gy - 18)
        half = int(6 + t * 40)
        cx = int(fx + (1 - t) * 0 + math.sin(y / 9.0) * 3 * t)
        for x in range(cx - half, cx + half):
            e = abs(x - cx) / max(1, half)
            c = dirt[0] if e < 0.45 else dirt[1] if e < 0.8 else dirt[2]
            if 0 <= x < W:
                im.putpixel((x, y), rgba(c))
    for _ in range(140):                                  # pebbles on the path
        y = rnd.randrange(gy + 22, H)
        t = (y - (gy + 18)) / (H - gy - 18)
        x = fx + rnd.randint(-int(4 + t * 34), int(4 + t * 34))
        if im.getpixel((x, y))[:3] in dirt:
            rect(d, (x, y, x + 2, y + 1), rgba((176, 136, 96) if rnd.random() < 0.5 else (104, 74, 48)))
    # cherry trees (left behind the logo, right behind the panel) and a few bushes
    def tree(tx, ty, size, seed):
        r2 = random.Random(seed)
        trunk = ramp('#6b4a33')
        rect(d, (tx - 3, ty - size, tx + 3, ty), rgba(trunk[3]))
        rect(d, (tx - 3, ty - size, tx - 1, ty), rgba(trunk[4]))
        rect(d, (tx + 2, ty - size, tx + 3, ty), rgba(trunk[2]))
        pink = ramp('#e892c8')
        blobs = [(tx + r2.randint(-size, size), ty - size - r2.randint(0, size // 2), r2.randint(size // 3, size // 2 + 4))
                 for _ in range(7)]
        for bx, by, br in blobs:                            # dark under-layer first (self-shadow), then lit tops
            d.ellipse((bx - br, by - br + 3, bx + br, by + br + 3), fill=rgba(pink[2]))
        for bx, by, br in blobs:
            d.ellipse((bx - br, by - br, bx + br, by + br - 2), fill=rgba(pink[3]))
        for bx, by, br in blobs:
            d.ellipse((bx - br + 2, by - br + 1, bx + br // 3, by - br // 3), fill=rgba(pink[4]))
        for _ in range(size * 3):                           # petals
            px, py = tx + r2.randint(-size - 6, size + 6), ty - r2.randint(size // 2, size * 2)
            if 0 <= px < W and 0 <= py < H and im.getpixel((px, py))[:3] in (pink[3], pink[2]):
                im.putpixel((px, py), rgba(pink[5]))
    tree(24, 136, 22, 1)
    tree(70, 116, 14, 2)
    tree(300, 128, 24, 3)
    tree(250, 112, 12, 4)
    tree(340, 140, 18, 5)
    leaf = ramp('#4f8a3a')
    for bx, by, br in ((110, 128, 8), (200, 124, 7), (40, 170, 10), (330, 176, 9)):
        d.ellipse((bx - br, by - br // 2, bx + br, by + br // 2 + 2), fill=rgba(leaf[2]))
        d.ellipse((bx - br + 1, by - br // 2 - 1, bx + br - 2, by + br // 2 - 1), fill=rgba(leaf[3]))
        d.ellipse((bx - br + 2, by - br // 2 - 1, bx, by - 1), fill=rgba(leaf[4]))
    # contact shadow where the character stands (hard edge, one darker tone)
    d.ellipse((fx - 14, fy - 3, fx + 14, fy + 3), fill=rgba((92, 64, 42)))
    d.ellipse((fx - 9, fy - 2, fx + 9, fy + 2), fill=rgba((78, 54, 36)))
    # stepped vignette: one darker band round the edges
    px = im.load()
    for y in range(H):
        for x in range(W):
            e = min(x, W - 1 - x, y, H - 1 - y)
            if e < 6:
                r, g, b, a = px[x, y]
                k = 0.86 if e < 3 else 0.93
                px[x, y] = (int(r * k), int(g * k), int(b * k), a)
    return im.resize((W * 2, H * 2), Image.NEAREST)


# ----------------------------------------------------------------- logo: wooden sign "AZERUS" / "QXCER"

def plank_board(w, h, wood='#a8763f', seed=1):
    r = ramp(wood)
    im = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    rnd = random.Random(seed)
    rect(d, (0, 0, w, h), rgba(r[0]))                     # outline
    rect(d, (1, 1, w - 1, h - 1), rgba(r[2]))             # frame (dark mid)
    rect(d, (1, 1, w - 1, 2), rgba(r[4]))                 # lit top edge of the frame
    rect(d, (1, 1, 2, h - 1), rgba(r[4]))
    rect(d, (4, 4, w - 4, h - 4), rgba(r[1]))             # recess shadow
    rect(d, (5, 5, w - 5, h - 5), rgba(r[3]))             # inner boards
    board = 8
    for y in range(5 + board, h - 5, board):              # board seams: dark gap + lit lip below
        rect(d, (5, y, w - 5, y + 1), rgba(r[1]))
        rect(d, (5, y + 1, w - 5, y + 2), rgba(r[4]))
    for _ in range(w * h // 40):                          # grain: short horizontal streaks
        x, y = rnd.randrange(6, w - 10), rnd.randrange(6, h - 6)
        if im.getpixel((x, y))[:3] == r[3][:3]:
            rect(d, (x, y, x + rnd.randint(3, 7), y + 1), rgba(r[2] if rnd.random() < 0.6 else r[4]))
    for nx, ny in ((2, 2), (w - 4, 2), (2, h - 4), (w - 4, h - 4)):   # nails in the frame corners
        rect(d, (nx, ny, nx + 2, ny + 2), rgba((70, 70, 78)))
        im.putpixel((nx, ny), rgba((170, 170, 180)))
    return im


def logo():
    W, H = 192, 112
    im = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    green = ramp('#62c23c')
    big = lettering.volume('AZERUS', green, scale=3, depth=3)
    small = lettering.volume('QXCER', green, scale=2, depth=2)
    # lower plank first (it hangs behind the main board), then a short post and the main board
    lw = small.width + 22
    lower = plank_board(lw, 34, '#9c6a38', seed=4)
    lower.alpha_composite(small, ((lw - small.width) // 2, (34 - small.height) // 2 + 1))
    im.alpha_composite(lower, ((W - lw) // 2, 70))
    post = ramp('#6b4a33')
    for x0 in ((W - lw) // 2 + 8, (W + lw) // 2 - 12):    # ropes / straps joining the boards
        d = ImageDraw.Draw(im)
        rect(d, (x0, 62, x0 + 4, 72), rgba(post[0]))
        rect(d, (x0 + 1, 62, x0 + 3, 72), rgba(post[3]))
    main = plank_board(W - 8, 64, '#b27d43', seed=2)
    main.alpha_composite(big, ((main.width - big.width) // 2, (64 - big.height) // 2 + 1))
    im.alpha_composite(main, (4, 2))
    return im


# ----------------------------------------------------------------- panel, cards, buttons

PANEL_BG = (16, 18, 24, 205)
PANEL_HEAD = (26, 29, 38, 225)
EDGE = (78, 86, 104, 235)
EDGE_DARK = (6, 7, 10, 235)
CARD_BG = (10, 11, 16, 220)
CARD_EDGE = (54, 60, 74, 240)


def panel():
    W, H = 224, 320
    im = Image.new('RGBA', (W, H), PANEL_BG)
    d = ImageDraw.Draw(im)
    rect(d, (0, 0, W, 30), PANEL_HEAD)
    rect(d, (0, 30, W, 31), EDGE)                          # header divider
    rect(d, (0, 31, W, 32), EDGE_DARK)
    # frame: lit top/left, dark bottom/right (light from top-left), 1 px outline
    rect(d, (0, 0, W, 1), EDGE)
    rect(d, (0, 0, 1, H), EDGE)
    rect(d, (W - 1, 0, W, H), EDGE_DARK)
    rect(d, (0, H - 1, W, H), EDGE_DARK)
    rect(d, (1, 1, W - 1, 2), (110, 120, 142, 200))
    return im.crop((0, 0, W, 160)), im.crop((0, 160, W, 320))


def icon(d, x, y, rows, pal):
    for j, row in enumerate(rows):
        for i, ch in enumerate(row):
            if ch != '.':
                rect(d, (x + i, y + j, x + i + 1, y + j + 1), rgba(pal[ch]))


ICON_LEVEL = ['..aaa..', '.abcba.', 'abccdba', 'abcddba', 'abbddba', '.abbba.', '..aaa..']
ICON_PIN = ['.aaaaa.', 'abccdba', 'abcddba', '.abdba.', '.abba..', '..aa...', '..a....']
ICON_SWORD = ['.....aa', '....acd', '...acda', 'a.acda.', '.aada..', '.cba...', 'b..a...']
ICON_FEATHER = ['....aaa', '...accd', '..acdda', '.acdda.', '.adda..', 'a.aa...', 'a......']
ICON_HEART = ['.aa.aa.', 'acdacda', 'accdcba', 'abccbba', '.abbba.', '..aba..', '...a...']


def card(empty=False):
    W, H = 208, 96
    im = Image.new('RGBA', (W, H), CARD_BG)
    d = ImageDraw.Draw(im)
    rect(d, (0, 0, W, 1), CARD_EDGE)
    rect(d, (0, 0, 1, H), CARD_EDGE)
    rect(d, (W - 1, 0, W, H), EDGE_DARK)
    rect(d, (0, H - 1, W, H), EDGE_DARK)
    if empty:
        dash = (70, 78, 96, 230)
        for x in range(6, W - 6, 6):                       # dashed inner frame
            rect(d, (x, 6, x + 3, 7), dash)
            rect(d, (x, H - 7, x + 3, H - 6), dash)
        for y in range(6, H - 6, 6):
            rect(d, (6, y, 7, y + 3), dash)
            rect(d, (W - 7, y, W - 6, y + 3), dash)
        g = ramp('#4fb35a')                                # big "+" in the middle-left
        cx, cy = 40, 48
        rect(d, (cx - 3, cy - 13, cx + 4, cy + 14), rgba(g[0]))
        rect(d, (cx - 13, cy - 3, cx + 14, cy + 4), rgba(g[0]))
        rect(d, (cx - 2, cy - 12, cx + 3, cy + 13), rgba(g[3]))
        rect(d, (cx - 12, cy - 2, cx + 13, cy + 3), rgba(g[3]))
        rect(d, (cx - 2, cy - 12, cx, cy + 13), rgba(g[4]))
        rect(d, (cx - 12, cy - 2, cx + 13, cy), rgba(g[4]))
        return im
    # bust well: x 4..68, y 4..92, stepped darker toward the bottom, inner shadow top/left
    well = [(30, 34, 44, 235), (26, 30, 39, 235), (22, 25, 33, 235), (19, 21, 28, 235)]
    for i, c in enumerate(well):
        rect(d, (4, 4 + i * 22, 68, 4 + (i + 1) * 22), c)
    rect(d, (4, 4, 68, 5), EDGE_DARK)
    rect(d, (4, 4, 5, 92), EDGE_DARK)
    rect(d, (4, 91, 68, 92), (64, 70, 86, 235))
    # info blocks right of the well: class / level / location, then stats
    rect(d, (72, 4, 204, 46), (20, 22, 30, 225))
    rect(d, (72, 50, 204, 92), (20, 22, 30, 225))
    rect(d, (72, 47, 204, 48), CARD_EDGE)
    gold = ramp('#e2b341')
    green = {'a': (16, 60, 14), 'b': (46, 140, 40), 'c': (110, 214, 84), 'd': (210, 255, 170)}
    red = {'a': (70, 10, 14), 'b': (170, 30, 40), 'c': (232, 70, 70), 'd': (255, 190, 180)}
    steel = {'a': (30, 30, 40), 'b': (110, 90, 60), 'c': (200, 206, 220), 'd': (250, 252, 255)}
    sky = {'a': (20, 40, 70), 'b': (60, 110, 160), 'c': (150, 200, 240), 'd': (240, 250, 255)}
    heart = {'a': (70, 10, 30), 'b': (160, 30, 60), 'c': (230, 70, 100), 'd': (255, 200, 210)}
    icon(d, 76, 18, ICON_LEVEL, green)                    # row 2: level   (text y 16..28)
    icon(d, 76, 32, ICON_PIN, red)                        # row 3: location (y 30..42)
    icon(d, 76, 54, ICON_SWORD, steel)                    # stats rows y 52..64, 66..78, 80..92
    icon(d, 76, 68, ICON_FEATHER, sky)
    icon(d, 76, 82, ICON_HEART, heart)
    del gold
    return im


def button(color, h=20, W=208):
    im = Image.new('RGBA', (W, 32), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    r = ramp(color)
    rect(d, (0, 0, W, h), rgba(r[0]))
    rect(d, (1, 1, W - 1, h - 1), rgba(r[3]))
    rect(d, (1, 1, W - 1, 2), rgba(r[5]))                 # top highlight
    rect(d, (1, 2, W - 1, 3), rgba(r[4]))
    rect(d, (1, 1, 2, h - 1), rgba(r[4]))
    rect(d, (1, h - 3, W - 1, h - 1), rgba(r[2]))         # bottom shade (2 px lip)
    rect(d, (W - 2, 2, W - 1, h - 1), rgba(r[2]))
    return im


def hover(w, h, W=None, H=None):
    W = W or w
    H = H or h
    im = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    rect(d, (0, 0, w, h), (255, 255, 255, 46))
    for box in ((0, 0, w, 1), (0, h - 1, w, h), (0, 0, 1, h), (w - 1, 0, w, h)):
        rect(d, box, (255, 255, 255, 190))
    return im


def buy():
    im = Image.new('RGBA', (208, 32), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    rect(d, (0, 0, 208, 24), EDGE_DARK)
    rect(d, (1, 1, 207, 23), (12, 13, 18, 235))
    rect(d, (1, 1, 207, 2), CARD_EDGE)
    rect(d, (1, 1, 2, 23), CARD_EDGE)
    return im


def lobby():
    im = button('#c8384a', h=22, W=80)
    out = Image.new('RGBA', (80, 32), (0, 0, 0, 0))
    out.alpha_composite(im.crop((0, 0, 80, 32)))
    return out


def arrow(up):
    im = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    rect(d, (0, 0, 16, 16), EDGE_DARK)
    rect(d, (1, 1, 15, 15), (26, 29, 38, 235))
    rect(d, (1, 1, 15, 2), EDGE)
    for i in range(5):                                     # 5-row triangle
        y = (5 + i) if up else (10 - i)
        rect(d, (8 - i - 1, y, 8 + i + 1, y + 1), (236, 240, 248, 255))
    return im


def row():
    im = Image.new('RGBA', (208, 64), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    rect(d, (0, 0, 208, 56), CARD_BG)
    rect(d, (0, 0, 208, 1), CARD_EDGE)
    rect(d, (0, 0, 1, 56), CARD_EDGE)
    rect(d, (207, 0, 208, 56), EDGE_DARK)
    rect(d, (0, 55, 208, 56), EDGE_DARK)
    well = (30, 34, 44, 235)
    rect(d, (6, 8, 46, 48), well)                          # icon well 40 x 40
    rect(d, (6, 8, 46, 9), EDGE_DARK)
    rect(d, (6, 8, 7, 48), EDGE_DARK)
    rect(d, (6, 47, 46, 48), (64, 70, 86, 235))
    return im


def build():
    tex = {}
    bg = scene()
    for r in range(3):
        for c in range(3):
            tex[f'pf_bg_{r}_{c}'] = bg.crop((BACK_X[c], BACK_Y[r], BACK_X[c] + BACK_W[c], BACK_Y[r] + BACK_H[r]))
    tex['pf_logo'] = logo()
    tex['pf_lobby'] = lobby()
    tex['pf_lobby_hover'] = hover(80, 22, 80, 32)
    tex['pf_panel_0'], tex['pf_panel_1'] = panel()
    tex['pf_card'] = card()
    tex['pf_card_empty'] = card(empty=True)
    tex['pf_btn'] = button('#3a74d8')
    tex['pf_btn_green'] = button('#3f9f4a')
    tex['pf_btn_hover'] = hover(208, 20, 208, 32)
    tex['pf_buy'] = buy()
    tex['pf_buy_hover'] = hover(208, 24, 208, 32)
    tex['pf_arrow_up'] = arrow(True)
    tex['pf_arrow_down'] = arrow(False)
    tex['pf_arrow_hover'] = hover(16, 16)
    tex['pf_row'] = row()
    tex['pf_row_hover'] = hover(208, 56, 208, 64)
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
    keep = [p for p in providers if not str(p.get('file', '')).startswith('rezusmenu:ui/pf_')]
    # bitmap glyphs of the RPG menu first (U+E000 + index), then ours, then the pixel / space providers
    bitmaps = [p for p in keep if p.get('type') == 'bitmap' and p['file'] != 'rezusmenu:ui/pixel.png']
    rest = [p for p in keep if p not in bitmaps]
    assert [p['file'] for p in bitmaps] == [f'rezusmenu:ui/{n}.png' for n in RPG_ORDER], 'ui.json order changed'
    for i, name in enumerate(PF_ORDER):
        w, h = tex[name].size
        bitmaps.append({'type': 'bitmap', 'file': f'rezusmenu:ui/{name}.png', 'ascent': h, 'height': h,
                        'chars': [chr(0xE000 + len(RPG_ORDER) + i)]})
    with open(path, 'w', encoding='utf-8') as f:
        json.dump({'providers': bitmaps + rest}, f, indent=2)
        f.write('\n')
    for i, name in enumerate(PF_ORDER):
        print(f'{0xE000 + len(RPG_ORDER) + i:04X} {name} {tex[name].size[0]}x{tex[name].size[1]}')


if __name__ == '__main__':
    write(build())
