#!/usr/bin/env python3
"""Azerus HUD textures for MythicHUD — slate blue + gold pixel-art UI.

Everything is drawn pixel by pixel (no blur, no anti-aliasing): 1 texture px = 1 GUI px,
the same scale as the old Volya HUD textures (bars 7..10 px tall, icons 9x9, skill icons 16x16).
Light comes from the top-left: highlights on top/left edges, shadows bottom/right.

Run:  python3 -I mythichud-azerus/tools/gen_textures.py
Writes into mythichud-azerus/source-pack/assets/mythichud/textures/assets/azerus/ and the vanilla
hotbar sprites into mythichud-azerus/source-pack/assets/minecraft/textures/gui/sprites/hud/.
"""
import math
import os
from PIL import Image

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
OUT = os.path.join(ROOT, "source-pack/assets/mythichud/textures/assets/azerus")
OUT_MC = os.path.join(ROOT, "source-pack/assets/minecraft/textures/gui/sprites/hud")


def H(c, a=255):
    c = c.lstrip("#")
    return (int(c[0:2], 16), int(c[2:4], 16), int(c[4:6], 16), a)


T = (0, 0, 0, 0)
# ---- palette -------------------------------------------------------------------------------
OUTL = H("10161d")          # outline / gaps
S0 = H("1b2530")            # slate shadow
S1 = H("2b3a4a")            # slate base (panel face)
S2 = H("3c5168")            # slate light-mid
S3 = H("4f6a85")            # slate light (1 px highlight)
S4 = H("6f8ca8")            # slate glint
REC = H("161e28")           # recessed field floor
REC_TOP = H("0d1218")       # shadow under the top edge of a recess
REC_BOT = H("1f2a36")       # bounce light at the bottom of a recess
G_OUT = H("4a2c08")         # gold outline / deepest shadow
G_D = H("a8701a")           # gold dark
G_B = H("e0a52a")           # gold base
G_L = H("ffd25a")           # gold light
G_H = H("fff0b0")           # gold glint

# five-tone ramps (shadow, dark, base, light, highlight) for bar fills
RAMPS = {
    "health":   ["7a1621", "a8232f", "d23b3f", "f0645a", "ffa58c"],
    "armor":    ["454e59", "6c7783", "97a2ad", "c2cbd3", "eef2f5"],
    "mana":     ["11306c", "1f50ae", "2f7de0", "5aa8ff", "b0dcff"],
    "energy":   ["7a5208", "b88412", "e8b425", "ffd85a", "fff3b8"],
    "hunger":   ["6b3410", "a85618", "e07f24", "ffa84a", "ffd8a0"],
    "hungry":   ["3d4a14", "5f6e1e", "86942e", "aebc4a", "d8e39a"],   # hunger effect
    "xp":       ["1d5410", "338c1b", "4fbf2a", "80e848", "cbff9e"],
    "absorb":   ["7a4d0a", "b47d14", "e6ad2a", "ffd660", "fff3b8"],
    "poison":   ["2c4a12", "4b7a1a", "6fa62a", "9fd04a", "d6f2a0"],
    "wither":   ["141418", "24242b", "3a3a44", "5a5a66", "8c8c99"],
    "frozen":   ["2e5f86", "4f8fbf", "7fc0ea", "b4e2ff", "e8f8ff"],
    "burning":  ["7a1a08", "b8361a", "ec5a1c", "ff9a3a", "ffd27a"],
    "air":      ["1d4f7a", "2f78b0", "4aa6e0", "8fd2ff", "d6f0ff"],
}


def ramp(name):
    return [H(c) for c in RAMPS[name]]


def new(w, h):
    return Image.new("RGBA", (w, h), T)


def px(im, x, y, c):
    if 0 <= x < im.width and 0 <= y < im.height:
        im.putpixel((x, y), c)


def rect(im, x0, y0, x1, y1, c):
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            px(im, x, y, c)


