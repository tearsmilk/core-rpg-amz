#!/usr/bin/env python3
"""Сборка «3D-брони на шейдере» из .bbmodel (шаблон MythicArmors-подобный: кости helmet, torso, right_arm,
left_arm, hips, right_leg, left_leg, right_boot, left_boot).

    python3 -I nexo-items/tools/armor3d_build.py            # все комплекты из nexo-items/source/reforged/
    python3 -I nexo-items/tools/armor3d_build.py netherite  # один

Что получается (namespace azerus, комплект <set>):
  pack/assets/azerus/models/item/reforged/<set>_helmet.json      шлем — обычная 3D-модель предмета (display.head)
  pack/assets/azerus/textures/item/reforged/<set>_armor.png      текстура шлема (копия текстуры .bbmodel)
  pack/assets/azerus/textures/item/reforged/<set>_{chestplate,leggings,boots}.png   иконки 16x16 (рендер детали)
  pack/assets/azerus/equipment/reforged_<set>.json               equipment: слои humanoid и humanoid_leggings
  pack/assets/azerus/textures/entity/equipment/humanoid/reforged_<set>_NN.png          00 — 2D-запаска 64x32,
  pack/assets/azerus/textures/entity/equipment/humanoid_leggings/reforged_<set>_NN.png  01.. — слои данных 3D
  items/reforged_armor.yml                                        предметы Nexo (все комплекты)
  preview/reforged/budget.json                                    бюджет (слои, грани, вершины)
Формат слоёв — armor3d_codec.py; шейдер — entity_shaders/azerus_entity_1_21_11/assets/minecraft/shaders/{core/entity.vsh,include/armor3d.glsl}.
"""
import base64
import io
import json
import math
import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import armor3d_codec as C  # noqa: E402
from armor3d_raster import Camera, Raster, mix_light  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))   # nexo-items/
SRC = os.path.join(ROOT, "source", "reforged")
ASSETS = os.path.join(ROOT, "pack", "assets", "azerus")
NS = "azerus"

SETS = {   # id: (материал, прилагательное м.р., прилагательное мн.ч., цвет имени, строка описания)
    "leather": ("LEATHER", "кожаный", "кожаные", "#c58a52", "Дублёная кожа на клёпках."),
    "chainmail": ("CHAINMAIL", "кольчужный", "кольчужные", "#b8c0c8", "Кольца, сплетённые вчетверо."),
    "copper": ("COPPER", "медный", "медные", "#e0875a", "Медь, что не боится патины."),
    "iron": ("IRON", "железный", "железные", "#d8d8d8", "Перекован в латы с наплечниками."),
    "golden": ("GOLDEN", "золотой", "золотые", "#f3d34a", "Парадный доспех с гравировкой."),
    "diamond": ("DIAMOND", "алмазный", "алмазные", "#5fe0e0", "Грани алмаза в оправе из стали."),
    "netherite": ("NETHERITE", "незеритовый", "незеритовые", "#9b6b6b", "Тяжёлые латы из глубин Незера."),
}
PIECES = [("helmet", "HELMET", "HEAD", "шлем", False), ("chestplate", "CHESTPLATE", "CHEST", "нагрудник", False),
          ("leggings", "LEGGINGS", "LEGS", "поножи", True), ("boots", "BOOTS", "FEET", "ботинки", True)]
EQUIP_SOUND = {"LEATHER": "item.armor.equip_leather", "CHAINMAIL": "item.armor.equip_chain",
               "COPPER": "item.armor.equip_copper", "IRON": "item.armor.equip_iron",
               "GOLDEN": "item.armor.equip_gold", "DIAMOND": "item.armor.equip_diamond",
               "NETHERITE": "item.armor.equip_netherite"}


# ------------------------------------------------------------------------------------------ загрузка
def load_set(set_id):
    path = os.path.join(SRC, f"reforged_{set_id}_armor.bbmodel")
    bb = C.BBModel(path)
    src = bb.texture_source
    if src.startswith("data:image"):
        tex = Image.open(io.BytesIO(base64.b64decode(src.split(",", 1)[1]))).convert("RGBA")
    else:
        tex = Image.open(os.path.splitext(path)[0] + ".png").convert("RGBA")
    if tex.width > C.ATLAS or tex.height > C.ATLAS:
        raise SystemExit(f"{set_id}: текстура {tex.size} больше 128x128 — не влезет в атлас слоя")
    return bb, np.asarray(tex).copy()


