#!/usr/bin/env python3
"""Генератор пакета ресурсов core-npc: окно диалога над хотбаром (шрифты в пространстве имён corenpc).

    python3 tools/gen_pack.py [путь/к/client-1.21.11.jar]

Пишет pack/assets/corenpc/** и src/main/resources/font-widths.txt (ширины глифов для раскладки в Java).
Без аргумента клиентский jar 1.21.11 скачивается с серверов Mojang в tools/.cache/ (нужны только ванильные
текстуры шрифта ascii.png и nonlatin_european.png).

Как устроено окно «свиток писаря» (всё — обычный rendertype_text, общий core-шейдер не трогается):
  * actionbar рисуется на высоте экрана H−68; верх глифа с ascent a — H−65−a; окно — TOP пикселей над низом экрана;
  * основное окно (портрет, плашка имени, пергамент в деревянной рамке, перо) — две половины 142 (U+E000, U+E001
    шрифта corenpc:dialog, между ними сдвиг −1: клиент не рисует глиф шире/выше 256); листок ответов — U+E002,
    рисуется только при ответах; перо-указатель — U+E020..E022 (по глифу на строку ответа), «▼ дальше» — U+E011;
    каждый глиф — со своим ascent (ascent ≤ height: снизу прозрачный запас);
  * строки — 7 шрифтов corenpc:row0..row6 с разным ascent: row0 — имя, row1..row3 — текст, row4..row6 — ответы;
  * сдвиги по горизонтали — пробелы шрифта corenpc:dialog (U+F801.. = −1,−2,−4…−512; U+F821.. = +1…+512).
Числа ниже должны совпадать с hud/HudLayout.java.
"""
import io
import math
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
# Окно «свиток писаря» (локальные координаты окна: x вправо, y вниз; верх окна — TOP пикселей над низом экрана).
# Все картинки окна — глифы шрифта corenpc:dialog, у каждого свой ascent; строки — шрифты corenpc:row0..row6.
# Числа должны совпадать с hud/HudLayout.java.
TOP = 220                       # верх окна на H−220 (низ ≈ H−100, над хотбаром и сердцами)
TOTAL_W = 330                   # общая ширина раскладки (центрирование клиента — по ней)
MAIN_X, MAIN_Y, MAIN_W, MAIN_H = 0, 40, 284, 80      # портрет + имя + пергамент + перо (2 части по 142)
MAIN_PARTS = 2
PANEL_X, PANEL_Y, PANEL_W, PANEL_H = 172, 0, 158, 38  # листок ответов (рисуется только при ответах)
NAME_X, NAME_Y = 48, 47
TEXT_X, TEXT_YS = 38, (64, 74, 84)
ANSWER_X, ANSWER_YS = 184, (5, 15, 25)
POINTER_X = 166                 # перо-указатель выбранного ответа (16×7)
NEXT_X, NEXT_Y = 226, 104       # «▼ дальше» внизу пергамента
ROW_TOPS = [TOP - y for y in (NAME_Y,) + TEXT_YS + ANSWER_YS]   # верх строки над низом экрана
MAX_GLYPH = 256


def asc_of(y):
    """ascent глифа, верх которого — y в координатах окна (верх глифа на экране = H − 65 − ascent)."""
    return TOP - y - 65


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


# --------------------------------------------------------------------------------------------- рисунки окна
# Палитра «свиток писаря»: дерево рамки, пергамент, плашка имени, перо. Свет — сверху слева.
WOOD_O = (52, 24, 18)        # контур
WOOD_D = (92, 44, 32)        # тень дерева
WOOD_M = (124, 62, 44)       # основа
WOOD_L = (160, 88, 60)       # свет
WOOD_H = (190, 118, 82)      # блик
PAR_B = (242, 230, 206)      # пергамент
PAR_S = (228, 212, 182)      # тень пергамента
PAR_E = (210, 186, 148)      # край / рваные кромки
PAR_D = (234, 220, 194)      # рисунок гор (едва видно)
PLATE = (66, 30, 22)
PLATE_IN = (84, 40, 30)
FEATHER = [(236, 236, 246), (206, 204, 226), (170, 166, 198), (128, 124, 160), (88, 84, 116)]


def _rect(a, x0, y0, x1, y1, c, alpha=255):
    a[y0:y1 + 1, x0:x1 + 1] = c + (alpha,)


def _wood_frame(a, x0, y0, x1, y1, t=3):
    """Деревянная рамка толщиной t: контур, свет сверху/слева, тень снизу/справа, «доски» с щелями."""
    _rect(a, x0, y0, x1, y1, WOOD_O)
    _rect(a, x0 + 1, y0 + 1, x1 - 1, y1 - 1, WOOD_M)
    a[y0 + 1, x0 + 1:x1] = WOOD_H + (255,)
    a[y0 + 1:y1, x0 + 1] = WOOD_L + (255,)
    a[y1 - 1, x0 + 1:x1] = WOOD_D + (255,)
    a[y0 + 1:y1, x1 - 1] = WOOD_D + (255,)
    for gx in range(x0 + 37, x1 - 8, 53):        # щели между досками
        a[y0 + 1:y0 + t + 1, gx] = WOOD_O + (255,)
        a[y1 - t:y1, gx + 11] = WOOD_O + (255,)


