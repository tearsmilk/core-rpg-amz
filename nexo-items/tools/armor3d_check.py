#!/usr/bin/env python3
"""Проверки «3D-брони на шейдере» (armor3d). Ничего не пишет, только читает.

    python3 -I nexo-items/tools/armor3d_check.py [client-1.21.11.jar] [папки/zip-паки ...]

1. Маркеры уникальны: ни одна текстура ванили (весь assets/minecraft/textures), паков из аргументов (по умолчанию —
   снимок прода server/plugins/Nexo/pack: assets + external_packs/*.zip) и других паков репозитория не выглядит как
   наш слой: 64x32 с A3 3D 7E/16 в (0,0) или ширина 128, высота ≥ 147 и A3 3D 7E/16 в (0,128).
2. Правила альфы: слои данных — только 0/16/20/24 (всё < 25.5 → без нашего шейдера слой целиком отрезается
   ALPHA_CUTOUT 0.1); слой 00 — 0/255 + магический пиксель 16; нигде нет 251 (свечение custom_armor).
3. Кодирование туда-обратно: каждая запись каждого слоя совпадает с гранью, которую даёт .bbmodel (xyz ± 1/65536 px,
   uv ± 1/32 px), лишних записей нет, все грани модели на месте.
4. Математика шейдера (Python-копия armor3d.glsl): 24 случайные позы на комплект (повороты по трём осям, масштаб 0.3..3, сдвиг)
   × все квады всех слоёв → вершины точно там, где грань .bbmodel в позе части (ошибка < 1e-3 px); перестановка
   вершин внутри квада ничего не меняет; квад из вершин разных граней не включает 3D (слой данных невидим, 2D видна).
5. Бюджет: слоёв на список, вершин на нагрудник/поножи/ботинки.
6. Файлы: equipment-json ↔ текстуры, предметы ↔ модели/иконки, копии entity.vsh и armor3d.glsl в rezusmenu/pack
   и nexo-items/pack совпадают байт-в-байт.
"""
import io
import json
import os
import random
import sys
import zipfile

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import armor3d_build as B  # noqa: E402
import armor3d_codec as C  # noqa: E402

ROOT = B.ROOT
REPO = os.path.dirname(ROOT)
JAR = os.path.join(REPO, "core-npc", "tools", ".cache", "client-1.21.11.jar")
OURS = "/textures/entity/equipment/"
PROBLEMS = []


def bad(msg):
    PROBLEMS.append(msg)
    print("   ✗", msg)


def looks_like_ours(data):
    try:
        im = Image.open(io.BytesIO(data))
        w, h = im.size
        if not ((w, h) == (64, 32) or (w == 128 and h >= 147)):
            return None
        a = np.asarray(im.convert("RGBA"))
    except Exception:
        return None
    if C.is_fallback_layer(a):
        return "2D-слой"
    if C.is_data_layer(a):
        return "слой данных"
    return None


def scan_zip(path, label):
    n = hits = 0
    with zipfile.ZipFile(path) as z:
        for name in z.namelist():
            if name.endswith(".png") and "/textures/" in name:
                n += 1
                k = looks_like_ours(z.read(name))
                if k:
                    hits += 1
                    bad(f"{label}: {name} выглядит как наш {k}")
    return n, hits


def scan_dir(path, label, skip_ours):
    n = hits = 0
    for root, _, files in os.walk(path):
        for f in files:
            if not f.endswith(".png"):
                continue
            p = os.path.join(root, f).replace("\\", "/")
            if skip_ours and "/azerus" + OURS in p and "/reforged_" in p:
                continue
            n += 1
            k = looks_like_ours(open(p, "rb").read())
            if k:
                hits += 1
                bad(f"{label}: {p} выглядит как наш {k}")
    return n, hits


