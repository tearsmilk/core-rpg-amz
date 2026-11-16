#!/usr/bin/env python3
"""Общая часть «3D-брони на шейдере» (armor3d): формат текстур-слоёв, ванильная сетка брони, разбор .bbmodel и
ТОЧНАЯ копия математики вершинного шейдера (assets/minecraft/shaders/include/armor3d.glsl) на Python.

Используется armor3d_build.py (сборка), armor3d_preview.py (программный рендер) и armor3d_check.py (проверки).
Запускать с python -I (модули ищутся по пути этого файла).

Формат текстуры слоя данных (ширина ровно 128):
  ряды 0..127   — цветовой атлас (копия текстуры .bbmodel): непрозрачный тексель → альфа 20, пустой → альфа 0.
                  Альфа 20 < 25.5 (ALPHA_CUTOUT 0.1), поэтому клиент без нашего шейдера весь слой отбрасывает.
  ряд 128       — заголовок: px0 = магия (A3 3D 7E, альфа 16); px1 = (версия, список 0/1, номер слоя, 16);
                  px2 = (число костей списка, граней на кость = 6, слоёв в списке, 16).
  ряды 129..    — записи, по одной на ряд: запись = кость_списка*6 + ванильная_грань (DOWN,UP,WEST,NORTH,EAST,SOUTH).
                  px 0..11: 4 угла × (x,y,z), каждое число 24 бита: v = (R<<16|G<<8|B)/65536 − 128 (пиксели,
                  система координат ванильной части модели: x = −x_bb, y = вниз, z = z_bb, начало — шарнир кости);
                  px 12..15: UV углов в пикселях атласа: u = (R<<4|G>>4)/16, v = ((G&15)<<8|B)/16;
                  px 16: флаги в R: bit0 грань есть, bit1 светится, bit2 красить цветом вершины, bit3 двусторонняя.
                  Альфа данных 24 (никогда не 0: упаковщики PNG обнуляют RGB у пикселей с альфой 0).
  Порядок углов записи — как у ванильных граней: нормаль наружу = −normalize(cross(T0−T1, T2−T1)).
Слой 0 каждого списка — обычная 2D-текстура 64x32 (раскладка ванильной брони, альфа 0/255) + магический пиксель
(0,0) = A3 3D 7E с альфой 16: шейдер прячет её, когда 3D работает; без шейдера/без subgroup её и видно.
"""
import json
import math

import numpy as np

# ------------------------------------------------------------------------------------------------ формат
MAGIC = (0xA3, 0x3D, 0x7E, 16)
VERSION = 1
ATLAS = 128            # ширина и высота цветового атласа
HEADER_ROW = 128
REC_ROW0 = 129
REC_W = 17
ALPHA_ATLAS = 20       # непрозрачный тексель атласа
ALPHA_DATA = 24        # пиксели записей
ALPHA_HEADER = 16

F_PRESENT, F_EMISSIVE, F_TINT, F_DOUBLE = 1, 2, 4, 8

LIST_HUMANOID, LIST_LEGGINGS = 0, 1
LIST_NAMES = {LIST_HUMANOID: "humanoid", LIST_LEGGINGS: "humanoid_leggings"}
# кости списка в порядке номеров (номер кости × 6 + грань = номер записи)
LIST_BONES = {
    LIST_HUMANOID: ["torso", "right_arm", "left_arm", "right_boot", "left_boot"],
    LIST_LEGGINGS: ["hips", "right_leg", "left_leg"],
}
BONE_LIST = {b: l for l, bs in LIST_BONES.items() for b in bs}
SLOT_BONES = {"chestplate": ["torso", "right_arm", "left_arm"], "leggings": ["hips", "right_leg", "left_leg"],
              "boots": ["right_boot", "left_boot"]}