def _parchment(a, x0, y0, x1, y1, seed=7):
    rng = np.random.RandomState(seed)
    _rect(a, x0, y0, x1, y1, PAR_B)
    a[y0, x0:x1 + 1] = PAR_S + (255,)
    a[y0:y1 + 1, x0] = PAR_S + (255,)
    a[y1, x0:x1 + 1] = PAR_E + (255,)
    a[y0:y1 + 1, x1] = PAR_E + (255,)
    # рваные кромки справа: зубцы
    for y in range(y0, y1 + 1):
        if rng.rand() < 0.35:
            a[y, x1 - 1] = PAR_E + (255,)
    # бледные горы внизу (рисунок на пергаменте)
    base = y1 - 4
    for cx in range(x0 + 10, x1 - 20, 34):
        h = 6 + rng.randint(0, 5)
        for dx in range(-h * 2, h * 2 + 1):
            yy = base - (h - abs(dx) // 2)
            if x0 < cx + dx < x1 and y0 < yy < y1:
                a[yy, cx + dx] = PAR_D + (255,)
                a[yy + 1:base + 1, cx + dx] = (PAR_D if (cx + dx) % 3 else PAR_S) + (255,)
    # пятнышки
    for _ in range((x1 - x0) * (y1 - y0) // 160):
        x, y = rng.randint(x0 + 2, x1 - 2), rng.randint(y0 + 2, y1 - 6)
        a[y, x] = PAR_S + (255,)


def _feather(a, x0, y0, length=40, width=9, flip=False):
    """Перо: стержень по дуге сверху справа вниз влево, бородки с двух сторон, свет сверху."""
    pts = []
    for i in range(length):
        t = i / (length - 1)
        x = x0 + int(round((1 - t) * length * 0.62 + 3 * math.sin(t * 3.0)))
        y = y0 + int(round(t * length * 0.95))
        pts.append((x, y))
    for i, (x, y) in enumerate(pts):
        t = i / (length - 1)
        w = int(round(width * math.sin(min(1.0, t * 1.15) * math.pi) * (1 - 0.35 * t)))
        for k in range(-w, w + 1):
            xx = x + k
            if 0 <= y < a.shape[0] and 0 <= xx < a.shape[1]:
                shade = 0 if k < -w // 2 else (1 if k < 0 else (2 if k < w // 2 else 3))
                if abs(k) == w:
                    shade = 4
                a[y, xx] = FEATHER[shade] + (255,)
        if 0 <= y < a.shape[0] and 0 <= x < a.shape[1]:
            a[y, x] = FEATHER[0] + (255,)       # стержень
    # остриё
    x, y = pts[-1]
    for d in range(4):
        if 0 <= y + d < a.shape[0]:
            a[y + d, max(0, x - d)] = FEATHER[4] + (255,)


def main_box():
    """Портрет + плашка имени + пергамент в деревянной рамке + перо. MAIN_W×MAIN_H (координаты от MAIN_X/MAIN_Y)."""
    a = np.zeros((MAIN_H, MAIN_W, 4), dtype=np.uint8)
    oy = MAIN_Y
    # пергамент в рамке: x 24..270, y 52..118 (окно)
    fx0, fy0, fx1, fy1 = 24, 52 - oy, 270, 118 - oy
    _wood_frame(a, fx0, fy0, fx1, fy1)
    _parchment(a, fx0 + 5, fy0 + 7, fx1 - 5, fy1 - 5)
    # уголки-накладки
    for cx, cy in ((fx0, fy0), (fx1 - 6, fy0), (fx0, fy1 - 6), (fx1 - 6, fy1 - 6)):
        _rect(a, cx, cy, cx + 6, cy + 6, WOOD_O)
        _rect(a, cx + 1, cy + 1, cx + 5, cy + 5, WOOD_L)
        _rect(a, cx + 2, cy + 2, cx + 4, cy + 4, WOOD_M)
    # портрет: 0..36, 40..76
    px0, py0, px1, py1 = 0, 0, 36, 36
    _wood_frame(a, px0, py0, px1, py1)
    _rect(a, px0 + 4, py0 + 4, px1 - 4, py1 - 4, WOOD_O)
    _rect(a, px0 + 5, py0 + 5, px1 - 5, py1 - 5, (46, 22, 17))
    for cx, cy in ((px0 + 2, py0 + 2), (px1 - 3, py0 + 2), (px0 + 2, py1 - 3), (px1 - 3, py1 - 3)):
        _rect(a, cx - 1, cy - 1, cx + 1, cy + 1, WOOD_H)   # шляпки гвоздей
    # плашка имени: x 42..150, y 44..58
    nx0, ny0, nx1, ny1 = 42, 44 - oy, 150, 58 - oy
    _rect(a, nx0, ny0, nx1, ny1, WOOD_O)
    _rect(a, nx0 + 1, ny0 + 1, nx1 - 1, ny1 - 1, WOOD_L)
    _rect(a, nx0 + 2, ny0 + 2, nx1 - 2, ny1 - 2, PLATE)
    a[ny0 + 2, nx0 + 2:nx1 - 1] = PLATE_IN + (255,)
    # перо в правом нижнем углу
    _feather(a, 244, 36, length=42, width=8)
    for x in (MAIN_W // MAIN_PARTS - 1, MAIN_W - 1):   # последний столбец каждой части не пустой (шаг = ширина + 1)
        a[0, x, 3] = max(a[0, x, 3], 1)
    return Image.fromarray(a, 'RGBA')


def panel_box():
    """Листок ответов: пергамент с рваными краями, PANEL_W×PANEL_H."""
    a = np.zeros((PANEL_H, PANEL_W, 4), dtype=np.uint8)
    _rect(a, 0, 0, PANEL_W - 1, PANEL_H - 1, WOOD_O)
    _parchment(a, 1, 1, PANEL_W - 2, PANEL_H - 2, seed=11)
    rng = np.random.RandomState(3)
    for x in range(PANEL_W):                   # рваные верх/низ
        if rng.rand() < 0.25:
            a[0, x] = (0, 0, 0, 0)
            a[1, x] = WOOD_O + (255,)
        if rng.rand() < 0.25:
            a[PANEL_H - 1, x] = (0, 0, 0, 0)
            a[PANEL_H - 2, x] = WOOD_O + (255,)
    a[:, -1, 3] = np.maximum(a[:, -1, 3], 1)    # последний столбец не пустой — шаг глифа = ширина + 1
    return Image.fromarray(a, 'RGBA')


def pointer():
    """Перо-указатель выбранного ответа, 16×7: остриё вправо."""
    a = np.zeros((7, 16, 4), dtype=np.uint8)
    for x in range(16):
        w = int(round(3 * math.sin(min(1.0, (15 - x) / 12) * math.pi / 1.2))) if x < 13 else 0
        for k in range(-w, w + 1):
            y = 3 + k
            a[y, x] = FEATHER[0 if k < 0 else (1 if k == 0 else 3)] + (255,)
        a[3, x] = FEATHER[0] + (255,)
    a[3, 13:16] = FEATHER[4] + (255,)
    return Image.fromarray(a, 'RGBA')


def indicator():
    """▼ «дальше» 7×5 — цвет дерева рамки."""
    a = np.zeros((5, 7, 4), dtype=np.uint8)
    for i in range(4):
        a[i, i:7 - i] = WOOD_M + (255,)
    a[0, 0:7] = WOOD_O + (255,)
    return Image.fromarray(a, 'RGBA')


def cell(img, asc):
    """Клетка глифа: рисунок сверху, ниже прозрачно — высота ≥ ascent (правило клиента)."""
    h = max(img.height, asc)
    out = Image.new('RGBA', (img.width, h), (0, 0, 0, 0))
    out.paste(img, (0, 0))
    return out


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
    for old in os.listdir(os.path.join(A, 'textures', 'font', 'dialog')):
        if old.startswith(('box', 'highlight', 'next', 'panel', 'pointer')):
            os.remove(os.path.join(A, 'textures', 'font', 'dialog', old))
    shifts = {}
    for i in range(10):
        shifts[chr(0xF801 + i)] = -(1 << i)
        shifts[chr(0xF821 + i)] = 1 << i
    provs = [{'type': 'space', 'advances': shifts}]

    def glyph(img, name, y, ch):
        asc = asc_of(y)
        write_png(cell(img, asc), 'font/dialog/%s.png' % name)
        provs.append({'type': 'bitmap', 'file': 'corenpc:font/dialog/%s.png' % name, 'height': max(img.height, asc),
                      'ascent': asc, 'chars': [ch]})

    for i, part in enumerate(split(main_box(), MAIN_PARTS)):
        glyph(part, 'box%d' % i, MAIN_Y, chr(0xE000 + i))          # U+E000, U+E001
    glyph(panel_box(), 'panel', PANEL_Y, '\ue002')
    glyph(indicator(), 'next', NEXT_Y, '\ue011')
    for r, y in enumerate(ANSWER_YS):
        glyph(pointer(), 'pointer%d' % r, y + 1, chr(0xE020 + r))  # U+E020..E022
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
        check(provs, 'row%d' % n)
        write_json({'providers': provs}, 'font/row%d.json' % n)

    cps = sorted(widths)
    os.makedirs(os.path.dirname(WIDTHS), exist_ok=True)
    with open(WIDTHS, 'w', encoding='utf-8') as f:
        f.write('# Ширины (advance) глифов шрифтов corenpc:row*; генерирует tools/gen_pack.py. Код символа (hex) ширина\n')
        f.write('20 4\na0 4\n')
        for cp in cps:
            f.write('%x %d\n' % (cp, widths[cp]))
    print('glyphs:', len(base), '+', len(tall), 'tall; textures', {k: v[0] for k, v in made.items()})


if __name__ == '__main__':
    main()
