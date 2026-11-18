"""Preview of the RPG menu tabs "Мой персонаж" and "Снаряжение": pack textures placed exactly like RpgScreen.java,
sample texts (pixel font of the minecraft-nexo-gui skill), a sample skin (SkinFront port) and simple item stand-ins.

    python3 tools/rpg_preview.py      # -> tools/preview/rpg_character.png, rpg_equipment.png (x2)

Texts are approximate (the real menu uses the Minecraft font); positions are the ones from the code.
"""
import os
import sys

from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import profiles_preview as P  # noqa: E402

PX, PY = 96, 56                     # panel on the canvas
TAB_X, TAB_Y = (12, 136, 260, 384), 10
GRID_X, GRID_STEP, GRID_Y, HOTBAR_Y = 218, 31, (74, 105, 136), 172
CHAR_NAME_X, CHAR_VALUE_R, CHAR_ROW_Y, CHAR_DY = (222, 364), (348, 490), 94, 12
RED, AQUA, ORANGE = (255, 85, 85), (85, 255, 255), (255, 170, 60)


def base(tab):
    img = Image.new('RGBA', (704, 400), (0, 0, 0, 255))
    for r in range(3):
        for c in range(3):
            img.alpha_composite(P.tex(f'back_{r}_{c}'), ((0, 256, 512)[c], (0, 144, 288)[r]))
    for i in range(4):
        img.alpha_composite(P.tex(f'panel_{i}'), (PX + (i % 2) * 256, PY + (i // 2) * 144))
    img.alpha_composite(P.tex('tab_active'), (PX + TAB_X[tab], PY + TAB_Y))
    P.text(img, 240, 12, 'Персонаж', P.GOLD, h=32, lh=11, cx=352)
    names = ('Мой персонаж', 'Снаряжение', 'Навыки', 'Задания')
    colors = ((200, 160, 110), (190, 190, 200), (160, 90, 200), (200, 180, 120))
    for i, n in enumerate(names):
        P.item_icon(img, PX + TAB_X[i] + 16, PY + TAB_Y + 16, 14, colors[i])
        P.text(img, 0, PY + TAB_Y, n, P.WHITE, h=32, lh=10, cx=PX + TAB_X[i] + 26 + 44)
    # wings
    P.item_icon(img, 48, 96, 30, (190, 190, 200))
    P.text(img, 0, 128, 'Мечник', P.GOLD, h=16, lh=8, cx=48)
    P.text(img, 0, 152, 'нет профессии', (85, 85, 85), h=16, lh=7, cx=48)
    P.text(img, 0, 176, 'Ур. 12', P.GREEN, h=16, lh=8, cx=48)
    P.text(img, 0, 192, '340 / 1200', P.GRAY, h=16, lh=7, cx=48)
    P.text(img, 0, 78, '3', P.GOLD, h=28, lh=18, cx=656)
    P.text(img, 0, 116, 'Очки навыков', P.GOLD, h=16, lh=7, cx=656)
    P.text(img, 0, 154, '5', P.GOLD, h=28, lh=18, cx=656)
    P.text(img, 0, 192, 'Очки параметров', P.GOLD, h=16, lh=7, cx=656)
    P.text(img, 0, 224, 'Профессии', P.GOLD, h=16, lh=7, cx=656)
    for i, n in enumerate(('Крестоносец', 'Тёмный рыцарь', 'с 20 ур.')):
        P.text(img, 634, 224 + (i + 1) * 16, n, P.GRAY, h=16, lh=7)
    # left section: nick, skin, bars, stats
    P.text(img, 0, PY + 52, 'Steve · Мечник', P.YELLOW, h=16, lh=9, cx=PX + 109)
    skin = P.front(P.sample_skin()).resize((48, 96), Image.NEAREST)
    img.alpha_composite(skin, (PX + 82, PY + 84))
    for (bar, y, frac, label) in (('bar_exp', 206, .28, 'Ур. 12 · 340 / 1200'), ('bar_hp', 220, .85, 'Здоровье 17 / 20'),
                                  ('bar_energy', 234, .6, 'Энергия 12 / 20')):
        t = P.tex(bar)
        img.alpha_composite(t.crop((0, 0, round(176 * frac), t.height)), (PX + 32, PY + y + 2))
        P.text(img, 0, PY + y, label, P.WHITE, h=10, lh=7, cx=PX + 32 + 83)
    for i, s in enumerate(('Сила 14', 'Ловкость 9', 'Выносливость 11', 'Удача 3')):
        P.text(img, 0, PY + (248, 248, 261, 261)[i], s, P.WHITE, h=13, lh=7,
               cx=PX + (16 + 46, 108 + 47, 16 + 46, 108 + 47)[i])
    P.text(img, 0, 358, 'ЛКМ — нажать · Пробел — курсор в центр · Shift — выйти', P.GRAY, h=24, lh=8, cx=352)
    return img


def character():
    img = base(0)
    img.alpha_composite(P.tex('char_left'), (PX + 14, PY + 66))
    img.alpha_composite(P.tex('char_bg_0'), (PX + 212, PY + 52))
    img.alpha_composite(P.tex('char_bg_1'), (PX + 356, PY + 52))
    P.text(img, 0, PY + 54, 'Сведения о персонаже', P.GOLD, h=20, lh=10, cx=PX + 356)
    P.text(img, 0, PY + 76, 'Персонаж', P.GOLD, h=16, lh=8, cx=PX + 285)
    P.text(img, 0, PY + 76, 'Характеристики', P.GOLD, h=16, lh=8, cx=PX + 427)
    left = [('Ник', 'Steve', P.YELLOW), ('Класс', 'Мечник', P.GOLD), ('Профессия', 'нет', (85, 85, 85)),
            ('Уровень', '12', P.GREEN), ('Опыт', '340/1200 28%', P.WHITE), ('Здоровье', '17/20', RED),
            ('Энергия', '12/20', AQUA), ('Очки навыков', '3', P.GOLD), ('Очки параметров', '5', P.GOLD),
            ('Профиль', '№1 из 2', P.WHITE), ('Баланс', '1 250', P.YELLOW), ('В игре', '5 ч 12 мин', P.WHITE)]
    right = [('Сила', '14'), ('Ловкость', '9'), ('Выносливость', '11'), ('Удача', '3'), ('Урон', '6.5'),
             ('Скорость атаки', '1.6'), ('Броня', '12'), ('Защита', '4'), ('Крит. шанс', '7.5%'),
             ('Крит. урон', '150%'), ('Реген. маны', '0.5/с'), ('Скорость', '100%')]
    for c, rows in enumerate((left, [(n, v, P.WHITE) for n, v in right])):
        for i, (n, v, col) in enumerate(rows):
            y = PY + CHAR_ROW_Y + i * CHAR_DY
            P.text(img, PX + CHAR_NAME_X[c], y, n, P.GRAY, h=12, lh=8)
            P.text(img, 0, y, v, col, h=12, lh=8, right=PX + CHAR_VALUE_R[c])
    for x, label in ((218, 'Калибровка'), (360, 'Закрыть')):
        P.text(img, 0, PY + 244, label, P.YELLOW if x == 218 else P.WHITE, h=28, lh=10, cx=PX + x + 67)
    return img


def equipment():
    img = base(1)
    P.text(img, 0, PY + 52, 'Инвентарь и хранилище', P.GOLD, h=16, lh=10, cx=PX + 356)
    d = ImageDraw.Draw(img)
    # left: armour, jewels, off-hand
    for (x, y, col) in ((18, 74, (190, 190, 200)), (18, 106, (190, 190, 200)), (172, 106, (230, 200, 60)),
                        (172, 170, (150, 110, 60))):
        P.item_icon(img, PX + x + 14, PY + y + 14, 20, col)
    # bag: rows 4-6 of 9 = stash rows 1..3 (offset 3); hotbar below
    items = {(0, 0): ((128, 128, 128), 99), (0, 1): ((128, 128, 128), 35), (0, 2): ((200, 200, 220), 1),
             (1, 0): ((90, 60, 30), 99), (1, 4): ((220, 220, 240), 1), (2, 7): ((240, 230, 120), 64)}
    for (r, c), (col, n) in items.items():
        cx, cy = PX + GRID_X + c * GRID_STEP + 14, PY + GRID_Y[r] + 14
        P.item_icon(img, cx, cy, 20, col)
        if n > 1:
            P.text(img, 0, PY + GRID_Y[r] + 18, str(n), P.WHITE, h=10, lh=8, right=PX + GRID_X + c * GRID_STEP + 28)
    for c, col in enumerate(((200, 200, 220), (150, 90, 40), (180, 60, 60))):
        P.item_icon(img, PX + GRID_X + c * GRID_STEP + 14, PY + HOTBAR_Y + 14, 20, col)
    img.alpha_composite(P.tex('slot_selected'), (PX + GRID_X, PY + GRID_Y[0]))
    img.alpha_composite(P.tex('pf_arrow_up'), (PX + 222, PY + 204))
    img.alpha_composite(P.tex('pf_arrow_down'), (PX + 476, PY + 204))
    P.text(img, 0, PY + 204, 'Ряды 4–6 из 9 · хранилище', P.GRAY, h=16, lh=8, cx=PX + 356)
    P.text(img, 0, PY + 224, 'Выбрано: Булыжник ×99', P.GRAY, h=14, lh=8, cx=PX + 356)
    for x, label in ((218, 'Калибровка'), (360, 'Закрыть')):
        P.text(img, 0, PY + 244, label, P.YELLOW if x == 218 else P.WHITE, h=28, lh=10, cx=PX + x + 67)
    del d
    return img


if __name__ == '__main__':
    os.makedirs(P.OUT, exist_ok=True)
    for fn, name in ((character, 'rpg_character.png'), (equipment, 'rpg_equipment.png')):
        im = fn()
        im.convert('RGB').resize((im.width * 2, im.height * 2), Image.NEAREST).save(os.path.join(P.OUT, name))
        print(os.path.join(P.OUT, name))