# ------------------------------------------------------------------------------------- ванильная броня
# HumanoidModel.createBaseArmorMesh (1.21.11): тело (16,16) box(-4,0,-2, 8,12,4); правая рука (40,16)
# box(-3,-2,-2, 4,12,4) в (-5,2,0); левая — то же с mirror() box(-1,...) в (5,2,0); ноги (0,16) box(-2,0,-2, 4,12,4)
# c deformation.extend(-0.1) в (∓1.9,12,0). Деформация: нагрудник и ботинки — 1.0, поножи — 0.5.
PART_BODY, PART_ARM, PART_LEG = 0, 1, 2
PART_TEX = {PART_BODY: (16, 16), PART_ARM: (40, 16), PART_LEG: (0, 16)}
PART_MIN = {PART_BODY: (-4.0, 0.0, -2.0), PART_ARM: (-3.0, -2.0, -2.0), PART_LEG: (-2.0, 0.0, -2.0)}
PART_DIM = {PART_BODY: (8, 12, 4), PART_ARM: (4, 12, 4), PART_LEG: (4, 12, 4)}
GROW = {LIST_HUMANOID: {PART_BODY: 1.0, PART_ARM: 1.0, PART_LEG: 0.9},
        LIST_LEGGINGS: {PART_BODY: 0.5, PART_ARM: 0.5, PART_LEG: 0.4}}
# шарниры ванильных частей (пиксели модели, y вниз)
PIVOT = {"body": (0.0, 0.0, 0.0), "right_arm": (-5.0, 2.0, 0.0), "left_arm": (5.0, 2.0, 0.0),
         "right_leg": (-1.9, 12.0, 0.0), "left_leg": (1.9, 12.0, 0.0)}
# кость .bbmodel → (ванильная часть, зеркальная?, ванильный шарнир)
BONE_PART = {
    "torso": (PART_BODY, False, "body"), "hips": (PART_BODY, False, "body"),
    "right_arm": (PART_ARM, False, "right_arm"), "left_arm": (PART_ARM, True, "left_arm"),
    "right_leg": (PART_LEG, False, "right_leg"), "left_leg": (PART_LEG, True, "left_leg"),
    "right_boot": (PART_LEG, False, "right_leg"), "left_boot": (PART_LEG, True, "left_leg"),
}

FACES = ["down", "up", "west", "north", "east", "south"]   # порядок граней ModelPart.Cube
# углы граней в порядке вершин Polygon (без зеркала): биты (x_max?, y_max?, z_max?) коробки
FACE_BITS = [
    [(1, 0, 1), (0, 0, 1), (0, 0, 0), (1, 0, 0)],   # DOWN  (y min модели = верх на экране)
    [(1, 1, 0), (0, 1, 0), (0, 1, 1), (1, 1, 1)],   # UP
    [(0, 0, 0), (0, 0, 1), (0, 1, 1), (0, 1, 0)],   # WEST
    [(1, 0, 0), (0, 0, 0), (0, 1, 0), (1, 1, 0)],   # NORTH
    [(1, 0, 1), (1, 0, 0), (1, 1, 0), (1, 1, 1)],   # EAST
    [(0, 0, 1), (1, 0, 1), (1, 1, 1), (0, 1, 1)],   # SOUTH
]
FACE_NORMAL = [(0, -1, 0), (0, 1, 0), (-1, 0, 0), (0, 0, -1), (1, 0, 0), (0, 0, 1)]


def face_uv_rect(part, face):
    """(u1, v1, u2, v2) полигона грани, как в ModelPart.Cube (пиксели раскладки 64x32)."""
    tu, tv = PART_TEX[part]
    dx, dy, dz = PART_DIM[part]
    j, k, l, m = tu, tu + dz, tu + dz + dx, tu + dz + dx + dx
    n, o = tu + dz + dx + dz, tu + dz + dx + dz + dx
    p, q, r = tv, tv + dz, tv + dz + dy
    return [(k, p, l, q), (l, q, m, p), (j, q, k, r), (k, q, l, r), (l, q, n, r), (n, q, o, r)][face]


def corner_uv(rect, c):
    u1, v1, u2, v2 = rect
    return [(u2, v1), (u1, v1), (u1, v2), (u2, v2)][c]


