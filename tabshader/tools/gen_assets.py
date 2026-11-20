#!/usr/bin/env python3
"""tabshader - generate the pack sprites, the marker font and the glyph width table.

    python3 -I tabshader/tools/gen_assets.py [client-1.21.11.jar]

Boss bar sprites (182x5, same names as vanilla) are a plain pink-crimson picture, so they still look right if
another position_tex_color shader wins. A = kind: 250 track, 251 progress, 252 notches over the track, 253 notches
over the progress. The lowest bits of R, G, B hold the variant (R*4 + G*2 + B): the bar colour (0 pink ... 6 white,
enum order) or the notch index (0: 6, 1: 10, 2: 12, 3: 20). position_tex_color.fsh takes the column and row from
the quad UV and draws the bar itself. Notch sprites only cover the notch columns, the rest is transparent.

Marker font tabshader:hud: U+E000 is an 8x8 glyph of colour (231, 7, 77) that rendertype_text.vsh recognises,
U+E001 is a space of -9, so the marker takes no room.

widths.txt (plugin resource): advance of every character of minecraft:default that is not 6 wide, computed like
the client does it for bitmap providers: rightmost column with alpha + 1, scaled by height / cell height, + 1.
"""
import json
import os
import sys
import zipfile

import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO = os.path.dirname(ROOT)
JAR = os.path.join(REPO, "core-npc", "tools", ".cache", "client-1.21.11.jar")
ASSETS = os.path.join(ROOT, "pack", "assets")
COLOURS = ["pink", "blue", "red", "green", "yellow", "purple", "white"]
NOTCHES = [6, 10, 12, 20]
MARK = (231, 7, 77, 255)


DEEP = np.array((50, 5, 20), float)
WINE = np.array((107, 9, 39), float)
CRIMSON = np.array((199, 18, 65), float)
ROSE = np.array((236, 74, 128), float)
PINK = np.array((255, 143, 189), float)
TINTS = {2: (CRIMSON, ROSE), 5: (np.array((158, 20, 107), float), PINK), 6: (ROSE, np.array((255, 209, 230), float))}


def code(rgb, variant, kind):
    r, g, b = (int(round(min(255.0, max(0.0, v)))) for v in rgb)
    return (r & ~1) | ((variant >> 2) & 1), (g & ~1) | ((variant >> 1) & 1), (b & ~1) | (variant & 1), kind


def bar(kind, variant):
    img = np.zeros((5, 182, 4), np.uint8)
    lo, hi = TINTS.get(variant, (CRIMSON, PINK))
    for y in range(5):
        for x in range(182):
            t = x / 181.0
            if kind == 250:
                if (x in (0, 181)) and y in (0, 4):
                    continue
                c = DEEP + (WINE - DEEP) * (0.4 + 0.3 * t)
                if y in (0, 4) or x in (0, 181):
                    c = c + (ROSE - c) * 0.45
            else:
                if y in (0, 4) or (x == 0 and y in (1, 3)):
                    continue
                c = lo + (hi - lo) * t
                if y == 1:
                    c = c + (PINK - c) * 0.35
            img[y, x] = code(c, variant, kind)
    return Image.fromarray(img, "RGBA")


def notched(kind, index, n):
    img = np.zeros((5, 182, 4), np.uint8)
    seg = 182.0 / n
    cols = set()
    for i in range(1, n):
        pos = seg * i
        for x in range(int(np.floor(pos - 0.75)), int(np.floor(pos + 0.75)) + 1):
            if 0 <= x < 182:
                cols.add(x)
    rows = range(5) if kind == 252 else range(1, 4)
    c = PINK if kind == 252 else DEEP
    for x in cols:
        for y in rows:
            img[y, x] = code(c, index, kind)
    return Image.fromarray(img, "RGBA")


def sprites():
    out = os.path.join(ASSETS, "minecraft", "textures", "gui", "sprites", "boss_bar")
    os.makedirs(out, exist_ok=True)
    for i, c in enumerate(COLOURS):
        bar(250, i).save(os.path.join(out, f"{c}_background.png"))
        bar(251, i).save(os.path.join(out, f"{c}_progress.png"))
    for i, n in enumerate(NOTCHES):
        notched(252, i, n).save(os.path.join(out, f"notched_{n}_background.png"))
        notched(253, i, n).save(os.path.join(out, f"notched_{n}_progress.png"))


def marker():
    tex = os.path.join(ASSETS, "tabshader", "textures", "font")
    os.makedirs(tex, exist_ok=True)
    Image.new("RGBA", (8, 8), MARK).save(os.path.join(tex, "marker.png"))
    font = {"providers": [
        {"type": "bitmap", "file": "tabshader:font/marker.png", "ascent": 7, "height": 8, "chars": [""]},
        {"type": "space", "advances": {"": -9}},
    ]}
    os.makedirs(os.path.join(ASSETS, "tabshader", "font"), exist_ok=True)
    with open(os.path.join(ASSETS, "tabshader", "font", "hud.json"), "w", encoding="utf-8") as f:
        json.dump(font, f, indent=2)
        f.write("\n")


def widths(jar):
    adv = {}
    with zipfile.ZipFile(jar) as z:
        space = json.loads(z.read("assets/minecraft/font/include/space.json"))
        for p in space["providers"]:
            for ch, a in p.get("advances", {}).items():
                adv.setdefault(ord(ch), int(a))
        spec = json.loads(z.read("assets/minecraft/font/include/default.json"))
        for p in spec["providers"]:
            if p.get("type") != "bitmap":
                continue
            img = np.array(Image.open(z.open("assets/minecraft/textures/" + p["file"].split(":")[1])).convert("RGBA"))
            rows = p["chars"]
            ch_h = img.shape[0] // len(rows)
            ch_w = img.shape[1] // len(rows[0])
            scale = p.get("height", 8) / ch_h
            for r, row in enumerate(rows):
                for c, char in enumerate(row):
                    cp = ord(char)
                    if cp == 0 or cp in adv:
                        continue
                    cell = img[r * ch_h:(r + 1) * ch_h, c * ch_w:(c + 1) * ch_w, 3]
                    cols = np.where(cell.max(axis=0) > 0)[0]
                    w = int(cols.max()) + 1 if len(cols) else 0
                    adv[cp] = int(0.5 + w * scale) + 1
    path = os.path.join(ROOT, "src", "main", "resources", "widths.txt")
    with open(path, "w", encoding="utf-8") as f:
        for cp in sorted(adv):
            if adv[cp] != 6:
                f.write(f"{cp:x} {adv[cp]}\n")
    return len(adv)


def main(argv):
    jar = argv[0] if argv else JAR
    sprites()
    marker()
    n = widths(jar)
    print(f"sprites: {len(COLOURS) * 2 + len(NOTCHES) * 2}, marker font, widths: {n} characters")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