def hline(im, x0, x1, y, c):
    rect(im, x0, y, x1, y, c)


def vline(im, x, y0, y1, c):
    rect(im, x, y0, x, y1, c)


def save(im, name, root=OUT):
    os.makedirs(root, exist_ok=True)
    p = os.path.join(root, name)
    im.save(p)
    return p


def from_map(rows, pal):
    h = len(rows)
    w = max(len(r) for r in rows)
    im = new(w, h)
    for y, r in enumerate(rows):
        for x, ch in enumerate(r):
            if ch in pal:
                im.putpixel((x, y), pal[ch])
    return im


# ---- building blocks ---------------------------------------------------------------------------
def panel(im, x, y, w, h, face=S1, hi=S3, sh=S0, glint=S4):
    """Rounded slate panel: 1px dark outline (corners cut), 1px highlight top/left, inner shadow
    bottom/right, flat face; glint pixels on the top-left corner of the highlight."""
    x1, y1 = x + w - 1, y + h - 1
    rect(im, x + 1, y + 1, x1 - 1, y1 - 1, face)
    hline(im, x + 1, x1 - 1, y, OUTL)
    hline(im, x + 1, x1 - 1, y1, OUTL)
    vline(im, x, y + 1, y1 - 1, OUTL)
    vline(im, x1, y + 1, y1 - 1, OUTL)
    # inner corner pixels keep the rounded outline 1px thick diagonally
    for cx, cy in ((x + 1, y + 1), (x1 - 1, y + 1), (x + 1, y1 - 1), (x1 - 1, y1 - 1)):
        px(im, cx, cy, OUTL)
    hline(im, x + 2, x1 - 2, y + 1, hi)
    vline(im, x + 1, y + 2, y1 - 2, hi)
    hline(im, x + 2, x1 - 1, y1 - 1, sh)
    vline(im, x1 - 1, y + 2, y1 - 2, sh)
    px(im, x1 - 1, y1 - 1, OUTL)
    # glint: short brighter run on the lit corner
    hline(im, x + 2, x + 4, y + 1, glint)
    px(im, x + 1, y + 2, glint)


def recess(im, x, y, w, h):
    """Sunken field: dark outline, floor, shadow row under the top edge, bounce light at the bottom,
    and a 1px light lip under/right of the outline so it reads as cut into the panel."""
    x1, y1 = x + w - 1, y + h - 1
    rect(im, x, y, x1, y1, OUTL)
    rect(im, x + 1, y + 1, x1 - 1, y1 - 1, REC)
    hline(im, x + 1, x1 - 1, y + 1, REC_TOP)
    hline(im, x + 2, x1 - 1, y1 - 1, REC_BOT)


def bar_frame(w, h):
    im = new(w, h)
    recess(im, 0, 0, w, h)
    return im


def bar_fill(w, h, r, stripe_every=9):
    """Glossy fill: light top row, base body, dark bottom row; diagonal light stripes (2 px wide,
    stepping up-right) every `stripe_every` px; brighter glint where a stripe meets the top row."""
    sh, dk, b, lt, hi = r
    im = new(w, h)
    for y in range(h):
        if y == 0:
            c = lt
        elif y == h - 1:
            c = dk if h > 3 else b
        else:
            c = b
        hline(im, 0, w - 1, y, c)
    if h >= 5:
        hline(im, 0, w - 1, h - 1, dk)
    # diagonal stripes
    for x0 in range(-h, w + h, stripe_every):
        for y in range(h):
            sx = x0 + (h - 1 - y)
            for k in range(2):
                xx = sx + k
                if 0 <= xx < w:
                    if y == 0:
                        c = hi
                    elif y == h - 1:
                        c = b
                    else:
                        c = lt
                    im.putpixel((xx, y), c)
    # dark terminator pixel at the very right end gives the fill a rounded cap
    for y in range(h):
        if 0 < y < h - 1:
            px(im, w - 1, y, dk)
    px(im, w - 1, 0, b)
    px(im, w - 1, h - 1, sh)
    return im


