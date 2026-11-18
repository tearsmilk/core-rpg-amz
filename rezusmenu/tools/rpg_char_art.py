"""Art of the RPG-menu tab "Мой персонаж": overlays char_left, char_bg_0, char_bg_1 for the font rezusmenu:ui.

    python3 tools/rpg_char_art.py            # writes pack/assets/rezusmenu/textures/ui/char_*.png and font/ui.json

Same kit as the rest of the menu (tools/profiles_art.py: walnut + gold, emerald/cyan jewels, green banners, stone dais).

* char_left (192 x 144) lies at panel (14, 66) on the "Мой персонаж" tab and covers the equipment wells of the left
  section (4 armour wells, 3 jewel wells + off-hand, totem and artifact under the arch): green banners hang where the
  well columns were, two stone plinths stand under the arch pillars. The arch, the portal and the skin stay visible
  (transparent middle); the bars below (y >= 204) are untouched.
* char_bg_0 / char_bg_1 (144 x 192 each) lie at panel (212, 52) / (356, 52): title plate, two framed columns
  ("Персонаж" | "Характеристики") with a header band and 12 striped rows (y 94 + 12 * i, panel px) - RpgScreen puts the
  texts there. The Calibrate / Close buttons of the panel below (y 244) stay visible.

Glyphs are appended to font/ui.json after the RPG and profile glyphs (PanelMath.ORDER); profiles_art.py keeps them.
"""
import json
import os
import sys

from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import profiles_art as K  # noqa: E402  (kit + paths; importing it draws nothing)

CHAR_ORDER = ['char_left', 'char_bg_0', 'char_bg_1']
LEFT_X, LEFT_Y = 14, 66          # panel px of char_left
BG_X, BG_Y = 212, 52             # panel px of char_bg_0 (char_bg_1 at +144)

# Panel px of the wells the left overlay hides (top-left corner, 28 x 28), from RpgScreen.
ARMOR = [(18, 74), (18, 106), (18, 138), (18, 170)]
JEWEL = [(172, 74), (172, 106), (172, 138), (172, 170)]
PLINTH = [(52, 168), (132, 168)]