def bone_faces(bb, bone):
    """Грани кости в пространстве её ванильной части: [(corners[4], uvs[4], flags, outward)].
    Начало координат — ВАНИЛЬНЫЙ шарнир части (C.PIVOT, ноги ±1.9), а не origin кости в .bbmodel (у шаблона ноги
    ±2.0): так в игре в покое каждая грань стоит ровно там же, где в Blockbench."""
    if bone not in bb.bones:
        return []
    origin = np.array(C.PIVOT[C.BONE_PART[bone][2]], float)
    out = []
    for e, chain in bb.bones[bone]["cubes"]:
        size = [abs(e["to"][i] - e["from"][i]) + 2 * float(e.get("inflate", 0) or 0) for i in range(3)]
        flat = min(size) < 1e-4
        for name, pts, uvs, nrm in bb.cube_faces(e, chain):
            pts = [p - origin for p in pts]
            pts, uvs = C.to_record_order(pts, uvs, nrm)
            area = np.linalg.norm(np.cross(pts[0] - pts[1], pts[2] - pts[1]))
            if area < 1e-6:
                continue
            flags = C.F_DOUBLE if flat else 0
            out.append(([tuple(map(float, p)) for p in pts], [tuple(map(float, u)) for u in uvs], flags,
                        nrm / np.linalg.norm(nrm)))
    return out


def assign_layers(faces):
    """Раскладка граней кости по слоям: на слой 6 мест (ванильные грани DOWN..SOUTH). Грань по возможности
    садится на ванильную грань с похожей нормалью (если клиент где-то отбрасывает невидимые грани модели —
    вместе с ней пропадёт обращённая туда же 3D-грань, которую и так не видно)."""
    buckets = {f: [] for f in range(6)}
    for fc in faces:
        n = fc[3]
        best = max(range(6), key=lambda f: float(np.dot(n, C.FACE_NORMAL[f])))
        buckets[best].append(fc)
    layers = []
    while any(buckets.values()):
        layer = {}
        for f in range(6):
            if buckets[f]:
                layer[f] = buckets[f].pop(0)
        for f in range(6):
            if f not in layer:
                big = max(range(6), key=lambda b: len(buckets[b]))
                if buckets[big]:
                    layer[f] = buckets[big].pop(0)
        layers.append(layer)
    return layers


# ------------------------------------------------------------------------------------- 2D-запаска
def project_fallback(bone_face_lists, atlas, list_type):
    """Проекция 3D-модели на ванильную раскладку 64x32: для каждой грани ванильной части смотрим на кость снаружи
    вдоль нормали (орто, 4x4 подвыборки на тексель) и берём ближайший цвет."""
    out = np.zeros((32, 64, 4), np.uint8)
    for part, faces in bone_face_lists:
        if not faces:
            continue
        g = C.GROW[list_type][part]
        for f in range(6):
            u1, v1, u2, v2 = C.face_uv_rect(part, f)
            L = [np.array(C.corner_local(part, f, c, g)) for c in range(4)]
            nrm = np.array(C.FACE_NORMAL[f], float)
            du, dv = u2 - u1, v2 - v1
            eu = (L[0] - L[1]) / du        # на 1 тексель по u
            ev = (L[2] - L[1]) / dv
            W, H = abs(du), abs(dv)
            ss = 4
            r = Raster(W * ss, H * ss)
            # экран: x = (u - min u)*ss, y = (v - min v)*ss; глубина = dot(p, n) (больше — снаружи)
            Muv = np.linalg.pinv(np.stack([eu, ev], 1))       # 3D → (du, dv) от L1
            umin, vmin = min(u1, u2), min(v1, v2)

            def scr(p):
                a = Muv @ (np.asarray(p) - L[1])
                return np.array([(u1 + a[0] - umin) * ss, (v1 + a[1] - vmin) * ss, float(np.dot(p, nrm))])

            for pts, uvs, flags, _ in faces:
                s4 = [scr(p) for p in pts]
                uv4 = [(u / atlas.shape[1], v / atlas.shape[0]) for u, v in uvs]
                r.quad(s4, uv4, atlas, "3d", (1.0, 1.0), double=True)
            col = r.color.reshape(H, ss, W, ss, 4)
            cov = col[..., 3].mean(axis=(1, 3))
            rgb = (col[..., :3] * col[..., 3:4]).sum(axis=(1, 3)) / np.maximum(col[..., 3].sum(axis=(1, 3)), 1e-6)[..., None]
            for yy in range(H):
                for xx in range(W):
                    if cov[yy, xx] >= 0.4:
                        out[vmin + yy, umin + xx, :3] = np.clip(rgb[yy, xx] * 255 + 0.5, 0, 255).astype(np.uint8)
                        out[vmin + yy, umin + xx, 3] = 255
    out[0, 0] = C.MAGIC
    return out


