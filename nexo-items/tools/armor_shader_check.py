#!/usr/bin/env python3
"""Проверка шейдера свечения брони (minecraft/shaders/core/entity.fsh): маркер «альфа ровно 251» не задевает чужое.

    python3 nexo-items/tools/armor_shader_check.py <client-1.21.11.jar> [папки/zip-паки ...]

1. Таблица истинности маркера для всех 256 значений альфы (как в GLSL: int(a/255*255 + 0.5) == 251).
2. Поиск пикселей с альфой 251. Шейдер трогает только конвейеры брони (ALPHA_CUTOUT + NO_OVERLAY +
   PER_FACE_LIGHTING: armor_cutout_no_cull, armor_decal_cutout_no_cull, armor_translucent) — значит, важны
   текстуры брони: entity/equipment/** (броня игроков, волков, лошадей…), trims/** и любые png с «armor»/
   «equipment» в пути в паке Nexo и внешних паках. Там должно быть 0 (кроме наших слоёв).
   Остальные совпадения (баннеры/щиты — entity_no_outline без ALPHA_CUTOUT, HUD-текстуры — шрифты) печатаются
   для сведения: эти конвейеры шейдер не трогает.
3. Наши слои: сколько светящихся пикселей.
"""
import io
import os
import sys
import zipfile

from PIL import Image

MARK = 251
OURS = "azerus/textures/item/armor/"


def glsl_marker(a8):
    a = a8 / 255.0  # что вернёт texture() для 8-битного канала
    return int(a * 255.0 + 0.5) == MARK


def count(img_bytes):
    im = Image.open(io.BytesIO(img_bytes))
    if im.mode not in ("RGBA", "LA", "P"):
        return 0, 0
    im = im.convert("RGBA")
    a = im.getchannel("A")
    hist = a.histogram()
    partial = sum(hist[1:255])
    return hist[MARK], partial


RELEVANT = ("/equipment/", "/trims/", "armor", "equipment")


def relevant(name):
    return any(r in name for r in RELEVANT) and OURS not in name


def scan_zip(path, prefix_filter=None):
    hits, total = [], 0
    with zipfile.ZipFile(path) as z:
        for n in z.namelist():
            if not n.endswith(".png") or "/textures/" not in n:
                continue
            if prefix_filter and not any(p in n for p in prefix_filter):
                continue
            total += 1
            m, _ = count(z.read(n))
            if m:
                hits.append((n, m))
    return hits, total


def scan_dir(path):
    hits, total, ours = [], 0, []
    for root, _, files in os.walk(path):
        for f in files:
            if not f.endswith(".png"):
                continue
            p = os.path.join(root, f)
            with open(p, "rb") as fh:
                m, _ = count(fh.read())
            total += 1
            rel = p.replace("\\", "/")
            if OURS in rel:
                ours.append((rel, m))
            elif m:
                hits.append((rel, m))
    return hits, total, ours


def main(argv):
    ok = True
    table = [a for a in range(256) if glsl_marker(a)]
    print("1. маркер срабатывает для альфы:", table)
    ok &= table == [MARK]
    vanilla = ["textures/entity/equipment/", "textures/trims/", "textures/entity/banner", "textures/entity/shield",
               "textures/entity/elytra", "textures/entity/player/", "textures/entity/"]
    hits, total = scan_zip(argv[0], vanilla)
    bad = [h for h in hits if relevant(h[0])]
    print(f"2. ваниль: {total} png (entity/*, trims); текстур брони с альфой {MARK}: {len(bad)}")
    for h in bad:
        print("   БРОНЯ:", h)
    for h in hits:
        if not relevant(h[0]):
            print("   (другой конвейер, не затронут)", h)
    ok &= not bad
    for extra in argv[1:]:
        if os.path.isdir(extra):
            h, t, ours = scan_dir(extra)
            bad = [x for x in h if relevant(x[0])]
            print(f"   {extra}: {t} png, чужих текстур брони с альфой {MARK}: {len(bad)} (прочих совпадений: {len(h) - len(bad)})")
            for x in bad:
                print("     БРОНЯ:", x)
            ok &= not bad
            for rel, m in ours:
                if m:
                    print(f"   наш слой {os.path.basename(rel)}: светится {m} px")
        elif extra.endswith(".zip"):
            h, t = scan_zip(extra)
            bad = [x for x in h if relevant(x[0])]
            print(f"   {os.path.basename(extra)}: {t} png, текстур брони с альфой {MARK}: {len(bad)} (прочих: {len(h) - len(bad)})")
            for x in bad:
                print("     БРОНЯ:", x)
            ok &= not bad
    print("ИТОГ:", "ok — маркер не задевает ничего, кроме наших слоёв" if ok else "ЕСТЬ СОВПАДЕНИЯ")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