def corner_local(part, face, c, grow):
    """Положение угла c грани (без зеркала) относительно шарнира части, пиксели модели."""
    mn = PART_MIN[part]
    dm = PART_DIM[part]
    b = FACE_BITS[face][c]
    return tuple((mn[i] + dm[i] + grow) if b[i] else (mn[i] - grow) for i in range(3))


def vanilla_part_quads(part, mirrored, list_type):
    """Квады ванильной части в порядке выдачи вершин: [(face, [(local_xyz, uv0_norm)]*4)].
    Зеркало (mirror()): x отражён, порядок вершин обратный (Polygon разворачивает массив)."""
    g = GROW[list_type][part]
    out = []
    for f in range(6):
        rect = face_uv_rect(part, f)
        verts = []
        for i in range(4):
            c = (3 - i) if mirrored else i
            x, y, z = corner_local(part, f, c, g)
            if mirrored:
                x = -x
            u, v = corner_uv(rect, c)
            verts.append(((x, y, z), (u / 64.0, v / 32.0)))
        out.append((f, verts))
    return out


# -------------------------------------------------------------------------------------- кодирование
def enc_coord(v):
    n = int(round((v + 128.0) * 65536.0))
    if not 0 <= n < (1 << 24):
        raise ValueError(f"координата {v} вне −128..128 px")
    return (n >> 16) & 255, (n >> 8) & 255, n & 255


def dec_coord(r, g, b):
    return float((r << 16) | (g << 8) | b) / 65536.0 - 128.0


def enc_uv(u, v):
    U, V = int(round(u * 16)), int(round(v * 16))
    if not (0 <= U < 4096 and 0 <= V < 4096):
        raise ValueError(f"UV {u},{v} вне 0..256 px")
    return U >> 4, ((U & 15) << 4) | (V >> 8), V & 255


def dec_uv(r, g, b):
    return float((r << 4) | (g >> 4)) / 16.0, float(((g & 15) << 8) | b) / 16.0


def records_count(list_type):
    return len(LIST_BONES[list_type]) * 6


def encode_layer(atlas_rgba, list_type, layer_index, n_layers, records):
    """atlas_rgba: (128,128,4) uint8 с альфой 0/20; records: {номер: (corners[4], uvs[4], flags)} → (H,128,4) uint8."""
    n = records_count(list_type)
    img = np.zeros((REC_ROW0 + n, ATLAS, 4), np.uint8)
    img[:ATLAS, :ATLAS] = atlas_rgba
    img[HEADER_ROW, 0] = MAGIC
    img[HEADER_ROW, 1] = (VERSION, list_type, layer_index, ALPHA_HEADER)
    img[HEADER_ROW, 2] = (len(LIST_BONES[list_type]), 6, n_layers, ALPHA_HEADER)
    for r in range(n):
        row = REC_ROW0 + r
        img[row, :REC_W] = (0, 0, 0, ALPHA_DATA)
        if r not in records:
            continue
        corners, uvs, flags = records[r]
        for t in range(4):
            for a in range(3):
                img[row, t * 3 + a, :3] = enc_coord(corners[t][a])
            img[row, 12 + t, :3] = enc_uv(*uvs[t])
        img[row, 16, :3] = (flags | F_PRESENT, 0, 0)
    return img


def decode_record(img, r):
    row = REC_ROW0 + r
    px = img[row].astype(int)
    flags = int(px[16, 0])
    corners = [tuple(dec_coord(*px[t * 3 + a, :3]) for a in range(3)) for t in range(4)]
    uvs = [dec_uv(*px[12 + t, :3]) for t in range(4)]
    return corners, uvs, flags


def is_data_layer(img):
    h, w = img.shape[:2]
    return w == ATLAS and h >= REC_ROW0 + 18 and tuple(int(x) for x in img[HEADER_ROW, 0]) == MAGIC


def is_fallback_layer(img):
    h, w = img.shape[:2]
    return (w, h) == (64, 32) and tuple(int(x) for x in img[0, 0]) == MAGIC