# ------------------------------------------------------------------------------------------ шлем
def helmet_model(bb, set_id):
    """Шлем → JSON-модель предмета. Голова игрока: шарнир (0,24,0) bb. Цепочка CustomHeadLayer:
    (0,−0.25,0) · Y180 · scale(0.625,−0.625,−0.625) · display.head · (−0.5): при display.head scale 1.6 и координатах
    модели m = b + (8, −20, 8) точка модели совпадает с точкой .bbmodel (проверено в armor3d_preview.py)."""
    elements = []
    shift = np.array([8.0, -20.0, 8.0])
    lo, hi = np.full(3, 1e9), np.full(3, -1e9)
    su, sv = 16.0 / bb.uv_w, 16.0 / bb.uv_h
    for e, chain in bb.bones.get("helmet", {"cubes": []})["cubes"]:
        inf = float(e.get("inflate", 0) or 0)
        f = np.array(e["from"], float) - inf
        t = np.array(e["to"], float) + inf
        R, tr = bb.cube_transform(e, chain)
        c = (f + t) / 2
        Tc = R @ c + tr
        nf, nt = f - c + Tc + shift, t - c + Tc + shift
        el = {"from": [round(x, 4) for x in nf], "to": [round(x, 4) for x in nt], "faces": {}}
        ex, ey, ez = C.euler_zyx(R)
        if max(abs(ex), abs(ey), abs(ez)) > 1e-4:
            el["rotation"] = {"origin": [round(x, 4) for x in Tc + shift], "x": round(ex, 4), "y": round(ey, 4),
                              "z": round(ez, 4)}
        for name, fc in e.get("faces", {}).items():
            if fc.get("texture") is None:
                continue
            u1, v1, u2, v2 = fc["uv"]
            face = {"uv": [round(u1 * su, 4), round(v1 * sv, 4), round(u2 * su, 4), round(v2 * sv, 4)],
                    "texture": "#0"}
            if fc.get("rotation"):
                face["rotation"] = int(fc["rotation"])
            el["faces"][name] = face
        if not el["faces"]:
            continue
        # габариты (для display.gui)
        hs = (t - f) / 2
        for sx in (-1, 1):
            for sy in (-1, 1):
                for sz in (-1, 1):
                    p = R @ (c + hs * (sx, sy, sz)) + tr + shift
                    lo, hi = np.minimum(lo, p), np.maximum(hi, p)
        for a in (*nf, *nt):
            if not -16 <= a <= 32:
                raise SystemExit(f"{set_id}: шлем выходит за −16..32 ({a})")
        elements.append(el)
    center = (lo + hi) / 2
    ext = float(max(hi - lo))
    gui_s = min(4.0, 0.625 * 16.0 / max(ext, 1.0) * 1.15)
    gtr = [round(-(center[0] - 8) * gui_s, 3), round(-(center[1] - 8) * gui_s, 3), 0]
    model = {
        "credit": "AZERUS armor3d_build.py из source/reforged/reforged_%s_armor.bbmodel" % set_id,
        "texture_size": [bb.tex_w, bb.tex_h],
        "textures": {"0": f"{NS}:item/reforged/{set_id}_armor", "particle": f"{NS}:item/reforged/{set_id}_armor"},
        "elements": elements,
        "display": {
            "head": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1.6, 1.6, 1.6]},
            "gui": {"rotation": [25, 145, 0], "translation": gtr, "scale": [round(gui_s, 4)] * 3},
            "ground": {"translation": [0, 2, 0], "scale": [0.4, 0.4, 0.4]},
            "fixed": {"rotation": [0, 180, 0], "scale": [round(gui_s * 1.1, 4)] * 3},
            "thirdperson_righthand": {"rotation": [0, 0, 0], "translation": [0, 2, -1], "scale": [0.4, 0.4, 0.4]},
            "thirdperson_lefthand": {"rotation": [0, 0, 0], "translation": [0, 2, -1], "scale": [0.4, 0.4, 0.4]},
            "firstperson_righthand": {"rotation": [0, 135, 0], "translation": [0, 2, 0], "scale": [0.4, 0.4, 0.4]},
            "firstperson_lefthand": {"rotation": [0, 135, 0], "translation": [0, 2, 0], "scale": [0.4, 0.4, 0.4]},
        },
    }
    return model, len(elements), sum(len(e["faces"]) for e in elements)


