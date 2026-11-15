#!/usr/bin/env python3
"""Генератор пакета ресурсов core-npc: окно диалога над хотбаром (шрифты в пространстве имён corenpc).

    python3 tools/gen_pack.py [путь/к/client-1.21.11.jar]

Пишет pack/assets/corenpc/** и src/main/resources/font-widths.txt (ширины глифов для раскладки в Java).
Без аргумента клиентский jar 1.21.11 скачивается с серверов Mojang в tools/.cache/ (нужны только ванильные
текстуры шрифта ascii.png и nonlatin_european.png).

Как устроено окно (всё — обычный rendertype_text, общий core-шейдер не трогается):
  * actionbar рисуется на высоте экрана H−68 (верх строки — H−72, верх глифа с ascent a — H−65−a);
  * окно — глиф U+E000 шрифта corenpc:dialog (248×74, ascent 61): от H−126 до H−52, над сердцами и бронёй;
  * строки окна — 7 шрифтов corenpc:row0..row6 с одной и той же текстурой glyphs.png, но разным ascent:
    у каждого глифа ванильного шрифта снизу прозрачный запас 56 пикселей (клетка 8×64, height 64), поэтому
    ascent можно поднять до 63 (ascent не может быть больше height) — так строка встаёт выше обычного actionbar;
  * row0 — имя говорящего, row1..row3 — текст, row4..row6 — ответы (U+E010 — подсветка выбранного ответа);
  * сдвиги по горизонтали — пробелы шрифта corenpc:dialog (U+F801.. = −1,−2,−4…−512; U+F821.. = +1…+512).
Числа ниже должны совпадать с hud/HudLayout.java.
"""
import io
import json
import os
import sys
import urllib.request
import zipfile

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, '..')
A = os.path.join(ROOT, 'pack', 'assets', 'corenpc')
WIDTHS = os.path.join(ROOT, 'src', 'main', 'resources', 'font-widths.txt')

CELL_W, CELL_H = 8, 64          # клетка глифа: 8×8 ванильного шрифта + 56 прозрачных снизу
BOX_W, BOX_H, BOX_ASCENT = 248, 74, 61
# верх глифа строки над экраном: H−122 (имя), H−110/−100/−90 (текст), H−80/−71/−62 (ответы)
ROW_TOPS = [122, 110, 100, 90, 80, 71, 62]
HL_W = 236

RANGES = [(0x20, 0x7E), (0xA0, 0x24F), (0x370, 0x3FF), (0x400, 0x52F), (0x2000, 0x206F), (0x20A0, 0x20CF),
          (0x2100, 0x214F), (0x2190, 0x21FF), (0x2200, 0x22FF), (0x2500, 0x25FF), (0x2600, 0x27BF)]


def client_jar():
    if len(sys.argv) > 1:
        return sys.argv[1]
    cache = os.path.join(HERE, '.cache')
    os.makedirs(cache, exist_ok=True)
    path = os.path.join(cache, 'client-1.21.11.jar')
    if not os.path.exists(path):
        man = json.load(urllib.request.urlopen('https://piston-meta.mojang.com/mc/game/version_manifest_v2.json'))
        url = [v['url'] for v in man['versions'] if v['id'] == '1.21.11'][0]
        ver = json.load(urllib.request.urlopen(url))
        urllib.request.urlretrieve(ver['downloads']['client']['url'], path)
    return path


def wanted(cp):
    return any(a <= cp <= b for a, b in RANGES)


def load_glyphs(jar):
    """Символ → картинка 8×8 (RGBA) из ванильных ascii.png и nonlatin_european.png (первый провайдер важнее)."""
    z = zipfile.ZipFile(jar)
    default = json.loads(z.read('assets/minecraft/font/include/default.json'))
    out = {}
    for p in default['providers']:
        if p.get('type') != 'bitmap' or p.get('height', 8) != 8:
            continue
        name = p['file'].split(':', 1)[1]
        if name not in ('font/ascii.png', 'font/nonlatin_european.png'):
            continue
        img = Image.open(io.BytesIO(z.read('assets/minecraft/textures/' + name))).convert('RGBA')
        rows = p['chars']
        ch = img.height // len(rows)
        cw = img.width // len(rows[0])
        for r, row in enumerate(rows):
            for c, s in enumerate(row):
                cp = ord(s)
                if cp == 0 or cp in out or not wanted(cp) or s == ' ':
                    continue
                cell = img.crop((c * cw, r * ch, (c + 1) * cw, (r + 1) * ch))
                if np.asarray(cell)[..., 3].max() == 0:
                    continue
                out[cp] = cell
    return out


def write_png(img, rel):
    path = os.path.join(A, 'textures', rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, optimize=True)


