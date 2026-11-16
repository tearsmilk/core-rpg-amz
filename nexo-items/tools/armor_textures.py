#!/usr/bin/env python3
"""Текстуры кастомной брони AZERUS (Python-система custom_armor): слои брони 64x32 и иконки 16x16.

    python3 nexo-items/tools/armor_textures.py            # пишет в nexo-items/pack/assets/azerus/textures/item/armor/

Слои — раскладка ванильной брони (humanoid layer_1: шлем, нагрудник, наплечники, сапоги; layer_2: пояс и поножи).
Свет сверху-слева, пять тонов на материал (тени холоднее, блики теплее), обводка — тёмный тон материала.

СВЕЧЕНИЕ: пиксели рун/прожилок/камней рисуются с альфой ровно 251 — это маркер для core-шейдера брони
(minecraft/shaders/core/entity.fsh: только конвейеры брони, только альфа 251 → пиксель светится и пульсирует,
без освещения). Без шейдера (OptiFine/Iris, старый пак) это обычные яркие непрозрачные пиксели.
"""
import math
import os
import sys

from PIL import Image

GLOW_ALPHA = 251

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "pack", "assets", "azerus", "textures", "item", "armor")


def hx(s):
    s = s.lstrip("#")
    return tuple(int(s[i:i + 2], 16) for i in (0, 2, 4))


# Рампы: [тень, тёмный, база, светлый, блик]; outline — обводка/щели. Подобраны вручную со сдвигом тона.
SETS = {
    "guren": {  # 紅蓮 — багровый: тёмная багровая сталь, воронёная окантовка, угольки
        "plate": ["#3a0a16", "#6e1424", "#a31f33", "#d4334a", "#f2826e"],
        "plate_out": "#24060e",
        "trim": ["#1c1318", "#2e2228", "#463840", "#6a5a62", "#a0909a"],
        "trim_out": "#0e080b",
        "glow": ["#c8201a", "#ff6a2a", "#ffc070"],
        "leaf": None,
    },
    "tsukikori": {  # 月氷 — лунный лёд: серебристо-голубые пластины, индиго, лунный свет
        "plate": ["#2c3e5e", "#4f6f96", "#86a8cc", "#b9d4ee", "#f0f8ff"],
        "plate_out": "#1a2640",
        "trim": ["#161a42", "#232c64", "#36448c", "#5464b4", "#8c9ae0"],
        "trim_out": "#0a0c26",
        "glow": ["#2fb8ff", "#8fe8ff", "#e8ffff"],
        "leaf": None,
    },
    "morikashi": {  # 森樫 — дубрава: дуб, бронзовая оковка, листья, сок дерева
        "plate": ["#3a2412", "#5c3a1c", "#7e5530", "#a07446", "#c89a62"],
        "plate_out": "#24160a",
        "trim": ["#4a2e10", "#7a5020", "#a8742e", "#d4a050", "#f2d08a"],
        "trim_out": "#2a1806",
        "glow": ["#4ec83a", "#9cff6a", "#e6ffc8"],
        "leaf": ["#1e4216", "#2f6a22", "#4f9a30", "#7cc444", "#b0e070"],
        "grain": "wood",
    },
}


# ---------------------------------------------------------------------------------------------
# Раскладка кубов (как в скинах): origin (u, v), размер w x h x d → прямоугольники граней
# ---------------------------------------------------------------------------------------------

def cube(u, v, w, h, d):
    return {
        "top": (u + d, v, w, d), "bottom": (u + d + w, v, w, d),
        "right": (u, v + d, d, h), "front": (u + d, v + d, w, h),
        "left": (u + d + w, v + d, d, h), "back": (u + 2 * d + w, v + d, w, h),
    }


HEAD = cube(0, 0, 8, 8, 8)
BODY = cube(16, 16, 8, 12, 4)
ARM = cube(40, 16, 4, 12, 4)
LEG = cube(0, 16, 4, 12, 4)

# Освещённость граней (свет сверху-слева, смотрим спереди): база тона.
FACE_BASE = {"top": 3, "front": 2, "right": 2, "left": 1.6, "back": 1.6, "bottom": 0}


