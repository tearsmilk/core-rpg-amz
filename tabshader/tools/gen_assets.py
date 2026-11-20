#!/usr/bin/env python3
"""tabshader - generate the pack sprites, the marker font and the glyph width table.

    python3 -I tabshader/tools/gen_assets.py [client-1.21.11.jar]

Boss bar sprites (182x5, same names as vanilla) carry data, not pictures: R = column (0-181), G = row (0-4),
B = bar colour (0 pink ... 6 white, enum order) or the notch count, A = kind: 250 track, 251 progress,
252 notches over the track, 253 notches over the progress. position_tex_color.fsh draws the bar from that data.

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


def bar(kind, b):
    img = np.zeros((5, 182, 4), np.uint8)
    for y in range(5):
        for x in range(182):
            img[y, x] = (x, y, b, kind)
    return Image.fromarray(img, "RGBA")


def sprites():
    out = os.path.join(ASSETS, "minecraft", "textures", "gui", "sprites", "boss_bar")
    os.makedirs(out, exist_ok=True)
    for i, c in enumerate(COLOURS):
        bar(250, i).save(os.path.join(out, f"{c}_background.png"))
        bar(251, i).save(os.path.join(out, f"{c}_progress.png"))
    for n in NOTCHES:
        bar(252, n).save(os.path.join(out, f"notched_{n}_background.png"))
        bar(253, n).save(os.path.join(out, f"notched_{n}_progress.png"))


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