def write_json(obj, rel):
    path = os.path.join(A, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(obj, f, ensure_ascii=False, indent=2)
        f.write('\n')


def box():
    """Окно: тёмная полупрозрачная панель, тонкая рамка, скруглённые углы, полоса под именем."""
    a = np.zeros((BOX_H, BOX_W, 4), dtype=np.uint8)
    y, x = np.mgrid[0:BOX_H, 0:BOX_W]
    r = 4
    cx = np.clip(x, r, BOX_W - 1 - r)
    cy = np.clip(y, r, BOX_H - 1 - r)
    d = np.sqrt((x - cx) ** 2 + (y - cy) ** 2)
    inside = d <= r
    border = inside & (d > r - 1.2) | inside & ((x == 0) | (x == BOX_W - 1) | (y == 0) | (y == BOX_H - 1))
    inner = inside & ~border
    grad = 1 - y / BOX_H * 0.35
    a[inner] = np.stack([14 * grad, 12 * grad, 18 * grad, np.full_like(grad, 205)], -1)[inner]
    a[border] = (196, 160, 92, 235)
    # полоса под именем и разделитель над ответами
    for yy, col in ((14, (196, 160, 92, 150)), (45, (120, 104, 80, 110))):
        a[yy, 6:BOX_W - 6] = col
    return Image.fromarray(a, 'RGBA')


def indicator():
    """▼ «дальше» 7×5."""
    a = np.zeros((5, 7, 4), dtype=np.uint8)
    for i in range(4):
        a[i, i:7 - i] = (230, 196, 120, 255)
    return Image.fromarray(a, 'RGBA')


def highlight():
    """Подсветка выбранного ответа: клетка HL_W×CELL_H, полоса 10 пикселей сверху."""
    a = np.zeros((CELL_H, HL_W, 4), dtype=np.uint8)
    xs = np.arange(HL_W)
    fade = np.clip(np.minimum(xs, HL_W - 1 - xs) / 30, 0, 1)
    a[0:10, :, 0] = 230
    a[0:10, :, 1] = 190
    a[0:10, :, 2] = 110
    a[0:10, :, 3] = (fade * 70 + 26).astype(np.uint8)[None, :]
    a[0:10, 0:2] = (230, 196, 120, 230)
    a[CELL_H - 1, HL_W - 1, 3] = 1   # последний столбец не пустой — ширина глифа не урежется
    return Image.fromarray(a, 'RGBA')


def main():
    glyphs = load_glyphs(client_jar())
    cps = sorted(glyphs)
    cols = 32
    rows = (len(cps) + cols - 1) // cols
    tex = Image.new('RGBA', (cols * CELL_W, rows * CELL_H), (0, 0, 0, 0))
    chars = []
    widths = {}
    for i, cp in enumerate(cps):
        r, c = divmod(i, cols)
        tex.paste(glyphs[cp], (c * CELL_W, r * CELL_H))
        alpha = np.asarray(glyphs[cp])[..., 3]
        used = np.nonzero(alpha.max(axis=0))[0]
        widths[cp] = int(used.max()) + 2      # как у клиента: ширина до последнего непустого столбца + 1
    for r in range(rows):
        row = ''.join(chr(cp) for cp in cps[r * cols:(r + 1) * cols])
        chars.append(row + '\u0000' * (cols - len(row)))
    write_png(tex, 'font/dialog/glyphs.png')
    write_png(box(), 'font/dialog/box.png')
    write_png(indicator(), 'font/dialog/next.png')
    write_png(highlight(), 'font/dialog/highlight.png')

    shifts = {}
    for i in range(10):
        shifts[chr(0xF801 + i)] = -(1 << i)
        shifts[chr(0xF821 + i)] = 1 << i
    write_json({'providers': [
        {'type': 'space', 'advances': shifts},
        {'type': 'bitmap', 'file': 'corenpc:font/dialog/box.png', 'height': BOX_H, 'ascent': BOX_ASCENT,
         'chars': ['']},
    ]}, 'font/dialog.json')
    for n, top in enumerate(ROW_TOPS):
        asc = top - 65
        provs = [
            {'type': 'space', 'advances': {' ': 4, ' ': 4}},
            {'type': 'bitmap', 'file': 'corenpc:font/dialog/glyphs.png', 'height': CELL_H, 'ascent': asc, 'chars': chars},
            {'type': 'bitmap', 'file': 'corenpc:font/dialog/highlight.png', 'height': CELL_H, 'ascent': asc + 1,
             'chars': ['']},
            {'type': 'bitmap', 'file': 'corenpc:font/dialog/next.png', 'height': 5, 'ascent': asc - 3,
             'chars': ['']},
        ]
        write_json({'providers': provs}, 'font/row%d.json' % n)

    os.makedirs(os.path.dirname(WIDTHS), exist_ok=True)
    with open(WIDTHS, 'w', encoding='utf-8') as f:
        f.write('# Ширины (advance) глифов шрифтов corenpc:row*; генерирует tools/gen_pack.py. Код символа (hex) ширина\n')
        f.write('20 4\na0 4\ne010 %d\ne011 8\n' % (HL_W + 1))
        for cp in cps:
            f.write('%x %d\n' % (cp, widths[cp]))
    # превью для проверки на глаз
    prev = Image.new('RGBA', (BOX_W + 20, BOX_H + 20), (90, 140, 90, 255))
    prev.alpha_composite(box(), (10, 10))
    os.makedirs(os.path.join(HERE, 'preview'), exist_ok=True)
    prev.save(os.path.join(HERE, 'preview', 'box.png'))
    print('glyphs:', len(cps), 'texture', tex.size)


if __name__ == '__main__':
    main()