def check_markers(jar, extra):
    print("1. уникальность маркеров")
    n, h = scan_zip(jar, "ваниль")
    print(f"   ваниль (весь jar): {n} png, похожих на наш слой: {h}")
    targets = extra or [os.path.join(REPO, "server", "plugins", "Nexo", "pack", "assets")] + sorted(
        os.path.join(REPO, "server", "plugins", "Nexo", "pack", "external_packs", z)
        for z in os.listdir(os.path.join(REPO, "server", "plugins", "Nexo", "pack", "external_packs"))
        if z.endswith(".zip"))
    for t in targets:
        if os.path.isdir(t):
            n, h = scan_dir(t, os.path.relpath(t, REPO), skip_ours=True)
        else:
            n, h = scan_zip(t, os.path.basename(t))
        print(f"   {os.path.relpath(t, REPO) if t.startswith(REPO) else t}: {n} png, похожих: {h}")
    for d in os.listdir(REPO):
        pk = os.path.join(REPO, d, "pack")
        if os.path.isdir(pk):
            n, h = scan_dir(pk, d + "/pack", skip_ours=True)
            print(f"   {d}/pack: {n} png (без наших reforged_*), похожих: {h}")


def equipment_layers(set_id):
    eq = json.load(open(os.path.join(B.ASSETS, "equipment", f"reforged_{set_id}.json"), encoding="utf-8"))
    out = {}
    for lname, layers in eq["layers"].items():
        imgs = []
        for layer in layers:
            ns, path = layer["texture"].split(":")
            f = os.path.join(ROOT, "pack", "assets", ns, "textures", "entity", "equipment", lname, path + ".png")
            if not os.path.exists(f):
                bad(f"{set_id}: нет текстуры {f}")
                continue
            imgs.append((os.path.relpath(f, REPO), np.asarray(Image.open(f).convert("RGBA"))))
        out[lname] = imgs
    return out


def check_alpha(set_id, layers):
    for lname, imgs in layers.items():
        for i, (f, img) in enumerate(imgs):
            vals = set(np.unique(img[..., 3]).tolist())
            if i == 0:
                if not C.is_fallback_layer(img):
                    bad(f"{f}: слой 00 не 64x32 с магическим пикселем")
                rest = img.copy()
                rest[0, 0] = (0, 0, 0, 0)
                v = set(np.unique(rest[..., 3]).tolist())
                if not v <= {0, 255}:
                    bad(f"{f}: в 2D-слое альфа {sorted(v - {0, 255})} (можно только 0/255)")
            else:
                if not C.is_data_layer(img):
                    bad(f"{f}: слой данных без заголовка")
                if not vals <= {0, 16, 20, 24}:
                    bad(f"{f}: альфа {sorted(vals - {0, 16, 20, 24})} (можно 0/16/20/24)")
                if img.shape[1] != C.ATLAS:
                    bad(f"{f}: ширина {img.shape[1]} ≠ 128")
                hdr = img[C.HEADER_ROW, 1]
                lt = 0 if lname == "humanoid" else 1
                if int(hdr[1]) != lt or int(hdr[2]) != i:
                    bad(f"{f}: заголовок список {hdr[1]}/слой {hdr[2]}, ожидалось {lt}/{i}")
                # пиксели записей — альфа 24, а не 0
                recs = img[C.REC_ROW0:, :C.REC_W, 3]
                if (recs != C.ALPHA_DATA).any():
                    bad(f"{f}: в записях есть альфа ≠ 24")
            if 251 in vals:
                bad(f"{f}: альфа 251 (маркер свечения)")