def brackets(im, x, y, w, h, arm=6, thick=2):
    """Gold L-corner brackets on the four corners of the box (x,y,w,h). Shaded: light on the outer
    top/left edge, base, dark on the inner/bottom side; 1 px dark outline around each arm."""
    x1, y1 = x + w - 1, y + h - 1

    def arm_h(xa, xb, yy, top):
        for xx in range(xa, xb + 1):
            for t in range(thick):
                if top:
                    c = G_L if t == 0 else G_B
                else:
                    c = G_B if t == 0 else G_D
                px(im, xx, yy + t, c)

    def arm_v(xx, ya, yb, left):
        for yy in range(ya, yb + 1):
            for t in range(thick):
                if left:
                    c = G_L if t == 0 else G_B
                else:
                    c = G_B if t == 0 else G_D
                px(im, xx + t, yy, c)

    # top-left
    arm_h(x, x + arm - 1, y, True)
    arm_v(x, y, y + arm - 1, True)
    px(im, x, y, G_H)
    # top-right
    arm_h(x1 - arm + 1, x1, y, True)
    arm_v(x1 - thick + 1, y, y + arm - 1, False)
    px(im, x1 - thick + 1, y, G_L)
    # bottom-left
    arm_h(x, x + arm - 1, y1 - thick + 1, False)
    arm_v(x, y1 - arm + 1, y1, True)
    # bottom-right
    arm_h(x1 - arm + 1, x1, y1 - thick + 1, False)
    arm_v(x1 - thick + 1, y1 - arm + 1, y1, False)
    px(im, x1, y1, G_OUT)
    # outline round the arm ends (dark gold) so brackets read on bright backgrounds
    for (ex, ey) in ((x + arm, y), (x + arm, y + 1), (x, y + arm), (x + 1, y + arm),
                     (x1 - arm, y), (x1 - arm, y + 1), (x1, y + arm), (x1 - 1, y + arm),
                     (x + arm, y1), (x + arm, y1 - 1), (x, y1 - arm), (x + 1, y1 - arm),
                     (x1 - arm, y1), (x1 - arm, y1 - 1), (x1, y1 - arm), (x1 - 1, y1 - arm)):
        if im.getpixel((ex, ey))[3] == 0:
            px(im, ex, ey, G_OUT)


# ---- icons -------------------------------------------------------------------------------------
def pal5(r, extra=None):
    sh, dk, b, lt, hi = r
    p = {"O": OUTL, "s": sh, "d": dk, "b": b, "l": lt, "h": hi}
    if extra:
        p.update(extra)
    return p