# ----------------------------------------------------------------------------------- вершинный шейдер
def _n(v):
    v = np.asarray(v, float)
    return v / max(np.linalg.norm(v), 1e-12)


HIDE = None  # «вершина убрана в клип-пространство (-3,-3,-3,1)»


def shade_quad(img, lanes, subgroup=True):
    """Копия armor3d.glsl для одного «квада» subgroup (4 соседние вершины).
    img — текстура слоя (H,W,4) uint8; lanes — 4 вершины [(Position, UV0, Normal)].
    Возвращает для каждой вершины (pos | HIDE, normal, uv_norm, state, flags):
      state 0 — ванильный путь (ничего не трогаем), 1 — 3D-грань, 2 — спрятана."""
    h, w = img.shape[:2]
    kind = 0
    if (w, h) == (64, 32) and tuple(int(x) for x in img[0, 0]) == MAGIC:
        kind = 2
    elif w == 128 and h >= REC_ROW0 + 18 and tuple(int(x) for x in img[HEADER_ROW, 0]) == MAGIC:
        kind = 1
    vanilla = [(np.asarray(p, float), np.asarray(nr, float), np.asarray(uv, float), 0.0, 0) for p, uv, nr in lanes]
    if kind == 0 or not subgroup:
        return vanilla
    qp = [np.asarray(l[0], float) for l in lanes]
    qu = [np.rint(np.asarray(l[1], float) * (64.0, 32.0)).astype(int) for l in lanes]
    umin, umax = min(q[0] for q in qu), max(q[0] for q in qu)
    vmin, vmax = min(q[1] for q in qu), max(q[1] for q in qu)
    part = face = -1
    rect = None
    for p in (PART_BODY, PART_ARM, PART_LEG):
        for f in range(6):
            r = face_uv_rect(p, f)
            if (min(r[0], r[2]), max(r[0], r[2]), min(r[1], r[3]), max(r[1], r[3])) == (umin, umax, vmin, vmax):
                part, face, rect = p, f, r
    if part < 0:
        return vanilla
    cs = []
    for q in qu:
        c = -1
        for k in range(4):
            if tuple(corner_uv(rect, k)) == (q[0], q[1]):
                c = k
        cs.append(c)
    if sorted(cs) != [0, 1, 2, 3]:
        return vanilla
    if kind == 2:  # слой 0 (2D): квад узнан — 3D работает, прячем
        return [(HIDE, v[1], v[2], 2.0, 0) for v in vanilla]
    hdr = img[HEADER_ROW, 1].astype(int)
    list_type = int(hdr[1])
    if list_type not in (0, 1):
        return vanilla
    W = [None] * 4
    for i in range(4):
        W[cs[i]] = qp[i]
    g = GROW[list_type][part]
    L = [np.array(corner_local(part, face, c, g)) for c in range(4)]
    nf = np.array(FACE_NORMAL[face], float)
    ea, eb = W[0] - W[1], W[2] - W[1]
    la, lb = L[0] - L[1], L[2] - L[1]
    mirrored = np.dot(np.cross(ea, eb), lanes[0][2]) * np.dot(np.cross(la, lb), nf) < 0.0
    if mirrored and part == PART_BODY:
        return vanilla
    flip = np.array([-1.0 if mirrored else 1.0, 1.0, 1.0])
    la, lb, nf = la * flip, lb * flip, nf * flip
    s = (np.linalg.norm(ea) + np.linalg.norm(eb)) / (np.linalg.norm(la) + np.linalg.norm(lb))
    sgn = 1.0 if np.dot(np.cross(la, lb), nf) >= 0 else -1.0
    Nw = _n(np.cross(ea, eb)) * sgn
    A = np.outer(ea, la / np.dot(la, la)) + np.outer(eb, lb / np.dot(lb, lb)) + np.outer(s * Nw, nf)
    # кость списка
    if part == PART_BODY:
        slot = 0
    elif list_type == LIST_HUMANOID:
        slot = (2 if mirrored else 1) if part == PART_ARM else (4 if mirrored else 3)
    else:
        if part != PART_LEG:
            return vanilla
        slot = 2 if mirrored else 1
    rec = slot * 6 + face
    row = REC_ROW0 + rec
    if row >= h:
        return vanilla
    corners, uvs, flags = decode_record(img, rec)
    if not flags & F_PRESENT:
        return [(HIDE, v[1], v[2], 2.0, 0) for v in vanilla]
    T = [np.array(c) for c in corners]
    nl = -np.cross(T[0] - T[1], T[2] - T[1])
    nrm_w = _n(A @ nl) if np.linalg.norm(nl) > 1e-9 else Nw
    out = []
    for i in range(4):
        c = cs[i]
        t = (3 - c) if mirrored else c
        Ls = L[c] * flip
        pos = qp[i] + A @ (T[t] - Ls)
        uv = np.array(uvs[t]) / (w, h)
        out.append((pos, nrm_w, uv, 1.0, flags))
    return out