def _hash(x, y):
    h = (x * 73856093) ^ (y * 19349663) ^ 0x5bd1e995
    h = (h ^ (h >> 13)) * 0x27d4eb2d & 0xffffffff
    return ((h ^ (h >> 15)) & 0xffff) / 65535.0


def grain(kind, x, y):
    """Фактура материала кластерами (не шум): металл — короткие светлые штрихи вдоль пластины (2 px),
    дерево — тёмные волокна вдоль (3 px вертикально)."""
    if kind == "wood":
        return -0.75 if _hash(x, y // 3) < 0.22 else 0.0
    return 0.55 if _hash(x // 2, y) < 0.16 else 0.0


class Canvas:
    """Слой 64x32: пишем (материал, тон) или свечение."""

    def __init__(self, pal):
        self.pal = pal
        self.img = Image.new("RGBA", (64, 32), (0, 0, 0, 0))

    def put(self, x, y, mat, tone, glow=False):
        if mat == "plate":
            tone += grain(self.pal.get("grain", "metal"), x, y)
        tone = max(0, min(4, int(math.floor(tone + 0.5))))
        if mat == "glow":
            c = hx(self.pal["glow"][min(2, tone)])
            self.img.putpixel((x, y), c + (GLOW_ALPHA,))
            return
        if mat == "out":
            c = hx(self.pal["plate_out"])
        elif mat == "tout":
            c = hx(self.pal["trim_out"])
        else:
            c = hx(self.pal[mat][tone])
        self.img.putpixel((x, y), c + (255,))


def shade(face, x, y, w, h):
    """Тон пластины в точке грани: база грани, светлый верхний край, тёмный нижний (AO), слева светлее."""
    t = FACE_BASE[face]
    if y == 0:
        t += 1
    elif y == h - 1:
        t -= 1
    if face in ("front", "back", "top") and w > 2:
        if x == 0:
            t += 0.4
        elif x == w - 1:
            t -= 0.6
    return t


def paint_face(cv, rect, face, fn):
    """fn(x, y, w, h) → None (прозрачно) | (материал, тон)."""
    u, v, w, h = rect
    for y in range(h):
        for x in range(w):
            r = fn(x, y, w, h)
            if r is not None:
                cv.put(u + x, v + y, *r)


# ---------------------------------------------------------------------------------------------
# Части
# ---------------------------------------------------------------------------------------------

def helmet(cv, name):
    def top(x, y, w, h):
        t = shade("top", x, y, w, h)
        if x in (3, 4):  # гребень
            if name == "tsukikori" and y in (2, 3, 4):
                return ("glow", 1 if y != 3 else 2)
            if name == "morikashi" and y in (1, 2):
                return ("leaf", 3 if x == 3 else 2)
            return ("trim", 3 if x == 3 else 2)
        if name == "morikashi" and (x, y) in ((1, 1), (2, 1), (1, 2), (6, 5), (5, 6), (6, 6)):
            return ("leaf", 3 if y < 3 else 2)
        return ("plate", t)

    def front(x, y, w, h):
        t = shade("front", x, y, w, h)
        if y <= 1:
            if name == "guren" and y == 1 and x in (3, 4):
                return ("glow", 2)  # уголёк во лбу
            return ("plate", t + (1 if y == 0 else 0))
        if y == 2:
            if name == "tsukikori" and x in (3, 4):
                return ("glow", 2)  # лунный камень
            return ("trim", 3 if x < 4 else 2)
        if name == "guren":
            if y == 3:  # забрало с горящими прорезями глаз
                if x in (1, 2, 5, 6):
                    return ("glow", 1)
                return ("trim", 1 if x in (0, 7) else 2)
            if y in (4, 5) and x in (0, 1, 6, 7):
                return ("plate", t - (0 if x < 4 else 0.6))
            if y in (6, 7) and x in (0, 7):
                return ("plate", 1 if y == 7 else 2)
            if y == 4 and x in (3, 4):  # наносник
                return ("trim", 2)
            return None
        if name == "tsukikori":
            if y in (3, 4, 5, 6) and x in (0, 1, 6, 7):
                if y == 6:
                    return ("trim", 1)
                return ("plate", t - (0.4 if x == 1 or x == 6 else 0))
            return None
        # morikashi: деревянные щёки с бронзовой кромкой
        if y in (3, 4, 5) and x in (0, 1, 6, 7):
            return ("plate", t)
        if y == 6 and x in (0, 1, 6, 7):
            return ("trim", 1)
        if y == 2:
            return ("trim", 2)
        return None

    def side(face):
        def f(x, y, w, h):
            front_edge = 7 if face == "right" else 0  # колонка у лица
            t = shade(face, x, y, w, h)
            if y == 2:
                return ("trim", t)
            if y >= 6 and abs(x - front_edge) <= 2:
                return None
            if y == 7:
                return ("trim", 1)
            if name == "tsukikori" and y == 4 and abs(x - front_edge) in (3, 4):
                return ("glow", 1)
            if name == "morikashi" and y in (0, 1) and abs(x - front_edge) in (5, 6):
                return ("leaf", 3 - y)
            return ("plate", t)
        return f

    def back(x, y, w, h):
        t = shade("back", x, y, w, h)
        if y == 2 or y == 7:
            return ("trim", 1)
        if x in (3, 4) and 3 <= y <= 6:
            return ("trim", 2 if x == 3 else 1)
        return ("plate", t)

    paint_face(cv, HEAD["top"], "top", top)
    paint_face(cv, HEAD["front"], "front", front)
    paint_face(cv, HEAD["right"], "right", side("right"))
    paint_face(cv, HEAD["left"], "left", side("left"))
    paint_face(cv, HEAD["back"], "back", back)


def chest(cv, name):
    def front(x, y, w, h):
        t = shade("front", x, y, w, h)
        if y == 0:
            return ("trim", 3 if x < 4 else 2)  # воротник
        if y in (10, 11):
            return ("trim", 2 if y == 10 else 1)
        if y == 7:
            return ("out", 0)  # шов между грудью и животом
        if name == "guren":
            # горящий камень в центре и трещины-угольки от него
            if (x, y) in ((3, 3), (4, 3), (3, 4), (4, 4)):
                return ("glow", 2 if (x, y) == (3, 3) else 1)
            if (x, y) in ((2, 2), (1, 1), (5, 5), (6, 6), (5, 2), (2, 5), (1, 6)):
                return ("glow", 1 if (x, y) in ((1, 1), (6, 6), (1, 6)) else 0)
            if x in (3, 4) and y in (1, 2, 5, 6):
                return ("trim", 2)
        elif name == "tsukikori":
            # полумесяц
            moon = {(2, 2), (1, 3), (1, 4), (1, 5), (2, 6), (3, 6)}
            if (x, y) in moon:
                return ("glow", 2 if (x, y) in ((1, 4), (2, 6)) else 1)
            if (x, y) in ((5, 2), (6, 4), (5, 5)):
                return ("glow", 0)  # звёзды
            if x in (3, 4) and y in (1,):
                return ("trim", 2)
        else:
            # лист с прожилкой сока
            leaf = {(3, 2), (4, 2), (2, 3), (5, 3), (2, 4), (5, 4), (3, 5), (4, 5), (3, 3), (4, 4)}
            if (x, y) in ((3, 4), (4, 3), (3, 6), (4, 6)):
                return ("glow", 1)
            if (x, y) in leaf:
                return ("leaf", 3 if x < 4 else 2)
            if y in (4,) and x in (0, 7):
                return ("trim", 3)
            if x in (1, 6) and y in (8, 9) or (x in (2, 5) and y == 1):
                return ("trim", 4)  # заклёпки
            if x in (1, 5) and y in (1, 2, 3, 5, 6):
                return ("plate", t - 0.7)  # волокна дерева
        if y in (8, 9):
            if x in (0, 7):
                return ("plate", t - 0.5)
            return ("plate", t - (0.5 if y == 9 else 0) + (0.6 if y == 8 else 0))
        return ("plate", t)

    def back(x, y, w, h):
        t = shade("back", x, y, w, h)
        if y == 0 or y in (10, 11):
            return ("trim", 1)
        if x in (3, 4):
            return ("trim", 2 if x == 3 else 1)
        if y == 7:
            return ("out", 0)
        return ("plate", t)

    def side(face):
        def f(x, y, w, h):
            t = shade(face, x, y, w, h)
            if y == 0 or y in (10, 11):
                return ("trim", 1)
            if y == 7:
                return ("out", 0)
            return ("plate", t)
        return f

    def top(x, y, w, h):
        return ("trim", 3 if y < 2 else 2)

    paint_face(cv, BODY["front"], "front", front)
    paint_face(cv, BODY["back"], "back", back)
    paint_face(cv, BODY["right"], "right", side("right"))
    paint_face(cv, BODY["left"], "left", side("left"))
    paint_face(cv, BODY["top"], "top", top)


def arms(cv, name):
    """Наплечник (ряды 0-4) и наруч (ряды 8-10). Текстура одна на обе руки (левая — зеркально)."""
    def f(face):
        def g(x, y, w, h):
            t = shade(face, x, y, w, h)
            if y <= 3:
                if y == 1 and face == "right":
                    if name == "guren" and x in (1, 2):
                        return ("glow", 1)
                    if name == "tsukikori" and x in (1, 2):
                        return ("glow", 2 if x == 1 else 1)
                    if name == "morikashi":
                        return ("leaf", 3 if x < 2 else 2)
                return ("plate", t + (1 if y == 0 else 0))
            if y == 4:
                return ("trim", 2 if face in ("front", "right") else 1)
            if y in (8, 9, 10):
                if y == 8 or y == 10:
                    return ("trim", 2 if face in ("front", "right") else 1)
                if name == "guren" and face == "right" and x in (1, 2):
                    return ("glow", 0)
                return ("plate", t - 0.3)
            return None
        return g

    for face in ("front", "back", "right", "left"):
        paint_face(cv, ARM[face], face, f(face))
    paint_face(cv, ARM["top"], "top", lambda x, y, w, h: ("plate", 4 if y == 0 else 3))


def boots(cv, name):
    def f(face):
        def g(x, y, w, h):
            if y < 7:
                return None
            t = shade(face, x, y - 7, w, 5)
            if y == 7:
                return ("trim", 3 if face in ("front", "right") else 2)
            if y == 11:
                return ("tout", 0)  # подошва
            if face == "front" and y == 9 and x in (1, 2):
                if name == "guren":
                    return ("glow", 1)
                if name == "tsukikori":
                    return ("glow", 2 if x == 1 else 1)
                return ("trim", 4)
            if name == "morikashi" and y == 8 and face == "right":
                return ("leaf", 3)
            return ("plate", t)
        return g

    for face in ("front", "back", "right", "left"):
        paint_face(cv, LEG[face], face, f(face))
    paint_face(cv, LEG["bottom"], "bottom", lambda x, y, w, h: ("tout", 0))


def leggings(cv, name):
    def waist(face):
        def g(x, y, w, h):
            if y < 7:
                return None
            if y in (7, 8):
                if face == "front" and x in (3, 4):
                    if name == "guren":
                        return ("glow", 2 if y == 7 else 1)  # пряжка-уголёк
                    if name == "tsukikori":
                        return ("glow", 2) if y == 7 else ("trim", 4)
                    return ("trim", 4 if y == 7 else 3)  # бронзовая пряжка
                return ("trim", 2 if y == 7 else 1)
            t = shade(face, x, y - 9, w, 3)
            return ("plate", t)
        return g

    for face in ("front", "back", "right", "left"):
        paint_face(cv, BODY[face], face, waist(face))

    def leg(face):
        def g(x, y, w, h):
            if y > 9:
                return None
            t = shade(face, x, y, w, 10)
            if y == 0:
                return ("trim", 2)
            if y in (4, 5) and face == "front":  # наколенник
                if x in (1, 2):
                    return ("trim", 3 if y == 4 else 2)
                return ("trim", 1)
            if face == "right" and x in (1, 2) and 1 <= y <= 3:
                if name == "guren" and (x + y) % 2 == 0:
                    return ("glow", 1)
                if name == "tsukikori" and x == 1:
                    return ("glow", 2 if y == 2 else 1)
                if name == "morikashi" and y == 2:
                    return ("leaf", 3)
            if y == 9:
                return ("plate", 1)
            return ("plate", t)
        return g

    for face in ("front", "back", "right", "left"):
        paint_face(cv, LEG[face], face, leg(face))
    paint_face(cv, LEG["top"], "top", lambda x, y, w, h: ("trim", 2))


# ---------------------------------------------------------------------------------------------
# Иконки 16x16
# ---------------------------------------------------------------------------------------------
# o — обводка пластины, k — обводка окантовки; 1..5 — тоны пластины (1 тень … 5 блик); a..e — тоны окантовки;
# g/G — акцент (свечение комплекта: тусклый/яркий); L/l — лист (только дубрава, иначе — окантовка).

ICONS = {
    "helmet": [
        "................",
        "................",
        "................",
        ".....oooooo.....",
        "...oo455443oo...",
        "..o4554443332o..",
        "..o5443333322o..",
        "..kdddcgGcccbk..",
        "..o43ko..okk2o..",
        "..o32ko..ok21o..",
        "..o32o....o21o..",
        "..okko....okko..",
        "...oo......oo...",
        "................",
        "................",
        "................",
    ],
    "chestplate": [
        "................",
        "..kkko....okkk..",
        ".kdccbkoookbcbk.",
        ".kcbbo4554o2bak.",
        ".o44o544433o21o.",
        ".o54o44gG32o21o.",
        ".ooo44433g32ooo.",
        "...o4433332o....",
        "...o4433322o....",
        "...oooooooooo...",
        "...o43333221o...",
        "...kdccccbbbk...",
        "...kkkkkkkkkk...",
        "................",
        "................",
        "................",
    ],
    "leggings": [
        "................",
        "................",
        "...kkkkkkkkkk...",
        "...kddcgGcbbk...",
        "...o54443321o...",
        "...o5443o221o...",
        "...o443oo321o...",
        "...o44o..o31o...",
        "...kdck..kbak...",
        "...o43o..o21o...",
        "...o43o..o21o...",
        "...o43o..o21o...",
        "...o32o..o21o...",
        "...oooo..oooo...",
        "................",
        "................",
    ],
    "boots": [
        "................",
        "................",
        "................",
        "................",
        "................",
        "................",
        "..kkkk....kkkk..",
        "..kdck....kbbk..",
        "..o54o....o32o..",
        "..o43o....o21o..",
        ".o44g3o..o3g21o.",
        "o5443Go..oG321o.",
        "okkkkko..okkkko.",
        ".......................",
        "................",
        "................",
    ],
}


def icon(name, part):
    pal = SETS[name]
    rows = [r[:16].ljust(16, ".") for r in ICONS[part]]
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    trim = pal["trim"]
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            c = None
            if ch == "o":
                c = pal["plate_out"]
            elif ch == "k":
                c = pal["trim_out"]
            elif ch in "12345":
                c = pal["plate"][int(ch) - 1]
            elif ch in "abcde":
                c = trim["abcde".index(ch)]
            elif ch == "g":
                c = pal["glow"][1]
            elif ch == "G":
                c = pal["glow"][2]
            if c:
                im.putpixel((x, y), hx(c) + (255,))
    # Мотив комплекта поверх: дубраве — листочки на шлеме/нагруднике, лунному — блик-«звезда».
    if name == "morikashi":
        leaf = pal["leaf"]
        spots = {"helmet": [(5, 4, 3), (6, 4, 2), (5, 5, 2)], "chestplate": [(4, 3, 3), (11, 3, 2)],
                 "leggings": [(4, 5, 3)], "boots": [(3, 8, 3), (11, 8, 2)]}[part]
        for x, y, t in spots:
            im.putpixel((x, y), hx(leaf[t]) + (255,))
    return im


# ---------------------------------------------------------------------------------------------

def build(name):
    pal = SETS[name]
    l1 = Canvas(pal)
    helmet(l1, name)
    chest(l1, name)
    arms(l1, name)
    boots(l1, name)
    l2 = Canvas(pal)
    leggings(l2, name)
    return l1.img, l2.img


def main(out=OUT):
    os.makedirs(out, exist_ok=True)
    for name in SETS:
        l1, l2 = build(name)
        l1.save(os.path.join(out, name + "_armor_layer_1.png"))
        l2.save(os.path.join(out, name + "_armor_layer_2.png"))
        for part in ICONS:
            icon(name, part).save(os.path.join(out, name + "_" + part + ".png"))
        print("ok", name)


if __name__ == "__main__":
    main(*sys.argv[1:])