HEART = [
    ".OO...OO.",
    "OhlO.OlbO",
    "OlbbObbdO",
    "OlbbbbbdO",
    ".ObbbbdO.",
    "..ObbdO..",
    "...OdO...",
    "....O....",
    ".........",
]
ARMOR = [
    ".OO...OO.",
    "OhlOOOldO",
    "OlbbbbbdO",
    ".OlbbbdO.",
    ".OlbhbdO.",
    ".OlbbbdO.",
    ".ObbbbdO.",
    ".OdddddO.",
    "..OOOOO..",
]
DROP = [
    "....O....",
    "...OlO...",
    "..OlbbO..",
    ".OlhbbdO.",
    ".OlbbbdO.",
    "OlbbbbbdO",
    "ObbbbbddO",
    ".OdbbddO.",
    "..OOOOO..",
]
POTION = [
    "...OOO...",
    "...OwO...",
    "...OgO...",
    "..OgggO..",
    ".OlhbbbO.",
    ".OlbbbdO.",
    ".ObbbbdO.",
    ".OdbbddO.",
    "..OOOOO..",
]
GEM = [
    "..OOOOO..",
    ".OhlllbO.",
    "OhllbbbdO",
    "OsdddddsO",
    ".OlbbbdO.",
    "..OlbdO..",
    "...OdO...",
    "....O....",
    ".........",
]
DRUMSTICK = [
    "....OOO..",
    "...OhlbO.",
    "..OlbbbdO",
    "..OlbbbdO",
    "..ObbbdO.",
    ".OwOddO..",
    "OwwOOO...",
    "OwO......",
    ".O.......",
]
BREAD = [
    ".........",
    "..OOOOO..",
    ".OhlllbO.",
    "OlbdbdbdO",
    "ObbbbbbdO",
    "OdbbbbddO",
    ".OdddddO.",
    "..OOOOO..",
    ".........",
]
BOLT = [
    ".....OOO.",
    "....OlbO.",
    "...OlbO..",
    "..OlbOOO.",
    ".OhbbbbO.",
    ".OOOObO..",
    "...ObO...",
    "..OdO....",
    "..OO.....",
]
BUBBLE = [
    "..OOO..",
    ".OhlbO.",
    "OhlbbdO",
    "OlbbbdO",
    "ObbbddO",
    ".OdddO.",
    "..OOO..",
]


def icon(rows, rname, extra=None):
    return from_map(rows, pal5(ramp(rname), extra))


COIN = [
    "..OOOOO..",
    ".OhlllbO.",
    "OhllllbdO",
    "OlblDbbdO",
    "OlblDbbdO",
    "OlblDbbdO",
    "ObbbbbddO",
    ".OdddddO.",
    "..OOOOO..",
]


def coin(size=9):
    """Gold coin: rim light top-left, dark bottom-right, embossed bar (light left / dark right)."""
    return from_map(COIN, {"O": G_OUT, "h": G_H, "l": G_L, "b": G_B, "d": G_D, "D": G_D})


COMPASS = [
    "...OOOOO...",
    ".OOLLLLBOO.",
    ".OLffRffBO.",
    "OLfffRfffDO",
    "OLffRRRfgDO",
    "OLfffXfggDO",
    "OBffNNNggDO",
    "OBfffNgggDO",
    ".OBffggDDO.",
    ".OODDDDDOO.",
    "...OOOOO...",
]


def compass(size=11):
    """Gold-rimmed compass, red needle north, slate needle south (11x11)."""
    return from_map(COMPASS, {"O": OUTL, "L": G_L, "B": G_B, "D": G_D, "f": H("dbe4ec"), "g": H("aebdcb"),
                              "R": H("e8443a"), "X": OUTL, "N": S2})


def head_frame():
    """38x38 portrait block: gold L-brackets (2px) round a 34x34 slate frame with a 24x24 hole
    for the 8x8 player head drawn by MythicHUD at 3 px per skin pixel."""
    im = new(38, 38)
    x, y, w = 2, 2, 34
    # frame body
    rect(im, x, y, x + w - 1, y + w - 1, OUTL)
    rect(im, x + 1, y + 1, x + w - 2, y + w - 2, S2)
    hline(im, x + 1, x + w - 2, y + 1, S3)
    vline(im, x + 1, y + 1, y + w - 2, S3)
    hline(im, x + 2, x + w - 2, y + w - 2, S0)
    vline(im, x + w - 2, y + 2, y + w - 2, S0)
    hline(im, x + 2, x + 4, y + 1, S4)
    # inner bevel ring
    rect(im, x + 3, y + 3, x + w - 4, y + w - 4, S1)
    # hole for the head + 1px dark recess round it (head = 24x24 at 7..30)
    rect(im, 6, 6, 31, 31, OUTL)
    rect(im, 7, 7, 30, 30, REC)
    hline(im, 7, 30, 7, REC_TOP)
    brackets(im, 0, 0, 38, 38, arm=8, thick=2)
    return im