# -------------------------------------------------------------------------------------- .bbmodel
DEG = math.pi / 180.0


def rot_zyx(r):
    """Поворот Blockbench / Minecraft (JOML rotationZYX): R = Rz·Ry·Rx, углы в градусах."""
    x, y, z = (a * DEG for a in r)
    cx, sx, cy, sy, cz, sz = math.cos(x), math.sin(x), math.cos(y), math.sin(y), math.cos(z), math.sin(z)
    Rx = np.array([[1, 0, 0], [0, cx, -sx], [0, sx, cx]])
    Ry = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]])
    Rz = np.array([[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]])
    return Rz @ Ry @ Rx


def euler_zyx(R):
    """Обратно: R = Rz·Ry·Rx → (x, y, z) в градусах."""
    y = math.asin(max(-1.0, min(1.0, -R[2, 0])))
    if abs(R[2, 0]) < 0.999999:
        x = math.atan2(R[2, 1], R[2, 2])
        z = math.atan2(R[1, 0], R[0, 0])
    else:
        x = math.atan2(-R[1, 2], R[1, 1])
        z = 0.0
    return x / DEG, y / DEG, z / DEG


# углы грани куба (bb, y вверх) как их видит текстура: TL, TR, BR, BL (соглашение Java-моделей)
def bb_face_corners(f, t, name):
    (x0, y0, z0), (x1, y1, z1) = f, t
    return {
        "north": [(x1, y1, z0), (x0, y1, z0), (x0, y0, z0), (x1, y0, z0)],
        "south": [(x0, y1, z1), (x1, y1, z1), (x1, y0, z1), (x0, y0, z1)],
        "east": [(x1, y1, z1), (x1, y1, z0), (x1, y0, z0), (x1, y0, z1)],
        "west": [(x0, y1, z0), (x0, y1, z1), (x0, y0, z1), (x0, y0, z0)],
        "up": [(x0, y1, z0), (x1, y1, z0), (x1, y1, z1), (x0, y1, z1)],
        "down": [(x0, y0, z1), (x1, y0, z1), (x1, y0, z0), (x0, y0, z0)],
    }[name]


BB_NORMAL = {"north": (0, 0, -1), "south": (0, 0, 1), "east": (1, 0, 0), "west": (-1, 0, 0), "up": (0, 1, 0),
             "down": (0, -1, 0)}


def bb_to_model(p):
    """Blockbench (y вверх, пиксели) → пространство модели Minecraft (x = −x, y = 24 − y, z = z)."""
    return np.array([-p[0], 24.0 - p[1], p[2]])


