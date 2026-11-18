#!/usr/bin/env python3
"""Подсказки (tooltip) предметов AZERUS в стиле «легендарная карточка»: своя рамка и фон (компонент tooltip_style,
textures/gui/sprites/tooltip/<стиль>_{background,frame}.png, nine_slice), большая иконка предмета слева от названия
(глиф шрифта azerus:tooltip высотой в две строки), плашки редкости и типа, разделитель с ромбом.
Сейчас — кольца (items/rings_azerus.yml пересобирается целиком). Запуск: python3 -I tools/tooltips.py
"""
import json
import os
import sys

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
A = os.path.join(ROOT, 'pack', 'assets', 'azerus')
SKILL = '/root/.claude/skills/synced/c3b8cbb8-56f6-46e0-b1a5-a4a6350f4608_9bcc0299-39b2-4127-bbdc-0f93b08ab14a/minecraft-nexo-gui/scripts'
sys.path.insert(0, os.environ.get('PIXFONT_DIR', SKILL))
import pixfont  # noqa: E402  (пиксельный шрифт с кириллицей)

FONT = 'azerus:tooltip'
# пробелы шрифта azerus:tooltip: сдвиг вправо/влево на 1,2,4,8,16,32 px
POS = {1: '', 2: '', 4: '', 8: '', 16: '', 32: ''}
NEG = {1: '', 2: '', 4: '', 8: '', 16: '', 32: ''}
ICON0 = 0xE100          # иконки колец: U+E100 + (номер-1)
BADGE = {'common': '', 'rare': '', 'epic': '', 'legendary': '', 'ring': ''}
DIVIDER = ''
ICON_H, ICON_ASC = 20, 15
INDENT = 23             # отступ строк под названием: ширина иконки (20) + 3

RARITY = {  # подпись, фон плашки, цвет названия
    'common': ('ОБЫЧНОЕ', (176, 184, 196), '#d6dde6'),
    'rare': ('РЕДКОЕ', (90, 170, 255), '#8cc8ff'),
    'epic': ('ЭПИЧЕСКОЕ', (196, 120, 255), '#d7a6ff'),
    'legendary': ('ЛЕГЕНДАРНОЕ', (110, 230, 255), '#9fe8ff'),
}


def shift(n):
    out, table = '', (POS if n > 0 else NEG)
    n = abs(n)
    for step in (32, 16, 8, 4, 2, 1):
        while n >= step:
            out += table[step]
            n -= step
    return out


def save(img, *parts):
    p = os.path.join(A, *parts)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    img.save(p)
    return p


# ------------------------------------------------------------------------------------------------ рамка и фон
def tooltip_style(name, top, bottom, edge, glow, corner):
    S, B = 100, 9
    bg = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    for y in range(6, S - 6):
        t = (y - 6) / (S - 13)
        c = tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3))
        for x in range(6, S - 6):
            bg.putpixel((x, y), c + (238,))
    fr = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(fr)
    d.rectangle([4, 4, S - 5, S - 5], outline=glow + (150,))      # мягкое свечение снаружи
    d.rectangle([5, 5, S - 6, S - 6], outline=edge + (255,))      # основная линия
    d.rectangle([6, 6, S - 7, S - 7], outline=glow + (110,))      # внутренний отсвет
    for cx, cy, sx, sy in ((2, 2, 1, 1), (S - 3, 2, -1, 1), (2, S - 3, 1, -1), (S - 3, S - 3, -1, -1)):
        for i in range(5):                                        # уголки-скобы
            fr.putpixel((cx + sx * i, cy), corner + (255,))
            fr.putpixel((cx, cy + sy * i), corner + (255,))
    meta = {'gui': {'scaling': {'type': 'nine_slice', 'width': S, 'height': S, 'border': B, 'stretch_inner': True}}}
    for kind, img in (('background', bg), ('frame', fr)):
        p = save(img, 'textures', 'gui', 'sprites', 'tooltip', f'{name}_{kind}.png')
        json.dump(meta, open(p + '.mcmeta', 'w'), indent=1)


