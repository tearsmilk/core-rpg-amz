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
    """Стиль azerus:parchment в двойной чёткости: текстура 200×200 при логическом размере 100×100 (клиент сэмплирует
    спрайт, детали — полупикселями). Рамка: объёмные рулоны сверху/снизу с торцами под золотыми колпачками, двойная
    золотисто-коричневая линия с узорными уголками. Фон: пергамент светлее к центру, подпалина по краям, пятна.
    nine_slice border 12 (логических): уголки с рулоном и узором не растягиваются; середина и края — растягиваются
    (поэтому в них только плавные переходы, без мелкого шума). Разделитель для пергамента — U+E421."""
    import numpy as np
    K = 2                       # пикселей текстуры на логический пиксель
    S = 100 * K
    yy, xx = np.mgrid[0:S, 0:S].astype(float)

    def mix(c1, c2, t):
        t = np.clip(t, 0, 1)[..., None]
        return np.array(c1, float) * (1 - t) + np.array(c2, float) * t

    # ---------------- фон: пергамент x 10..189, y 12..187 (в текстуре). Текст клиент ставит на 12 логических
    # (24 px текстуры) от края спрайта — всё украшение умещается в эти поля.
    x0, x1, y0, y1 = 10, S - 11, 12, S - 13
    inside = (xx >= x0) & (xx <= x1) & (yy >= y0) & (yy <= y1)
    # расстояние до края пергамента (подпалина) — только у краёв, в пределах рамки nine_slice (24 px)
    de = np.minimum.reduce([xx - x0, x1 - xx, yy - y0, y1 - yy])
    burn = np.clip(1 - de / 14.0, 0, 1) ** 1.6
    base = mix((236, 214, 172), (226, 198, 152), 0.0 * xx)
    col = mix(base, (176, 122, 72), burn * 0.85)
    col = mix(col, (120, 70, 38), (de < 1.5) * 1.0)
    bg = np.zeros((S, S, 4), np.uint8)
    bg[..., :3] = np.clip(col, 0, 255).astype(np.uint8)
    bg[..., 3] = np.where(inside, 252, 0)
    # пятна старины — только в уголках (не растягиваются)
    rng = np.random.RandomState(9)
    for cx, cy in ((26, 30), (S - 30, 34), (24, S - 30), (S - 26, S - 34), (40, 24), (S - 44, S - 26)):
        r = rng.uniform(5, 9)
        d = np.hypot(xx - cx, yy - cy)
        m = inside & (d < r)
        t = (1 - d / r)[m] * 0.25
        bg[..., :3][m] = np.clip(bg[..., :3][m] * (1 - t[:, None]) + np.array((190, 140, 90)) * t[:, None], 0, 255)
    # рваные зубцы по бокам — только в уголках
    for yv in range(y0, y0 + 22, 3):
        for xv in (x0, x0 + 1, x1, x1 - 1):
            if rng.rand() < 0.5:
                bg[yv:yv + 2, xv, 3] = 0
    for yv in range(y1 - 22, y1, 3):
        for xv in (x0, x0 + 1, x1, x1 - 1):
            if rng.rand() < 0.5:
                bg[yv:yv + 2, xv, 3] = 0

    # ---------------- рамка
    fr = np.zeros((S, S, 4), np.uint8)
    GOLD = [(255, 236, 160), (232, 190, 96), (190, 140, 56), (130, 86, 32)]
    INK = (96, 52, 26)

    def put(mask, rgb, a=255):
        fr[mask, :3] = rgb
        fr[mask, 3] = a

    # двойная линия внутри пергамента (inset 7 и 10 от края пергамента)
    for inset, iy, c, w in ((5, 18, (150, 92, 44), 1.3), (8, 21, (184, 132, 70), 0.9)):
        lx0, lx1, ly0, ly1 = x0 + inset, x1 - inset, iy, S - 1 - iy
        on = (((np.abs(xx - lx0) < w) | (np.abs(xx - lx1) < w)) & (yy >= ly0) & (yy <= ly1)) | \
             (((np.abs(yy - ly0) < w) | (np.abs(yy - ly1) < w)) & (xx >= lx0) & (xx <= lx1))
        put(on, c, 235)
    # узорные уголки: ромб-«лилия» с завитками в каждом углу внутренней линии
    for sx, sy in ((1, 1), (-1, 1), (1, -1), (-1, -1)):
        cx = (x0 + 5) if sx > 0 else (x1 - 5)
        cy = 18 if sy > 0 else S - 19
        d = np.abs(xx - cx) + np.abs(yy - cy)
        put(d <= 3.6, GOLD[3])
        put(d <= 2.6, GOLD[1])
        put(d <= 1.0, GOLD[0])

    # рулоны: сверху y 0..22, снизу S-23..S-1; цилиндр с бликом, концы — дерево + золотой колпачок
    def roll(ry0):
        h = 16
        t = (yy - ry0) / h
        on = (yy >= ry0) & (yy < ry0 + h) & (xx >= 14) & (xx <= S - 15)
        # профиль цилиндра: свет сверху
        prof = 1 - np.abs(t - 0.38) * 1.9
        c = mix((150, 98, 56), (246, 222, 180), np.clip(prof, 0, 1) ** 0.8)
        c = mix(c, (255, 246, 222), np.clip((0.06 - np.abs(t - 0.30)) / 0.06, 0, 1) * 0.8)   # блик
        fr[on, :3] = np.clip(c[on], 0, 255).astype(np.uint8)
        fr[on, 3] = 255
        put(on & ((yy - ry0 < 1.2) | (yy - ry0 > h - 1.6)), INK)
        put(on & (np.abs(yy - (ry0 + h * 0.72)) < 0.6), (196, 150, 104), 200)                 # линия намотки
        # концы рулона
        for side in (1, -1):
            ex = 14 if side > 0 else S - 15
            cap = (yy >= ry0 + 2) & (yy < ry0 + h - 2) & ((xx - ex) * side >= -9) & ((xx - ex) * side < -1)
            tc = (yy - ry0 - 2) / (h - 4)
            cc = mix(GOLD[3], GOLD[0], np.clip(1 - np.abs(tc - 0.35) * 2.2, 0, 1))
            fr[cap, :3] = np.clip(cc[cap], 0, 255).astype(np.uint8)
            fr[cap, 3] = 255
            put(cap & ((np.abs((xx - ex) * side + 9) < 0.8) | (np.abs(yy - ry0 - 2) < 0.8) | (np.abs(yy - ry0 - h + 3) < 0.8)), INK)
            knob = np.hypot((xx - (ex - side * 11)) * 1.0, (yy - (ry0 + h / 2)) * 1.1) < 3.6
            put(knob, GOLD[2])
            put(np.hypot(xx - (ex - side * 11) + side * 0.8, yy - (ry0 + h / 2) + 1.0) < 1.5, GOLD[0])
        # тень рулона на пергамент
        sh = (yy >= ry0 + h) & (yy < ry0 + h + 2) & (xx >= 16) & (xx <= S - 17) if ry0 < S / 2 else \
             (yy >= ry0 - 2) & (yy < ry0) & (xx >= 16) & (xx <= S - 17)
        fr[sh] = (60, 30, 14, 70)

    roll(0)
    roll(S - 16)
    meta = {'gui': {'scaling': {'type': 'nine_slice', 'width': 100, 'height': 100, 'border': 12, 'stretch_inner': True}}}
    for kind, a in (('background', bg), ('frame', fr)):
        path = save(Image.fromarray(a, 'RGBA'), 'textures', 'gui', 'sprites', 'tooltip', f'parchment_{kind}.png')
        json.dump(meta, open(path + '.mcmeta', 'w'), indent=1)

    # разделитель 120×7 (логических), двойная чёткость: линия-градиент, ромб с золотом, завитки
    W, H = 120 * K, 7 * K   # ≤ 256 px текстуры (атлас шрифта клиента)
    d = np.zeros((H, W, 4), np.uint8)
    gy, gx = np.mgrid[0:H, 0:W].astype(float)
    mid, cy = W / 2, H / 2 - 0.5
    fade = np.clip(1 - np.abs(gx - mid) / mid, 0, 1) ** 0.7
    line = (np.abs(gy - cy) < 0.8) & (np.abs(gx - mid) > 14)
    d[line, :3] = (120, 66, 32)
    d[line, 3] = (fade[line] * 230).astype(np.uint8)
    dia = np.abs(gx - mid) + np.abs(gy - cy) * 1.4
    for r, c in ((7.5, (110, 58, 26)), (6.0, (210, 160, 70)), (3.5, (250, 220, 140))):
        m = dia <= r
        d[m, :3] = c
        d[m, 3] = 255
    for side in (1, -1):
        rr = np.hypot(gx - (mid + side * 13), gy - cy)
        m = (np.abs(rr - 3.2) < 0.7) & ((gy - cy) * side < 1.0)
        d[m, :3] = (140, 80, 36)
        d[m, 3] = 230
    save(Image.fromarray(d, 'RGBA'), 'textures', 'font', 'tooltip', 'divider_parchment.png')
    return {'type': 'bitmap', 'file': 'azerus:font/tooltip/divider_parchment.png', 'height': 7, 'ascent': 4,
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
