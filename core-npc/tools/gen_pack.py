#!/usr/bin/env python3
"""Генератор пакета ресурсов core-npc: окно диалога над хотбаром (шрифты в пространстве имён corenpc).

    python3 tools/gen_pack.py [путь/к/client-1.21.11.jar]

Пишет pack/assets/corenpc/** и src/main/resources/font-widths.txt (ширины глифов для раскладки в Java).
Без аргумента клиентский jar 1.21.11 скачивается с серверов Mojang в tools/.cache/ (нужны только ванильные
текстуры шрифта ascii.png и nonlatin_european.png).

Как устроено окно (всё — обычный rendertype_text, общий core-шейдер не трогается):
  * actionbar рисуется на высоте экрана H−68 (верх строки — H−72, верх глифа с ascent a — H−65−a);
  * окно (320×78, ascent 63): от H−128 до H−51, над сердцами и бронёй. Клиент собирает глифы в атлас 256×256
    (FontTexture.SIZE) и глиф шире или выше 256 не влезает — вместо него «нет глифа». Поэтому окно — две половины
    160×78: U+E000 и U+E001 шрифта corenpc:dialog, между ними сдвиг −1 (шаг глифа = ширина + 1);
  * строки окна — 7 шрифтов corenpc:row0..row6 с разным ascent: у глифа ванильного шрифта снизу прозрачный запас
    (клетка 8×h), поэтому ascent можно поднять до h (ascent не может быть больше height) — так строка встаёт выше
    обычного actionbar. Высота клетки у каждой строки своя — max(8, ascent): чем ниже строка, тем меньше пустоты
    в атласе (glyphs_h<h>.png; строки с одной высотой делят текстуру);
  * row0 — имя говорящего, row1..row3 — текст, row4..row6 — ответы; подсветка выбранного ответа — две половины
    154×16 (U+E010, U+E012, между ними сдвиг −1 — U+F801 есть и в шрифтах строк), только в row4..row6;
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

CELL_W = 8                      # клетка глифа: 8×8 ванильного шрифта + прозрачный запас снизу (высота — по строке)
BOX_W, BOX_H, BOX_ASCENT = 320, 78, 63
BOX_PARTS = 2                   # окно режется на части не шире 256 (атлас клиента 256×256)
HL_H = 16                       # высота клетки подсветки (полоса 10 пикселей сверху)
HL_ROWS = (4, 5, 6)             # подсветка есть только в шрифтах ответов
NEXT_ROW = 6                    # «▼ дальше» — только в последней строке
# верх глифа строки над экраном: H−124 (имя), H−111/−101/−91 (текст), H−80/−71/−62 (ответы)
ROW_TOPS = [124, 111, 101, 91, 80, 71, 62]
HL_W = 308
HL_PARTS = 2
MAX_GLYPH = 256

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


def load_glyphs(jar, tall=False):
    """Символ → картинка (RGBA) из ванильных шрифтов: 8×8 из ascii.png и nonlatin_european.png или (tall) 8×12 из
    accented.png (буквы с надстрочными знаками: Й, Ё…; рисуются на 3 пикселя выше)."""
    z = zipfile.ZipFile(jar)
    default = json.loads(z.read('assets/minecraft/font/include/default.json'))
    out = {}
    files = ('font/accented.png',) if tall else ('font/ascii.png', 'font/nonlatin_european.png')
    for p in default['providers']:
        if p.get('type') != 'bitmap':
            continue
        name = p['file'].split(':', 1)[1]
        if name not in files:
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
    for yy, col in ((14, (196, 160, 92, 150)), (46, (120, 104, 80, 110))):
        a[yy, 6:BOX_W - 6] = col
    return Image.fromarray(a, 'RGBA')


def indicator():
    """▼ «дальше» 7×5."""
    a = np.zeros((5, 7, 4), dtype=np.uint8)
    for i in range(4):
        a[i, i:7 - i] = (230, 196, 120, 255)
    return Image.fromarray(a, 'RGBA')


def highlight():
    """Подсветка выбранного ответа: HL_W×HL_H, полоса 10 пикселей сверху."""
    a = np.zeros((HL_H, HL_W, 4), dtype=np.uint8)
    xs = np.arange(HL_W)
    fade = np.clip(np.minimum(xs, HL_W - 1 - xs) / 30, 0, 1)
    a[0:10, :, 0] = 230
    a[0:10, :, 1] = 190
    a[0:10, :, 2] = 110
    a[0:10, :, 3] = (fade * 70 + 26).astype(np.uint8)[None, :]
    a[0:10, 0:2] = (230, 196, 120, 230)
    return Image.fromarray(a, 'RGBA')


def split(img, parts):
    """Разрезать картинку по ширине на равные части (каждая — отдельный глиф не шире MAX_GLYPH)."""
    w = img.width // parts
    assert w * parts == img.width and w <= MAX_GLYPH and img.height <= MAX_GLYPH, img.size
    out = [img.crop((i * w, 0, (i + 1) * w, img.height)) for i in range(parts)]
    for o in out:   # последний столбец каждой части не пустой — шаг глифа = ширина + 1
        assert np.asarray(o)[..., 3][:, -1].max() > 0
    return out


def check(provs, name):
    """Правила клиента 1.21.11: ascent ≤ height (BitmapProvider.Definition.validate — иначе весь файл шрифта
    отбрасывается: «Unable to load font»), глиф не больше 256×256 (FontTexture.SIZE)."""
    for p in provs:
        if p['type'] != 'bitmap':
            continue
        assert p['ascent'] <= p['height'], (name, p['file'], p['ascent'], p['height'])
        img = Image.open(os.path.join(A, 'textures', p['file'].split(':', 1)[1]))
        rows, cols = len(p['chars']), max(len(r) for r in p['chars'])
        assert img.height % rows == 0 and img.width % cols == 0, (name, p['file'])
        assert img.width // cols <= MAX_GLYPH and img.height // rows <= MAX_GLYPH, (name, p['file'], img.size)


def atlas(glyphs, widths, cell_h):
    cps = sorted(glyphs)
    cols = 32
    rows = (len(cps) + cols - 1) // cols
    tex = Image.new('RGBA', (cols * CELL_W, rows * cell_h), (0, 0, 0, 0))
    chars = []
    for i, cp in enumerate(cps):
        r, c = divmod(i, cols)
        tex.paste(glyphs[cp], (c * CELL_W, r * cell_h))
        alpha = np.asarray(glyphs[cp])[..., 3]
        used = np.nonzero(alpha.max(axis=0))[0]
        widths[cp] = int(used.max()) + 2      # как у клиента: ширина до последнего непустого столбца + 1
    for r in range(rows):
        row = ''.join(chr(cp) for cp in cps[r * cols:(r + 1) * cols])
        chars.append(row + '\u0000' * (cols - len(row)))
    return tex, chars


def main():
    jar = client_jar()
    widths = {}
    base = load_glyphs(jar)
    tall = {cp: g for cp, g in load_glyphs(jar, tall=True).items() if cp not in base}
    for old in ('glyphs.png', 'glyphs_tall.png', 'box.png', 'highlight.png'):
        path = os.path.join(A, 'textures', 'font', 'dialog', old)
        if os.path.exists(path):
            os.remove(path)
    for name in os.listdir(os.path.join(A, 'textures', 'font', 'dialog')):
        if name.startswith('glyphs_h') or name.startswith('tall_h'):
            os.remove(os.path.join(A, 'textures', 'font', 'dialog', name))
    boxes = split(box(), BOX_PARTS)
    hls = split(highlight(), HL_PARTS)
    for i, b in enumerate(boxes):
        write_png(b, 'font/dialog/box%d.png' % i)
    for i, h in enumerate(hls):
        write_png(h, 'font/dialog/highlight%d.png' % i)
    write_png(indicator(), 'font/dialog/next.png')

    shifts = {}
    for i in range(10):
        shifts[chr(0xF801 + i)] = -(1 << i)
        shifts[chr(0xF821 + i)] = 1 << i
    provs = [{'type': 'space', 'advances': shifts}]
    for i in range(BOX_PARTS):
        provs.append({'type': 'bitmap', 'file': 'corenpc:font/dialog/box%d.png' % i, 'height': BOX_H,
                      'ascent': BOX_ASCENT, 'chars': [chr(0xE000 + i)]})
    check(provs, 'dialog')
    write_json({'providers': provs}, 'font/dialog.json')

    made = {}
    chars = chars_tall = None
    for n, top in enumerate(ROW_TOPS):
        asc = top - 65
        h = max(8, asc)
        ht = max(12, asc + 3)
        if ('b', h) not in made:
            tex, chars = atlas(base, widths, h)
            write_png(tex, 'font/dialog/glyphs_h%d.png' % h)
            made[('b', h)] = (tex.size, chars)
        if ('t', ht) not in made:
            tex, chars_tall = atlas(tall, widths, ht)
            write_png(tex, 'font/dialog/tall_h%d.png' % ht)
            made[('t', ht)] = (tex.size, chars_tall)
        chars = made[('b', h)][1]
        chars_tall = made[('t', ht)][1]
        provs = [
            {'type': 'space', 'advances': {' ': 4, '\u00a0': 4, '\uf801': -1}},
            {'type': 'bitmap', 'file': 'corenpc:font/dialog/glyphs_h%d.png' % h, 'height': h, 'ascent': asc,
             'chars': chars},
            {'type': 'bitmap', 'file': 'corenpc:font/dialog/tall_h%d.png' % ht, 'height': ht, 'ascent': asc + 3,
             'chars': chars_tall},
        ]
        if n in HL_ROWS:
            assert asc + 1 <= HL_H
            for i in range(HL_PARTS):
                provs.append({'type': 'bitmap', 'file': 'corenpc:font/dialog/highlight%d.png' % i, 'height': HL_H,
                              'ascent': asc + 1, 'chars': [chr(0xE010 + 2 * i)]})
        if n == NEXT_ROW:
            provs.append({'type': 'bitmap', 'file': 'corenpc:font/dialog/next.png', 'height': 5, 'ascent': asc - 3,
                          'chars': ['\ue011']})
        check(provs, 'row%d' % n)
        write_json({'providers': provs}, 'font/row%d.json' % n)

    cps = sorted(widths)
    os.makedirs(os.path.dirname(WIDTHS), exist_ok=True)
    with open(WIDTHS, 'w', encoding='utf-8') as f:
        f.write('# Ширины (advance) глифов шрифтов corenpc:row*; генерирует tools/gen_pack.py. Код символа (hex) ширина\n')
        f.write('20 4\na0 4\n')
        for i in range(HL_PARTS):
            f.write('%x %d\n' % (0xE010 + 2 * i, hls[i].width + 1))
        f.write('e011 8\n')
        for cp in cps:
            f.write('%x %d\n' % (cp, widths[cp]))
    # превью для проверки на глаз
    prev = Image.new('RGBA', (BOX_W + 20, BOX_H + 20), (90, 140, 90, 255))
    prev.alpha_composite(box(), (10, 10))
    os.makedirs(os.path.join(HERE, 'preview'), exist_ok=True)
    prev.save(os.path.join(HERE, 'preview', 'box.png'))
    print('glyphs:', len(base), '+', len(tall), 'tall; textures', {k: v[0] for k, v in made.items()})


if __name__ == '__main__':
    main()