# ------------------------------------------------------------------------------------------------ плашки
def badge(text, bgc, fg=(10, 18, 40)):
    w = pixfont.text_width(text) + 4
    img = Image.new('RGBA', (w, 9), bgc + (255,))
    pixfont.draw_text(img, 2, 1, text, '#%02x%02x%02x' % fg)
    return img


def divider(width=150):
    img = Image.new('RGBA', (width, 5), (0, 0, 0, 0))
    mid = width // 2
    for x in range(width):
        if abs(x - mid) > 5:
            a = int(200 * (1 - abs(x - mid) / mid) ** 0.6)
            img.putpixel((x, 2), (120, 200, 255, max(a, 30)))
    for dx, dy in ((0, 0), (-1, 1), (1, 1), (0, 2), (-1, 3), (1, 3), (0, 4), (-2, 2), (2, 2)):
        img.putpixel((mid + dx, dy), (190, 240, 255, 255))
    for x in (mid - 5, mid + 5):
        img.putpixel((x, 2), (190, 240, 255, 255))
    return img


# ------------------------------------------------------------------------------------------------ пергамент
DIVIDER_PARCHMENT = '\ue421'


def parchment_style():
    """Стиль azerus:parchment: пергамент с рваными боками и рулонами сверху/снизу; разделитель для него (U+E421)."""
    import numpy as np
    S = 100
    TAN, TAN_L, TAN_D, EDGE, OUT = (214, 166, 118), (228, 186, 140), (196, 146, 100), (120, 62, 34), (70, 34, 20)
    ROLL = [(250, 214, 168), (236, 190, 140), (214, 160, 110), (180, 120, 76), (130, 74, 42)]
    rng = np.random.RandomState(4)
    bg = np.zeros((S, S, 4), np.uint8)
    for y in range(7, 93):
        for x in range(5, 95):
            c = TAN
            if (x * 7 + y * 13) % 23 == 0:
                c = TAN_L
            if (x * 5 + y * 11) % 29 == 0:
                c = TAN_D
            bg[y, x] = c + (255,)
    for x in range(5, 95):
        if rng.rand() < 0.3:
            bg[12 + rng.randint(0, 3), x] = TAN_L + (255,)
    bg[7:93, 5] = EDGE + (255,)
    bg[7:93, 94] = EDGE + (255,)
    bg[7:93, 6] = TAN_D + (255,)
    bg[7:93, 93] = TAN_D + (255,)
    for y in range(9, 91):
        if rng.rand() < 0.2:
            bg[y, 5] = (0, 0, 0, 0)
            bg[y, 6] = EDGE + (255,)
        if rng.rand() < 0.2:
            bg[y, 94] = (0, 0, 0, 0)
            bg[y, 93] = EDGE + (255,)
    fr = np.zeros((S, S, 4), np.uint8)

    def roll(y0):
        for i, c in enumerate(ROLL):
            fr[y0 + 1 + i, 3:97] = c + (255,)
        fr[y0, 3:97] = OUT + (255,)
        fr[y0 + 6, 3:97] = OUT + (255,)
        for y in range(y0, y0 + 7):
            edge = y in (y0, y0 + 6)
            fr[y, 2] = OUT + (255,)
            fr[y, 97] = OUT + (255,)
            fr[y, 3] = (OUT if edge else ROLL[3]) + (255,)
            fr[y, 96] = (OUT if edge else ROLL[3]) + (255,)
        fr[y0 + 7, 4:96] = (60, 30, 18, 90)

    roll(1)
    roll(S - 9)
    meta = {'gui': {'scaling': {'type': 'nine_slice', 'width': S, 'height': S, 'border': 9, 'stretch_inner': True}}}
    for kind, a in (('background', bg), ('frame', fr)):
        path = save(Image.fromarray(a, 'RGBA'), 'textures', 'gui', 'sprites', 'tooltip', f'parchment_{kind}.png')
        json.dump(meta, open(path + '.mcmeta', 'w'), indent=1)
    w = 150
    d = np.zeros((5, w, 4), np.uint8)
    mid = w // 2
    for x in range(w):
        if abs(x - mid) > 5:
            d[2, x] = (120, 62, 34, max(int(220 * (1 - abs(x - mid) / mid) ** 0.5), 40))
    for dx, dy in ((0, 0), (-1, 1), (1, 1), (0, 2), (-2, 2), (2, 2), (-1, 3), (1, 3), (0, 4)):
        d[dy, mid + dx] = (150, 70, 30, 255)
    d[2, mid] = (230, 160, 60, 255)
    save(Image.fromarray(d, 'RGBA'), 'textures', 'font', 'tooltip', 'divider_parchment.png')
    return {'type': 'bitmap', 'file': 'azerus:font/tooltip/divider_parchment.png', 'height': 5, 'ascent': 3,
            'chars': [DIVIDER_PARCHMENT]}