def panel_image():
    p = Image.new('RGBA', (512, 288))
    for i in range(4):
        p.alpha_composite(Image.open(os.path.join(K.UI, f'panel_{i}.png')).convert('RGBA'), ((i % 2) * 256, (i // 2) * 144))
    return p


def char_left():
    im = Image.new('RGBA', (192, 144), (0, 0, 0, 0))
    pen = K.Pen(im)
    panel = panel_image()

    def L(x, y):
        return x - LEFT_X, y - LEFT_Y

    # well columns -> section floor (the same walnut as around them) with a soft lip at the edges
    for x0, x1 in ((16, 50), (166, 203)):
        a, b = L(x0, 68)
        c, d = L(x1, 202)
        K.grain(pen, a, b, c, d, K.PANEL, seed=x0 + 7, dens=0.03, lo=2, hi=4)
    # keep the panel's own pixels where nothing covered a well (seamless join at the column edges)
    for y in range(68, 202):
        for x in (16, 49, 166, 202):
            lx, ly = L(x, y)
            pen.p(lx, ly, panel.getpixel((x, y)))
    # hanging banners on the columns
    for bx in (19, 172):
        lx, ly = L(bx, 70)
        K.banner(pen, lx, ly, 26, 118, seed=bx)
    # stone plinths under the arch pillars, where the totem / artifact wells were
    for (x, y) in PLINTH:
        lx, ly = L(x - 1, y - 1)
        w, h = 30, 31
        pen.rect(lx, ly, lx + w, ly + h, K.DAIS[3])
        for yy in range(ly + 4, ly + h - 1, 7):           # stone courses
            pen.hl(lx + 1, lx + w - 1, yy, K.DAIS[1])
            pen.hl(lx + 1, lx + w - 1, yy + 1, K.DAIS[4])
        for i, yy in enumerate(range(ly + 4, ly + h - 1, 7)):  # staggered joints
            jx = lx + (9 if i % 2 else 19)
            pen.vl(jx, yy + 2, min(ly + h - 1, yy + 7), K.DAIS[1])
        pen.hl(lx, lx + w, ly, K.GOLD[4])                  # gold cap
        pen.hl(lx, lx + w, ly + 1, K.GOLD[3])
        pen.hl(lx, lx + w, ly + 2, K.GOLD[2])
        pen.ring(lx, ly, lx + w, ly + h, K.OUT)
        pen.vl(lx + 1, ly + 3, ly + h - 1, K.DAIS[4])
        pen.vl(lx + w - 2, ly + 3, ly + h - 1, K.DAIS[1])
        K.gem(pen, lx + w // 2 - 3, ly + 13, K.EMER, big=True)
    return im


def char_bg():
    im = Image.new('RGBA', (288, 192), (0, 0, 0, 0))
    pen = K.Pen(im)
    K.grain(pen, 0, 0, 288, 192, K.PANEL, seed=41, dens=0.03, lo=2, hi=4)
    K.plate(pen, 4, 2, 284, 22, seed=12)
    for x0, x1 in ((2, 144), (146, 286)):
        K.section(pen, x0, 24, x1, 191, seed=x0 + 3)
        K.inset(pen, x0 + 6, 27, x1 - 6, 39)
        # gems at the ends of the header band
        K.gem(pen, x0 + 8, 30, K.CYAN)
        K.gem(pen, x1 - 13, 30, K.CYAN)
        # striped rows: y 94 + 12 * i (panel) -> 42 + 12 * i here
        for i in range(12):
            y = 42 + 12 * i
            if i % 2 == 1:
                pen.rect(x0 + 5, y, x1 - 5, y + 12, K.PANEL[2])
            pen.hl(x0 + 8, x1 - 8, y + 11, K.PANEL[1] if i % 2 == 0 else K.PANEL[2])
    return im


def build():
    bg = char_bg()
    return {'char_left': char_left(), 'char_bg_0': bg.crop((0, 0, 144, 192)), 'char_bg_1': bg.crop((144, 0, 288, 192))}


def write(tex):
    for name in CHAR_ORDER:
        im = tex[name]
        w, h = im.size
        assert w % 16 == 0 and h % 16 == 0 and w <= 256 and h <= 256, (name, im.size)
        px = im.load()
        if all(px[w - 1, y][3] == 0 for y in range(h)):
            px[w - 1, h - 1] = (0, 0, 0, 1)  # glyph advance = last non-empty column + 1: pin it to the full width
        im.save(os.path.join(K.UI, name + '.png'), optimize=True)
    path = os.path.join(K.PACK, 'font', 'ui.json')
    with open(path, encoding='utf-8') as f:
        providers = json.load(f)['providers']
    keep = [p for p in providers if not str(p.get('file', '')).startswith('rezusmenu:ui/char_')]
    bitmaps = [p for p in keep if p.get('type') == 'bitmap' and p['file'] != 'rezusmenu:ui/pixel.png']
    rest = [p for p in keep if p not in bitmaps]
    base = K.RPG_ORDER + K.PF_ORDER
    assert [p['file'] for p in bitmaps] == [f'rezusmenu:ui/{n}.png' for n in base], 'ui.json order changed'
    for i, name in enumerate(CHAR_ORDER):
        w, h = tex[name].size
        bitmaps.append({'type': 'bitmap', 'file': f'rezusmenu:ui/{name}.png', 'ascent': h, 'height': h,
                        'chars': [chr(0xE000 + len(base) + i)]})
    with open(path, 'w', encoding='utf-8') as f:
        json.dump({'providers': bitmaps + rest}, f, indent=2)
        f.write('\n')
    for i, name in enumerate(CHAR_ORDER):
        print(f'{0xE000 + len(base) + i:04X} {name} {tex[name].size[0]}x{tex[name].size[1]}')


if __name__ == '__main__':
    write(build())