# ------------------------------------------------------------------------------------------ иконки
def render_icon(face_lists, atlas, size=16, yaw=180, pitch=0):
    """Деталь в покое, вид спереди-сбоку (как предмет в слоте), 8x суперсэмплинг → size×size, альфа 0/255,
    тёмный контур."""
    pts_all = []
    world_faces = []
    for pivot, faces in face_lists:
        for pts, uvs, flags, nrm in faces:
            w = [np.array([-(p[0] + pivot[0]), -(p[1] + pivot[1]), p[2] + pivot[2]]) for p in pts]   # модель → мир
            world_faces.append((w, uvs, flags, np.array([-nrm[0], -nrm[1], nrm[2]])))
            pts_all += w
    if not pts_all:
        return np.zeros((size, size, 4), np.uint8)
    P = np.array(pts_all)
    cam0 = Camera(yaw, pitch, 1.0, 0, 0, (P.min(0) + P.max(0)) / 2)
    V = np.array([cam0.view(p) for p in P])
    ext = max(np.ptp(V[:, 0]), np.ptp(V[:, 1]))
    ss = 8
    S = size * ss
    cam = Camera(yaw, pitch, (S - 2 * ss) / ext, S / 2, S / 2, (P.min(0) + P.max(0)) / 2)
    r = Raster(S, S)
    for w, uvs, flags, n in world_faces:
        s4 = [cam.screen(p) for p in w]
        uv4 = [(u / atlas.shape[1], v / atlas.shape[0]) for u, v in uvs]
        r.quad(s4, uv4, atlas, "3d", mix_light(n), double=bool(flags & C.F_DOUBLE))
    col = r.color.reshape(size, ss, size, ss, 4)
    cov = col[..., 3].mean(axis=(1, 3))
    rgb = (col[..., :3] * col[..., 3:4]).sum(axis=(1, 3)) / np.maximum(col[..., 3].sum(axis=(1, 3)), 1e-6)[..., None]
    out = np.zeros((size, size, 4), np.uint8)
    on = cov >= 0.35
    out[on, :3] = np.clip(rgb[on] * 255 * 1.08, 0, 255).astype(np.uint8)
    out[on, 3] = 255
    # контур: прозрачный пиксель рядом с непрозрачным → тёмный цвет соседа
    edge = np.zeros_like(on)
    for dy, dx in ((0, 1), (0, -1), (1, 0), (-1, 0)):
        edge |= np.roll(np.roll(on, dy, 0), dx, 1) & ~on
    edge[0, :] = edge[-1, :] = edge[:, 0] = edge[:, -1] = False
    nb = np.zeros((size, size, 3), np.float32)
    cnt = np.zeros((size, size), np.float32)
    for dy, dx in ((0, 1), (0, -1), (1, 0), (-1, 0)):
        o = np.roll(np.roll(on, dy, 0), dx, 1)
        nb += np.roll(np.roll(out[..., :3].astype(np.float32), dy, 0), dx, 1) * o[..., None]
        cnt += o
    em = edge & (cnt > 0)
    out[em, :3] = (nb[em] / cnt[em][:, None] * 0.35).astype(np.uint8)
    out[em, 3] = 255
    return out