def player_panel():
    """122x40 info panel right of the portrait: coin field 62x11, gem field 51x11, two bar rows."""
    im = new(122, 40)
    panel(im, 0, 0, 122, 40)
    recess(im, 3, 3, 62, 11)        # coin field   x 3..64
    recess(im, 68, 3, 51, 11)       # gem field    x 68..118
    # small gold rivet between the fields
    px(im, 66, 7, G_L); px(im, 66, 8, G_D)
    return im


def location_panel():
    """140x15 top-centre plate: compass socket on the left, recessed text field."""
    im = new(140, 15)
    panel(im, 0, 0, 140, 15)
    recess(im, 16, 2, 121, 11)
    return im


def bottom_panel(w=81, h=20):
    im = new(w, h)
    panel(im, 0, 0, w, h)
    return im


def level_badge():
    im = new(20, 13)
    panel(im, 0, 0, 20, 13, face=S1)
    # gold trim along the top highlight
    hline(im, 3, 16, 1, G_D)
    hline(im, 4, 15, 1, G_B)
    return im


# ---- hotbar (vanilla sprites) -----------------------------------------------------------------
def slot_box(im, x, y, w, h):
    """Slate slot: dark outline, recessed floor with a light lip, 1px corner cut."""
    x1, y1 = x + w - 1, y + h - 1
    rect(im, x, y, x1, y1, OUTL)
    rect(im, x + 1, y + 1, x1 - 1, y1 - 1, H("202b37"))
    hline(im, x + 1, x1 - 1, y + 1, REC_TOP)
    vline(im, x + 1, y + 1, y1 - 1, REC_TOP)
    hline(im, x + 2, x1 - 1, y1 - 1, S2)
    vline(im, x1 - 1, y + 2, y1 - 1, S2)
    for cx, cy in ((x, y), (x1, y), (x, y1), (x1, y1)):
        px(im, cx, cy, T)


def hotbar():
    """182x22 like vanilla: 9 slots, items at x=3+20i, y=3 (16x16). Slate backing strip with a dark
    outline; each slot is an 18x18 recessed box round the item (x=2+20i..19+20i, y=2..19)."""
    im = new(182, 22)
    panel(im, 0, 0, 182, 22)
    for i in range(9):
        slot_box(im, 2 + 20 * i, 2, 18, 18)
    return im


def hotbar_selection():
    """24x23 like vanilla (drawn at hotbar x-1+20i, y-1). Gold L-brackets round the selected
    18x18 slot (slot is at 3..20 / 3..20 in this sprite), brighter slate rim inside."""
    im = new(24, 23)
    # light rim on the slot edge
    x, y, w = 3, 3, 18
    hline(im, x + 1, x + w - 2, y, S4)
    vline(im, x, y + 1, y + w - 2, S4)
    hline(im, x + 1, x + w - 2, y + w - 1, S3)
    vline(im, x + w - 1, y + 1, y + w - 2, S3)
    brackets(im, 0, 0, 24, 23, arm=6, thick=2)
    return im


def offhand(left=True):
    """29x24 (vanilla offhand sprites; item at x=3 (left) / x=10 (right), y=4)."""
    im = new(29, 24)
    if left:
        panel(im, 0, 1, 22, 22)
        slot_box(im, 2, 3, 18, 18)
    else:
        panel(im, 7, 1, 22, 22)
        slot_box(im, 9, 3, 18, 18)
    return im