def check_roundtrip(set_id, layers):
    bb, _ = B.load_set(set_id)
    faces = {b: B.bone_faces(bb, b) for bl in C.LIST_BONES.values() for b in bl}
    n_ok = 0
    for lt, bones in C.LIST_BONES.items():
        imgs = layers[C.LIST_NAMES[lt]][1:]
        per_bone = {b: B.assign_layers(faces[b]) for b in bones}
        need = max(1, max(len(v) for v in per_bone.values()))
        if len(imgs) != need:
            bad(f"{set_id} {C.LIST_NAMES[lt]}: слоёв {len(imgs)}, нужно {need}")
        seen = {b: 0 for b in bones}
        for k, (f, img) in enumerate(imgs):
            for slot, b in enumerate(bones):
                expect = per_bone[b][k] if k < len(per_bone[b]) else {}
                for vf in range(6):
                    corners, uvs, flags = C.decode_record(img, slot * 6 + vf)
                    if vf not in expect:
                        if flags & C.F_PRESENT:
                            bad(f"{f}: лишняя запись {b}/{vf}")
                        continue
                    pts, euvs, eflags, _ = expect[vf]
                    if not flags & C.F_PRESENT or (flags & ~C.F_PRESENT) != eflags:
                        bad(f"{f}: флаги {b}/{vf}: {flags} ≠ {eflags}")
                    dx = max(abs(a - e) for c, ec in zip(corners, pts) for a, e in zip(c, ec))
                    du = max(abs(a - e) for c, ec in zip(uvs, euvs) for a, e in zip(c, ec))
                    if dx > 1.0 / 65536 or du > 1.0 / 32:
                        bad(f"{f}: {b}/{vf} расхождение xyz {dx:.2e}, uv {du:.2e}")
                    else:
                        n_ok += 1
                    seen[b] += 1
        for b in bones:
            if seen[b] != len(faces[b]):
                bad(f"{set_id}: у кости {b} в слоях {seen[b]} граней из {len(faces[b])}")
    return n_ok, faces


SLOTS = {"chestplate": (C.LIST_HUMANOID, [("torso", C.PART_BODY, False), ("right_arm", C.PART_ARM, False),
                                           ("left_arm", C.PART_ARM, True)]),
         "leggings": (C.LIST_LEGGINGS, [("hips", C.PART_BODY, False), ("right_leg", C.PART_LEG, False),
                                        ("left_leg", C.PART_LEG, True)]),
         "boots": (C.LIST_HUMANOID, [("right_boot", C.PART_LEG, False), ("left_boot", C.PART_LEG, True)])}


def rand_rot(rng):
    return C.rot_zyx([rng.uniform(-180, 180), rng.uniform(-90, 90), rng.uniform(-180, 180)])


def check_math(set_id, layers, faces, poses=24, seed=7):
    rng = random.Random(seed + hash(set_id) % 1000)
    max_err = 0.0
    n3d = 0
    for _ in range(poses):
        for slot, (lt, bones) in SLOTS.items():
            imgs = layers[C.LIST_NAMES[lt]]
            Rg, sg, tg = rand_rot(rng), rng.uniform(0.3, 3.0), np.array([rng.uniform(-50, 50) for _ in range(3)])
            for bone, part, mirrored in bones:
                Rp = rand_rot(rng)
                R = Rg @ Rp
                t = tg + sg * (Rg @ np.array([rng.uniform(-10, 10) for _ in range(3)]))
                E = np.array([sg * (R @ np.array(p)) + t for pts, *_ in faces[bone] for p in pts]).reshape(-1, 3)
                for li, (f, img) in enumerate(imgs):
                    for vf, verts in C.vanilla_part_quads(part, mirrored, lt):
                        nrm = np.array(C.FACE_NORMAL[vf], float)
                        if mirrored:
                            nrm[0] = -nrm[0]
                        lanes = [(sg * (R @ np.array(p)) + t, uv, R @ nrm) for p, uv in verts]
                        perm = list(range(4))
                        rng.shuffle(perm)
                        res = C.shade_quad(img, lanes)
                        res2 = C.shade_quad(img, [lanes[i] for i in perm])
                        for i, j in enumerate(perm):
                            a, b2 = res[j], res2[i]
                            if a[3] != b2[3] or (a[0] is not None and np.abs(a[0] - b2[0]).max() > 1e-6):
                                bad(f"{f}: результат зависит от порядка вершин в кваде")
                                return max_err, n3d
                        st = res[0][3]
                        if li == 0:
                            if st != 2.0:
                                bad(f"{f}: 2D-слой не спрятан при рабочем 3D")
                            continue
                        if st != 1.0:
                            continue
                        n3d += 1
                        rec = (C.LIST_BONES[lt].index(bone)) * 6 + vf
                        corners, uvs, _ = C.decode_record(img, rec)
                        for v in res:
                            # ближайшая ожидаемая точка
                            d = float(np.abs(E - v[0]).max(axis=1).min())
                            max_err = max(max_err, d)
                        # нормаль = поворот нормали грани
                        T = [np.array(c) for c in corners]
                        nl = -np.cross(T[0] - T[1], T[2] - T[1])
                        if np.linalg.norm(nl) > 1e-6:
                            nw = R @ (nl / np.linalg.norm(nl))
                            if np.abs(nw - res[0][1]).max() > 1e-5:
                                bad(f"{f}: нормаль грани {bone}/{vf} неверна")
                                return max_err, n3d
    if max_err > 1e-3:
        bad(f"{set_id}: вершины 3D-граней отклоняются на {max_err:.2e} px")
    # «сломанный» квад: вершины двух разных граней → ваниль (данные невидимы, 2D видна)
    lt = C.LIST_HUMANOID
    q = C.vanilla_part_quads(C.PART_BODY, False, lt)
    lanes = [(np.array(p), uv, np.array(C.FACE_NORMAL[q[0][0]], float)) for p, uv in q[0][1][:2] + q[3][1][2:]]
    for f, img in layers["humanoid"]:
        if C.shade_quad(img, lanes)[0][3] != 0.0:
            bad(f"{f}: квад из разных граней не отклонён")
    return max_err, n3d