class BBModel:
    """Разобранный .bbmodel (формат free/generic, Blockbench 4–5): кубы с итоговыми преобразованиями по костям."""

    def __init__(self, path):
        self.path = path
        d = json.load(open(path, encoding="utf-8"))
        self.raw = d
        tex = d["textures"][0]
        self.tex_w = tex.get("width") or d.get("resolution", {}).get("width", 16)
        self.tex_h = tex.get("height") or d.get("resolution", {}).get("height", 16)
        self.uv_w = tex.get("uv_width") or d.get("resolution", {}).get("width", 16)
        self.uv_h = tex.get("uv_height") or d.get("resolution", {}).get("height", 16)
        self.texture_source = tex.get("source", "")
        self.groups = {g["uuid"]: g for g in d.get("groups", [])}
        self.elements = {e["uuid"]: e for e in d["elements"]}
        self.bones = {}      # имя верхней кости → {"origin":..., "cubes": [(element, chain)]}
        self.unassigned = []
        for node in d["outliner"]:
            if isinstance(node, str):
                self.unassigned.append(node)
                continue
            self._walk(node, [], None)

    def _group(self, node):
        if "name" in node:
            return node
        return self.groups[node["uuid"]]

    def _walk(self, node, chain, top):
        g = self._group(node)
        chain = chain + [(tuple(g.get("origin", (0, 0, 0))), tuple(g.get("rotation", (0, 0, 0))))]
        if top is None:
            top = g["name"]
            self.bones.setdefault(top, {"origin": tuple(g.get("origin", (0, 0, 0))), "cubes": []})
        if g.get("export") is False:
            return
        for c in node.get("children", []):
            if isinstance(c, str):
                e = self.elements.get(c)
                if e is None or e.get("export") is False or e.get("type", "cube") != "cube":
                    continue
                self.bones[top]["cubes"].append((e, chain))
            else:
                self._walk(c, chain, top)

    @staticmethod
    def cube_transform(e, chain):
        """Итоговое преобразование куба в пространстве bb: p → R·p + t (сначала свой поворот, затем группы
        от внутренней к внешней)."""
        R = np.eye(3)
        t = np.zeros(3)

        def apply(origin, rot):
            nonlocal R, t
            if not any(rot):
                return
            M = rot_zyx(rot)
            o = np.asarray(origin, float)
            R = M @ R
            t = M @ (t - o) + o

        apply(e.get("origin", (0, 0, 0)), e.get("rotation", (0, 0, 0)))
        for origin, rot in reversed(chain):
            apply(origin, rot)
        return R, t

    def cube_faces(self, e, chain, model_space=True):
        """Грани куба: [(name, corners[4] (TL,TR,BR,BL), uvs[4] в пикселях текстуры, outward_normal)]."""
        inf = float(e.get("inflate", 0) or 0)
        f = [a - inf for a in e["from"]]
        t = [a + inf for a in e["to"]]
        R, tr = self.cube_transform(e, chain)
        su, sv = self.tex_w / self.uv_w, self.tex_h / self.uv_h
        out = []
        for name in ("north", "east", "south", "west", "up", "down"):
            fc = e.get("faces", {}).get(name)
            if not fc or fc.get("texture") is None:
                continue
            u1, v1, u2, v2 = fc["uv"]
            uvs = [(u1, v1), (u2, v1), (u2, v2), (u1, v2)]
            k = (int(fc.get("rotation", 0) or 0) // 90) % 4
            uvs = [uvs[(i - k) % 4] for i in range(4)]
            uvs = [(u * su, v * sv) for u, v in uvs]
            pts = [R @ np.asarray(p, float) + tr for p in bb_face_corners(f, t, name)]
            nrm = R @ np.asarray(BB_NORMAL[name], float)
            if model_space:
                pts = [bb_to_model(p) for p in pts]
                nrm = np.array([-nrm[0], -nrm[1], nrm[2]])
            out.append((name, pts, uvs, nrm))
        return out


def to_record_order(pts, uvs, outward):
    """TL,TR,BR,BL → порядок записи: нормаль наружу = −cross(T0−T1, T2−T1) (как у ванильных граней)."""
    n = -np.cross(np.asarray(pts[0]) - pts[1], np.asarray(pts[2]) - pts[1])
    if np.dot(n, outward) < 0:
        return list(reversed(pts)), list(reversed(uvs))
    return list(pts), list(uvs)