# ---- skill bar ---------------------------------------------------------------------------------
def skill_slot(active=False):
    """22x22 skill slot: slate frame (2px) round the 16x16 icon at 3..18; casting mode / active =
    gold L-brackets on the corners and a lit rim."""
    im = new(22, 22)
    rect(im, 0, 0, 21, 21, OUTL)
    rect(im, 1, 1, 20, 20, S2)
    hline(im, 1, 20, 1, S3 if not active else S4)
    vline(im, 1, 1, 20, S3 if not active else S4)
    hline(im, 2, 20, 20, S0)
    vline(im, 20, 2, 20, S0)
    rect(im, 2, 2, 19, 19, OUTL)  # icon well outline (icon covers 3..18)
    rect(im, 3, 3, 18, 18, REC)
    for cx, cy in ((0, 0), (21, 0), (0, 21), (21, 21)):
        px(im, cx, cy, T)
    if active:
        brackets(im, 0, 0, 22, 22, arm=6, thick=2)
    else:
        # muted gold corner ticks so the bar shares the gold accents
        for (a, b) in ((1, 1), (20, 1), (1, 20), (20, 20)):
            px(im, a, b, G_D)
    return im


def skill_backing(n=6, pitch=25, slot=22):
    w = (n - 1) * pitch + slot + 6
    im = new(w, slot + 6)
    panel(im, 0, 0, w, slot + 6)
    return im


def overlay(color, alpha):
    im = new(16, 16)
    c = H(color, alpha)
    rect(im, 0, 0, 15, 15, c)
    return im


def cooldown_overlay():
    """16x16 translucent slate veil with a darker 1px frame (skill on cooldown)."""
    im = overlay("0d1218", 175)
    for i in range(16):
        px(im, i, 0, H("0d1218", 215)); px(im, i, 15, H("0d1218", 215))
        px(im, 0, i, H("0d1218", 215)); px(im, 15, i, H("0d1218", 215))
    return im


def oom_overlay():
    im = overlay("3a5fd0", 95)   # not enough mana: cold blue tint (mana colour)
    for i in range(16):
        px(im, i, 15, H("2f7de0", 200))
    return im


DIGITS = {
    "1": [".#.", "##.", ".#.", ".#.", "###"],
    "2": ["##.", "..#", ".#.", "#..", "###"],
    "3": ["##.", "..#", ".#.", "..#", "##."],
    "4": ["#.#", "#.#", "###", "..#", "..#"],
    "5": ["###", "#..", "##.", "..#", "##."],
    "6": [".##", "#..", "###", "#.#", "###"],
    "7": ["###", "..#", ".#.", ".#.", ".#."],
    "8": ["###", "#.#", "###", "#.#", "###"],
    "9": ["###", "#.#", "###", "..#", "##."],
}


def key_hint(d):
    """7x8 keycap: dark slate cap, gold digit (3x5)."""
    im = new(7, 8)
    rect(im, 0, 0, 6, 7, OUTL)
    rect(im, 1, 1, 5, 6, S1)
    hline(im, 1, 5, 6, S0)
    px(im, 1, 1, S3)
    for y, row in enumerate(DIGITS[d]):
        for x, ch in enumerate(row):
            if ch == "#":
                im.putpixel((2 + x, 1 + y), G_L if y < 2 else G_B)
    return im


def no_skill():
    im = new(16, 16)
    rect(im, 0, 0, 15, 15, REC)
    # faint diagonal hatch, slate light-mid, in clusters
    for i in range(0, 16, 5):
        for k in range(3):
            px(im, i + k, 15 - i - k, H("223040"))
    return im