# ------------------------------------------------------------------------------------------------ кольца
def ring_rarity(n):
    return 'common' if n <= 48 else 'rare'


def main():
    tooltip_style('ring', top=(10, 24, 66), bottom=(14, 46, 112), edge=(70, 200, 255), glow=(40, 120, 210),
                  corner=(160, 235, 255))
    providers = [{'type': 'space', 'advances': {**{v: k for k, v in POS.items()}, **{v: -k for k, v in NEG.items()}}}]
    for key, ch in BADGE.items():
        if key == 'ring':
            img = badge('КОЛЬЦО', (236, 240, 246))
        else:
            label, col, _ = RARITY[key]
            img = badge(label, col)
        save(img, 'textures', 'font', 'tooltip', f'badge_{key}.png')
        providers.append({'type': 'bitmap', 'file': f'azerus:font/tooltip/badge_{key}.png', 'height': 9, 'ascent': 8,
                          'chars': [ch]})
    save(divider(), 'textures', 'font', 'tooltip', 'divider.png')
    providers.append({'type': 'bitmap', 'file': 'azerus:font/tooltip/divider.png', 'height': 5, 'ascent': 3,
                      'chars': [DIVIDER]})
    providers.append(parchment_style())
    y = ['# Кольца AZERUS (102 шт.) — раздел rings_azerus. Подсказка в стиле карточки: tools/tooltips.py',
         '# (рамка azerus:ring, иконка слева от названия — глиф azerus:tooltip, плашки редкости/типа). Не править руками:',
         '# файл пересобирает tools/tooltips.py.', '']
    for n in range(1, 103):
        num = f'{n:03d}'
        ch = chr(ICON0 + n - 1)
        providers.append({'type': 'bitmap', 'file': f'azerus:item/rings_azerus/ring_{num}.png', 'height': ICON_H,
                          'ascent': ICON_ASC, 'chars': [ch]})
        r = ring_rarity(n)
        label, _, color = RARITY[r]
        icon = f'<font:{FONT}>{shift(-1)}{ch}{shift(3)}</font>'
        pad = f'<font:{FONT}>{shift(INDENT)}</font>'
        lore = [
            f'<!i>{pad}<font:{FONT}>{BADGE[r]}{shift(2)}{BADGE["ring"]}</font>',
            '<!i>',
            f'<!i><font:{FONT}>{DIVIDER}</font>',
            '<!i><gray>Сила этого кольца пока',
            '<!i><gray>скрыта от глаз.',
        ]
        y += [f'azerus_ring_{num}:', '  material: PAPER', f'  itemname: "<!i>{icon}<{color}>Кольцо {num}"',
              '  lore:'] + [f'    - "{l}"' for l in lore] + [
              '  Pack:', f'    model: azerus:item/rings_azerus/ring_{num}',
              '  Components:', f'    item_model: azerus:rings_azerus/ring_{num}', '    tooltip_style: azerus:ring', '']
    p = os.path.join(A, 'font', 'tooltip.json')
    json.dump({'providers': providers}, open(p, 'w', encoding='utf-8'), indent=1, ensure_ascii=False)
    open(os.path.join(ROOT, 'items', 'rings_azerus.yml'), 'w', encoding='utf-8').write('\n'.join(y))
    print('ok: стиль ring, плашек', len(BADGE), 'иконок', 102)


if __name__ == '__main__':
    main()