def check_files():
    print("6. файлы")
    a = os.path.join(REPO, "nexo-items", "pack", "assets", "minecraft", "shaders")
    b = os.path.join(REPO, "rezusmenu", "pack", "assets", "minecraft", "shaders")
    for rel in ("core/entity.vsh", "include/armor3d.glsl"):
        fa, fb = os.path.join(a, rel), os.path.join(b, rel)
        same = os.path.exists(fa) and os.path.exists(fb) and open(fa, "rb").read() == open(fb, "rb").read()
        print(f"   {rel}: nexo-items == rezusmenu: {'да' if same else 'НЕТ'}")
        if not same:
            bad(f"{rel}: копии в nexo-items/pack и rezusmenu/pack различаются")
    if os.path.exists(os.path.join(a, "core", "rendertype_item_entity_translucent_cull.vsh")):
        bad("в nexo-items/pack лишняя копия общего rendertype_item_entity_translucent_cull")
    yml = open(os.path.join(ROOT, "items", "reforged_armor.yml"), encoding="utf-8").read()
    n_items = yml.count("\nazerus_reforged_")
    print(f"   items/reforged_armor.yml: предметов {n_items}")
    for set_id in B.SETS:
        for piece in ("chestplate", "leggings", "boots"):
            if not os.path.exists(os.path.join(B.ASSETS, "textures", "item", "reforged", f"{set_id}_{piece}.png")):
                bad(f"нет иконки {set_id}_{piece}")
        if not os.path.exists(os.path.join(B.ASSETS, "models", "item", "reforged", f"{set_id}_helmet.json")):
            bad(f"нет модели шлема {set_id}")
        if f"asset_id: azerus:reforged_{set_id}" not in yml:
            bad(f"в yml нет asset_id azerus:reforged_{set_id}")


def main(argv):
    jar = argv[0] if argv and argv[0].endswith(".jar") else JAR
    extra = [a for a in argv if not a.endswith(".jar")]
    check_markers(jar, extra)
    print("2–4. альфа, кодирование, математика шейдера; 5. бюджет")
    print(f"   {'комплект':10s} {'слоёв h/l':>9s} {'граней':>7s} {'записей ok':>10s} {'3D-квадов':>9s} {'макс. ошибка':>12s}"
          f"  вершин нагр./пон./бот.")
    for set_id in B.SETS:
        layers = equipment_layers(set_id)
        check_alpha(set_id, layers)
        n_ok, faces = check_roundtrip(set_id, layers)
        err, n3d = check_math(set_id, layers, faces)
        hl, ll = len(layers["humanoid"]), len(layers["humanoid_leggings"])
        nf = sum(len(v) for v in faces.values())
        print(f"   {set_id:10s} {hl - 1:>4d}/{ll - 1:<4d} {nf:>7d} {n_ok:>10d} {n3d:>9d} {err:>12.1e}"
              f"  {72 * hl}/{72 * ll}/{48 * hl}")
    check_files()
    print("ИТОГ:", "ok" if not PROBLEMS else f"ПРОБЛЕМ: {len(PROBLEMS)}")
    return 0 if not PROBLEMS else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