# ------------------------------------------------------------------------------------------ сборка
def build_set(set_id):
    bb, tex = load_set(set_id)
    atlas = np.zeros((C.ATLAS, C.ATLAS, 4), np.uint8)
    h, w = tex.shape[:2]
    atlas[:h, :w, :3] = tex[..., :3]
    atlas[:h, :w, 3] = np.where(tex[..., 3] >= 128, C.ALPHA_ATLAS, 0)
    # цвет пустых текселей атласа — 0 (альфа 0 всё равно режется)
    atlas[atlas[..., 3] == 0] = 0

    faces = {b: bone_faces(bb, b) for bl in C.LIST_BONES.values() for b in bl}
    unknown = [b for b in bb.bones if b not in faces and b != "helmet"]
    if unknown:
        print(f"  {set_id}: кости без места (пропущены): {unknown}")
    budget = {"set": set_id, "bones": {b: len(f) for b, f in faces.items()}, "lists": {}}
    eq_layers = {}
    tex_dir = os.path.join(ASSETS, "textures", "entity", "equipment")
    for lt, bones in C.LIST_BONES.items():
        lname = C.LIST_NAMES[lt]
        per_bone = {b: assign_layers(faces[b]) for b in bones}
        n_layers = max(1, max(len(v) for v in per_bone.values()))
        os.makedirs(os.path.join(tex_dir, lname), exist_ok=True)
        # слой 0: 2D-запаска
        if lt == C.LIST_HUMANOID:
            fb = project_fallback([(C.PART_BODY, faces["torso"]), (C.PART_ARM, faces["right_arm"]),
                                   (C.PART_LEG, faces["right_boot"])], atlas, lt)
        else:
            fb = project_fallback([(C.PART_BODY, faces["hips"]), (C.PART_LEG, faces["right_leg"])], atlas, lt)
        names = [f"reforged_{set_id}_00"]
        Image.fromarray(fb, "RGBA").save(os.path.join(tex_dir, lname, names[0] + ".png"), optimize=True)
        for k in range(n_layers):
            recs = {}
            for slot, b in enumerate(bones):
                if k < len(per_bone[b]):
                    for vf, (pts, uvs, flags, _) in per_bone[b][k].items():
                        recs[slot * 6 + vf] = (pts, uvs, flags)
            img = C.encode_layer(atlas, lt, k + 1, n_layers, recs)
            nm = f"reforged_{set_id}_{k + 1:02d}"
            Image.fromarray(img, "RGBA").save(os.path.join(tex_dir, lname, nm + ".png"), optimize=True)
            names.append(nm)
        # лишние старые слои (если модель стала проще)
        k = n_layers + 1
        while os.path.exists(os.path.join(tex_dir, lname, f"reforged_{set_id}_{k:02d}.png")):
            os.remove(os.path.join(tex_dir, lname, f"reforged_{set_id}_{k:02d}.png"))
            k += 1
        eq_layers[lname] = [{"texture": f"{NS}:{n}"} for n in names]
        budget["lists"][lname] = {
            "layers_3d": n_layers, "layers_total": n_layers + 1,
            "faces": {b: len(faces[b]) for b in bones},
            "layers_needed": {b: math.ceil(len(faces[b]) / 6) for b in bones},
        }
    # вершины на игроке: нагрудник = тело+2 руки (72 вершины на слой), поножи = тело+2 ноги, ботинки = 2 ноги
    hl = budget["lists"]["humanoid"]["layers_total"]
    ll = budget["lists"]["humanoid_leggings"]["layers_total"]
    budget["vertices"] = {"chestplate": 72 * hl, "leggings": 72 * ll, "boots": 48 * hl}
    os.makedirs(os.path.join(ASSETS, "equipment"), exist_ok=True)
    with open(os.path.join(ASSETS, "equipment", f"reforged_{set_id}.json"), "w", encoding="utf-8") as fh:
        json.dump({"layers": eq_layers}, fh, indent=2)
        fh.write("\n")
    # шлем
    model, n_el, n_f = helmet_model(bb, set_id)
    budget["helmet"] = {"elements": n_el, "faces": n_f}
    mdir = os.path.join(ASSETS, "models", "item", "reforged")
    tdir = os.path.join(ASSETS, "textures", "item", "reforged")
    os.makedirs(mdir, exist_ok=True)
    os.makedirs(tdir, exist_ok=True)
    with open(os.path.join(mdir, f"{set_id}_helmet.json"), "w", encoding="utf-8") as fh:
        json.dump(model, fh, indent=1)
        fh.write("\n")
    t2 = tex.copy()
    t2[..., 3] = np.where(t2[..., 3] >= 128, 255, 0)
    t2[t2[..., 3] == 0] = 0
    Image.fromarray(t2, "RGBA").save(os.path.join(tdir, f"{set_id}_armor.png"), optimize=True)
    # иконки (атлас с альфой 255 для рендера)
    piv = {b: np.array(C.PIVOT[C.BONE_PART[b][2]]) for b in C.BONE_PART}
    icon_atlas = atlas.copy()
    icon_atlas[..., 3] = np.where(atlas[..., 3] > 0, 255, 0)
    # иконка: спереди; ноги разведены (поножи ±1.5 px, ботинки ±2.5 px и вид чуть сбоку), как у ванильных иконок
    spread = {"chestplate": (0.0, 180, 0), "leggings": (1.5, 180, 0), "boots": (2.5, 150, 8)}
    for piece in ("chestplate", "leggings", "boots"):
        dx, yaw, pitch = spread[piece]
        fl = []
        for b in C.SLOT_BONES[piece]:
            side = -1.0 if b.startswith("right") else (1.0 if b.startswith("left") else 0.0)
            fl.append((piv[b] + np.array([side * dx, 0, 0]), faces[b]))
        ic = render_icon(fl, icon_atlas, yaw=yaw, pitch=pitch)
        Image.fromarray(ic, "RGBA").save(os.path.join(tdir, f"{set_id}_{piece}.png"), optimize=True)
    return budget