# ---- main --------------------------------------------------------------------------------------
def main():
    made = []
    # top-left player panel
    made.append(save(head_frame(), "portrait_frame.png"))
    made.append(save(player_panel(), "player_panel.png"))
    made.append(save(bar_frame(106, 10), "bar_frame_wide.png"))
    made.append(save(bar_fill(104, 8, ramp("xp")), "fill_wide_xp.png"))
    made.append(save(bar_fill(104, 8, ramp("mana")), "fill_wide_mana.png"))
    made.append(save(bar_fill(104, 8, ramp("energy")), "fill_wide_energy.png"))
    made.append(save(coin(9), "icon_coin.png"))
    made.append(save(icon(GEM, "wither", {"s": H("1a1426"), "d": H("2e2247"), "b": H("4a3672"),
                                          "l": H("7a5fb0"), "h": H("c8b4ff")}), "icon_gem.png"))
    made.append(save(icon(GEM, "xp"), "icon_gem_green.png"))
    made.append(save(icon(POTION, "mana", {"w": H("8a5a2b"), "g": H("c9d6e2")}), "icon_potion.png"))
    made.append(save(icon(POTION, "energy", {"w": H("8a5a2b"), "g": H("c9d6e2")}), "icon_potion_energy.png"))
    # top-centre location
    made.append(save(location_panel(), "location_panel.png"))
    made.append(save(compass(11), "icon_compass.png"))
    # bottom
    made.append(save(bottom_panel(), "bottom_panel.png"))
    made.append(save(bar_frame(67, 7), "bar_frame.png"))
    for n in ("health", "armor", "mana", "energy", "hunger", "hungry", "absorb", "poison", "wither",
              "frozen", "burning"):
        made.append(save(bar_fill(65, 5, ramp(n), stripe_every=8), "fill_%s.png" % n))
    made.append(save(bar_fill(65, 2, ramp("absorb"), stripe_every=8), "fill_absorb_strip.png"))
    made.append(save(bar_frame(182, 6), "xp_frame.png"))
    made.append(save(bar_fill(180, 4, ramp("xp"), stripe_every=10), "fill_xp.png"))
    made.append(save(bar_frame(56, 5), "air_frame.png"))
    made.append(save(bar_fill(54, 3, ramp("air"), stripe_every=7), "fill_air.png"))
    made.append(save(level_badge(), "level_badge.png"))
    made.append(save(icon(HEART, "health"), "icon_heart.png"))
    made.append(save(icon(HEART, "poison"), "icon_heart_poison.png"))
    made.append(save(icon(HEART, "wither"), "icon_heart_wither.png"))
    made.append(save(icon(HEART, "frozen"), "icon_heart_frozen.png"))
    made.append(save(icon(HEART, "absorb"), "icon_heart_absorb.png"))
    made.append(save(icon(HEART, "burning"), "icon_heart_burning.png"))
    made.append(save(icon(ARMOR, "armor"), "icon_armor.png"))
    made.append(save(icon(DROP, "mana"), "icon_mana.png"))
    made.append(save(icon(BOLT, "energy"), "icon_energy.png"))
    made.append(save(icon(DRUMSTICK, "hunger", {"w": H("e8e2d4")}), "icon_food.png"))
    made.append(save(icon(DRUMSTICK, "hungry", {"w": H("c8cfa8")}), "icon_food_hunger.png"))
    made.append(save(icon(BREAD, "hunger"), "icon_food2.png"))
    made.append(save(icon(BREAD, "hungry"), "icon_food2_hunger.png"))
    made.append(save(icon(BUBBLE, "air"), "icon_air.png"))
    # skill bar
    made.append(save(skill_slot(False), "skill_slot.png"))
    made.append(save(skill_slot(True), "skill_slot_active.png"))
    made.append(save(skill_backing(), "skill_backing.png"))
    made.append(save(cooldown_overlay(), "skill_cooldown.png"))
    made.append(save(oom_overlay(), "skill_oom.png"))
    made.append(save(no_skill(), "skill_empty.png"))
    for d in "1234567":
        made.append(save(key_hint(d), "key_%s.png" % d))
    # vanilla hotbar sprites
    made.append(save(hotbar(), "hotbar.png", OUT_MC))
    made.append(save(hotbar_selection(), "hotbar_selection.png", OUT_MC))
    made.append(save(offhand(True), "hotbar_offhand_left.png", OUT_MC))
    made.append(save(offhand(False), "hotbar_offhand_right.png", OUT_MC))
    for p in made:
        print(os.path.relpath(p, ROOT))
    print(len(made), "textures")


if __name__ == "__main__":
    main()