def items_yml():
    lines = [
        "# «Перекованная» броня — 3D на шейдере (armor3d). СГЕНЕРИРОВАНО nexo-items/tools/armor3d_build.py — не править руками.",
        "# Нагрудник/поножи/ботинки: equippable.asset_id azerus:reforged_<комплект> — своё equipment-описание",
        "#   pack/assets/azerus/equipment/reforged_<комплект>.json (без CustomArmor, Nexo его не перегенерирует).",
        "#   Слой 00 — обычная 2D-броня (видна без шейдера), слои 01.. — данные 3D-модели (без шейдера невидимы).",
        "# Шлем: 3D-модель предмета на голове (equippable со слотом HEAD без asset_id — как forest_helmet у Nexo).",
        "# Кузнечный стол (отделка, улучшение) для этих предметов отключён: core-python custom_armor (main.py).",
        "",
    ]
    for set_id, (mat, adj_m, adj_pl, color, desc) in SETS.items():
        lines.append(f"# ---- {set_id} ----")
        for piece, suffix, slot, noun, plural in PIECES:
            pref = "Перекованные" if plural else "Перекованный"
            adj = adj_pl if plural else adj_m
            name = f"{pref} {adj} {noun}"
            lines += [
                f"azerus_reforged_{set_id}_{piece}:",
                f'  itemname: "<{color}>{name}"',
                f"  material: {mat}_{suffix}",
                "  lore:",
                f'    - "<gray>{desc}"',
                '    - "<dark_gray>Перекованный комплект"',
                "  Pack:",
            ]
            if piece == "helmet":
                lines.append(f"    model: {NS}:item/reforged/{set_id}_helmet")
            else:
                lines.append(f"    texture: {NS}:item/reforged/{set_id}_{piece}")
            lines += ["  Components:", "    enchantment_glint_override: false", "    equippable:", f"      slot: {slot}"]
            if piece != "helmet":
                lines.append(f"      asset_id: {NS}:reforged_{set_id}")
            lines.append(f"      equip_sound: {EQUIP_SOUND[mat]}")
        lines.append("")
    path = os.path.join(ROOT, "items", "reforged_armor.yml")
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines))
    return path


def main(argv):
    sets = argv or list(SETS)
    budgets = []
    for s in sets:
        if s not in SETS:
            raise SystemExit(f"нет комплекта {s}; есть {', '.join(SETS)}")
        print("•", s)
        b = build_set(s)
        hl, ll = b["lists"]["humanoid"], b["lists"]["humanoid_leggings"]
        print(f"  humanoid: слоёв 3D {hl['layers_3d']} ({hl['faces']}), leggings: {ll['layers_3d']} ({ll['faces']}), "
              f"шлем: {b['helmet']['faces']} граней")
        budgets.append(b)
    p = items_yml()
    print("предметы:", os.path.relpath(p, ROOT))
    os.makedirs(os.path.join(ROOT, "preview", "reforged"), exist_ok=True)
    bpath = os.path.join(ROOT, "preview", "reforged", "budget.json")
    old = {}
    if os.path.exists(bpath):
        old = {b["set"]: b for b in json.load(open(bpath, encoding="utf-8"))}
    for b in budgets:
        old[b["set"]] = b
    with open(bpath, "w", encoding="utf-8") as fh:
        json.dump([old[k] for k in SETS if k in old], fh, indent=1, ensure_ascii=False)
        fh.write("\n")


if __name__ == "__main__":
    main(sys.argv[1:])
