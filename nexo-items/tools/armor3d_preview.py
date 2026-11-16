#!/usr/bin/env python3
"""Программный рендер «перекованной» 3D-брони на позированном игроке — той же математикой, что шейдер.

    python3 -I nexo-items/tools/armor3d_preview.py [client-1.21.11.jar] [комплект ...]

Как в игре: строится ванильная сетка брони (ModelPart.Cube: грани, порядок вершин, UV, зеркало левых рук/ног,
деформация 1.0/0.5/0.9), части поворачиваются позой, вершины идут четвёрками («квад subgroup») в
armor3d_codec.shade_quad — Python-копию armor3d.glsl — и результат растеризуется с логикой entity.fsh.
Шлем — JSON-модель предмета через цепочку CustomHeadLayer (display.head).

Пишет nexo-items/preview/reforged/:
  <комплект>.png        ряд 1 — 3D (шейдер), 4 позы спереди-сбоку; ряд 2 — те же позы сзади;
                        ряд 3 — что видит клиент без шейдера / без subgroup (2D-слой 00 + 3D-шлем), спереди и сзади
  contact_sheet.png     все комплекты: 3D спереди, 3D сзади, 2D-запаска
  fallback_2d.png       развёртки слоёв 00 (humanoid, humanoid_leggings) всех комплектов, ×4
  icons.png             иконки и шлем в слоте
"""
import json
import math
import os
import sys
import zipfile

import numpy as np
from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import armor3d_codec as C  # noqa: E402
from armor3d_raster import Camera, Raster, mix_light  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO = os.path.dirname(ROOT)
ASSETS = os.path.join(ROOT, "pack", "assets", "azerus")
OUT = os.path.join(ROOT, "preview", "reforged")
JAR = os.path.join(REPO, "core-npc", "tools", ".cache", "client-1.21.11.jar")
SETS = ["leather", "chainmail", "copper", "iron", "golden", "diamond", "netherite"]
BG = (44, 47, 58, 255)

try:
    FONT = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 15)
except OSError:
    FONT = ImageFont.load_default()

# ------------------------------------------------------------------------------------------- позы
# xRot, yRot, zRot (радианы) частей HumanoidModel + сдвиги шарниров (пиксели модели), как setupAnim.
POSES = {
    "стоит": {"right_arm": (0, 0, 0.06), "left_arm": (0, 0, -0.06)},
    "шаг": {"right_arm": (-0.75, 0, 0.06), "left_arm": (0.75, 0, -0.06), "right_leg": (0.8, 0, 0),
            "left_leg": (-0.8, 0, 0), "body": (0, 0.05, 0), "head": (0.1, 0.2, 0)},
    "удар": {"right_arm": (-2.4, 0.35, 0.25), "left_arm": (0.3, 0, -0.15), "body": (0, 0.35, 0),
             "head": (-0.2, 0.35, 0), "right_leg": (0.25, 0, 0.03), "left_leg": (-0.25, 0, -0.03)},
    "присел": {"body": (0.5, 0, 0), "right_arm": (0.4, 0, 0.06), "left_arm": (0.4, 0, -0.06),
               "_offset": {"head": (0, 4.2, 0), "body": (0, 3.2, 0), "right_arm": (0, 5.2, 0), "left_arm": (0, 5.2, 0),
                           "right_leg": (0, 0.2, 4.0), "left_leg": (0, 0.2, 4.0)}},
}
PIVOTS = {"head": (0, 0, 0), "body": (0, 0, 0), "right_arm": (-5, 2, 0), "left_arm": (5, 2, 0),
          "right_leg": (-1.9, 12, 0), "left_leg": (1.9, 12, 0)}


def part_matrix(pose, part):
    """Часть → (R, t): точка части p (пиксели) → модель: t + R·p (ModelPart.translateAndRotate)."""
    rot = pose.get(part, (0, 0, 0))
    off = pose.get("_offset", {}).get(part, (0, 0, 0))
    R = C.rot_zyx((rot[0] / C.DEG, rot[1] / C.DEG, rot[2] / C.DEG))
    return R, np.array(PIVOTS[part], float) + off


def to_world(p):
    """Модель (y вниз) → мир превью (y вверх, ноги на 0): (−x, 24 − y, z)."""
    return np.array([-p[0], 24.0 - p[1], p[2]])


def dir_world(n):
    return np.array([-n[0], -n[1], n[2]])


# ------------------------------------------------------------------------------------------- игрок
def cube(tex_off, mn, dim, grow, mirrored, tex_size):
    """Как ModelPart.Cube: [(face, [(pos, uv)]*4, normal)] в координатах части."""
    tu, tv = tex_off
    dx, dy, dz = dim
    j, k, l, m = tu, tu + dz, tu + dz + dx, tu + dz + 2 * dx
    n, o = tu + 2 * dz + dx, tu + 2 * dz + 2 * dx
    p, q, r = tv, tv + dz, tv + dz + dy
    rects = [(k, p, l, q), (l, q, m, p), (j, q, k, r), (k, q, l, r), (l, q, n, r), (n, q, o, r)]
    out = []
    for f in range(6):
        u1, v1, u2, v2 = rects[f]
        cuv = [(u2, v1), (u1, v1), (u1, v2), (u2, v2)]
        vs = []
        for i in range(4):
            c = 3 - i if mirrored else i
            b = C.FACE_BITS[f][c]
            pos = [(mn[a] + dim[a] + grow) if b[a] else (mn[a] - grow) for a in range(3)]
            if mirrored:
                pos[0] = (mn[0] + dim[0] + grow) + (mn[0] - grow) - pos[0]
            vs.append((np.array(pos, float), (cuv[c][0] / tex_size[0], cuv[c][1] / tex_size[1])))
        nrm = np.array(C.FACE_NORMAL[f], float)
        if mirrored:
            nrm[0] = -nrm[0]
        out.append((f, vs, nrm))
    return out


SKIN_PARTS = {  # PlayerModel (широкий): часть → (texOffs, min, dim)
    "head": ((0, 0), (-4, -8, -4), (8, 8, 8)), "body": ((16, 16), (-4, 0, -2), (8, 12, 4)),
    "right_arm": ((40, 16), (-3, -2, -2), (4, 12, 4)), "left_arm": ((32, 48), (-1, -2, -2), (4, 12, 4)),
    "right_leg": ((0, 16), (-2, 0, -2), (4, 12, 4)), "left_leg": ((16, 48), (-2, 0, -2), (4, 12, 4)),
}


def draw_player(r, cam, pose, skin):
    for part, (to, mn, dim) in SKIN_PARTS.items():
        R, t = part_matrix(pose, part)
        for f, vs, nrm in cube(to, mn, dim, 0.0, False, (64, 64)):
            s4 = [cam.screen(to_world(t + R @ p)) for p, _ in vs]
            r.quad(s4, [uv for _, uv in vs], skin, "cutout", mix_light(dir_world(R @ nrm)))


# ------------------------------------------------------------------------------------------- броня
SLOT_PARTS = {  # слот → (список, [(ванильная часть, зеркало, часть позы)])
    "chestplate": (C.LIST_HUMANOID, [(C.PART_BODY, False, "body"), (C.PART_ARM, False, "right_arm"),
                                     (C.PART_ARM, True, "left_arm")]),
    "leggings": (C.LIST_LEGGINGS, [(C.PART_BODY, False, "body"), (C.PART_LEG, False, "right_leg"),
                                   (C.PART_LEG, True, "left_leg")]),
    "boots": (C.LIST_HUMANOID, [(C.PART_LEG, False, "right_leg"), (C.PART_LEG, True, "left_leg")]),
}


def load_layers(set_id):
    eq = json.load(open(os.path.join(ASSETS, "equipment", f"reforged_{set_id}.json"), encoding="utf-8"))
    out = {}
    for lname, layers in eq["layers"].items():
        imgs = []
        for layer in layers:
            ns, path = layer["texture"].split(":")
            imgs.append(np.asarray(Image.open(os.path.join(REPO, "nexo-items", "pack", "assets", ns, "textures",
                                                           "entity", "equipment", lname, path + ".png")).convert("RGBA")))
        out[lname] = imgs
    return out


def draw_armor(r, cam, pose, layers, subgroup, stats=None):
    for slot, (lt, parts) in SLOT_PARTS.items():
        for img in layers[C.LIST_NAMES[lt]]:
            for part, mirrored, pose_part in parts:
                R, t = part_matrix(pose, pose_part)
                for f, verts in C.vanilla_part_quads(part, mirrored, lt):
                    nrm = np.array(C.FACE_NORMAL[f], float)
                    if mirrored:
                        nrm[0] = -nrm[0]
                    nw = dir_world(R @ nrm)
                    lanes = [(to_world(t + R @ np.array(p)), uv, nw) for p, uv in verts]
                    res = C.shade_quad(img, lanes, subgroup=subgroup)
                    st = res[0][3]
                    if stats is not None:
                        stats[st] = stats.get(st, 0) + 1
                    if st == 2.0:
                        continue
                    if st == 1.0:
                        s4 = [cam.screen(v[0]) for v in res]
                        flags = res[0][4]
                        r.quad(s4, [v[2] for v in res], img, "3d", mix_light(res[0][1]),
                               emissive=bool(flags & C.F_EMISSIVE), double=bool(flags & C.F_DOUBLE))
                    else:
                        s4 = [cam.screen(v[0]) for v in res]
                        r.quad(s4, [v[2] for v in res], img, "cutout", mix_light(nw))


def helmet_quads(set_id):
    """JSON-модель шлема → грани в координатах головы (пиксели модели, относительно шарнира головы)."""
    mpath = os.path.join(ASSETS, "models", "item", "reforged", f"{set_id}_helmet.json")
    m = json.load(open(mpath, encoding="utf-8"))
    tex = np.asarray(Image.open(os.path.join(ASSETS, "textures", "item", "reforged", f"{set_id}_armor.png")).convert("RGBA"))
    head = m["display"]["head"]
    sc = np.array(head.get("scale", [1, 1, 1]), float)
    tr = np.array(head.get("translation", [0, 0, 0]), float) / 16.0
    rx, ry, rz = (a * C.DEG for a in head.get("rotation", [0, 0, 0]))
    Rd = (np.array([[1, 0, 0], [0, math.cos(rx), -math.sin(rx)], [0, math.sin(rx), math.cos(rx)]])
          @ np.array([[math.cos(ry), 0, math.sin(ry)], [0, 1, 0], [-math.sin(ry), 0, math.cos(ry)]])
          @ np.array([[math.cos(rz), -math.sin(rz), 0], [math.sin(rz), math.cos(rz), 0], [0, 0, 1]]))
    Hm = np.diag([-0.625, -0.625, 0.625])   # Y180 · scale(0.625, −0.625, −0.625)

    def chain(p):   # точка модели (0..16) → пиксели модели относительно головы
        q = tr + Rd @ (sc * (np.asarray(p) / 16.0 - 0.5))
        return (np.array([0, -0.25, 0]) + Hm @ q) * 16.0

    quads = []
    tw, th = m.get("texture_size", [16, 16])
    for e in m["elements"]:
        f, t = e["from"], e["to"]
        R = np.eye(3)
        o = np.zeros(3)
        if "rotation" in e:
            rot = e["rotation"]
            o = np.array(rot["origin"], float)
            if "axis" in rot:
                ang = [0, 0, 0]
                ang["xyz".index(rot["axis"])] = rot["angle"]
            else:
                ang = [rot.get("x", 0), rot.get("y", 0), rot.get("z", 0)]
            R = C.rot_zyx(ang)
        for name, fc in e["faces"].items():
            u1, v1, u2, v2 = fc["uv"]
            uvs = [(u1, v1), (u2, v1), (u2, v2), (u1, v2)]
            k = (int(fc.get("rotation", 0)) // 90) % 4
            uvs = [uvs[(i - k) % 4] for i in range(4)]
            uvs = [(u / 16.0, v / 16.0) for u, v in uvs]
            pts = [chain(R @ (np.asarray(p, float) - o) + o) for p in C.bb_face_corners(f, t, name)]
            n = R @ np.asarray(C.BB_NORMAL[name], float)
            nm = chain(np.asarray(C.BB_NORMAL[name]) * 0 + 8 + n) - chain(np.full(3, 8.0))   # направление в модели
            # TL,TR,BR,BL идут по часовой стрелке снаружи; FaceBakery выдаёт против часовой — разворачиваем
            quads.append((pts[::-1], uvs[::-1], nm / np.linalg.norm(nm)))
    return quads, tex


def draw_helmet(r, cam, pose, hq):
    quads, tex = hq
    R, t = part_matrix(pose, "head")
    for pts, uvs, nm in quads:
        s4 = [cam.screen(to_world(t + R @ p)) for p in pts]
        # предметы: задние грани отбрасываются (cull), свет как у сущностей
        r.quad(s4, uvs, tex, "3d", mix_light(dir_world(R @ nm)), double=False)


# ------------------------------------------------------------------------------------------- кадры
def frame(set_id, pose_name, yaw, layers, hq, skin, subgroup=True, size=(230, 300), stats=None):
    pose = POSES[pose_name]
    r = Raster(*size, bg=np.array(BG) / 255.0)
    cam = Camera(yaw, -12, 5.6, size[0] / 2, size[1] * 0.55, (0, 16.0, 0))
    draw_player(r, cam, pose, skin)
    draw_armor(r, cam, pose, layers, subgroup, stats)
    draw_helmet(r, cam, pose, hq)
    return r.image()


def label(img, text, xy=(6, 4), fill=(235, 235, 240)):
    ImageDraw.Draw(img).text(xy, text, font=FONT, fill=fill)
    return img


def load_skin(jar):
    with zipfile.ZipFile(jar) as z:
        from io import BytesIO
        return np.asarray(Image.open(BytesIO(z.read("assets/minecraft/textures/entity/player/wide/steve.png"))).convert("RGBA"))


FRONT, BACK = 150, -30   # yaw камеры: игрок смотрит на −z, камера с −z и чуть сбоку


def set_sheet(set_id, skin):
    layers = load_layers(set_id)
    hq = helmet_quads(set_id)
    poses = list(POSES)
    W, H = 230, 300
    sheet = Image.new("RGBA", (W * 4, H * 3 + 30), BG)
    label(sheet, f"reforged_{set_id}: ряд 1–2 — 3D (шейдер + subgroup), ряд 3 — без шейдера/без subgroup (слой 00)",
          (8, 6))
    stats = {}
    for i, pn in enumerate(poses):
        sheet.paste(label(frame(set_id, pn, FRONT, layers, hq, skin, stats=stats), pn), (i * W, 30))
        sheet.paste(label(frame(set_id, pn, BACK, layers, hq, skin), pn + ", сзади"), (i * W, 30 + H))
    for i, (pn, yaw, t) in enumerate([("стоит", FRONT, "2D, спереди"), ("стоит", BACK, "2D, сзади"),
                                      ("шаг", FRONT, "2D, шаг"), ("присел", 60, "2D, сбоку")]):
        sheet.paste(label(frame(set_id, pn, yaw, layers, hq, skin, subgroup=False), t), (i * W, 30 + 2 * H))
    sheet.save(os.path.join(OUT, f"{set_id}.png"))
    return layers, hq, stats


def main(argv):
    jar = argv[0] if argv and argv[0].endswith(".jar") else JAR
    sets = [a for a in argv if not a.endswith(".jar")] or SETS
    os.makedirs(OUT, exist_ok=True)
    skin = load_skin(jar)
    W, H = 230, 300
    contact = Image.new("RGBA", (W * len(sets), H * 3), BG)
    fb = Image.new("RGBA", (64 * 4 * 2 + 30, (32 * 4 + 24) * len(sets)), BG)
    icons = Image.new("RGBA", (4 * 72 + 120, 72 * len(sets)), BG)
    for i, s in enumerate(sets):
        print("•", s)
        layers, hq, stats = set_sheet(s, skin)
        print("  квады по состоянию (0 ваниль/срезано альфой, 1 — 3D, 2 — спрятано):", stats)
        contact.paste(label(frame(s, "стоит", FRONT, layers, hq, skin), s), (i * W, 0))
        contact.paste(label(frame(s, "шаг", BACK, layers, hq, skin), s + ", сзади"), (i * W, H))
        contact.paste(label(frame(s, "стоит", FRONT, layers, hq, skin, subgroup=False), s + ", 2D"), (i * W, 2 * H))
        y = i * (32 * 4 + 24)
        label(fb, s, (6, y + 2))
        for j, ln in enumerate(("humanoid", "humanoid_leggings")):
            im = Image.fromarray(layers[ln][0]).resize((256, 128), Image.NEAREST)
            fb.alpha_composite(im, (j * 270 + 6, y + 22))
        ImageDraw.Draw(icons).text((6, i * 72 + 26), s, font=FONT, fill=(235, 235, 240))
        for j, piece in enumerate(("chestplate", "leggings", "boots")):
            ic = Image.open(os.path.join(ASSETS, "textures", "item", "reforged", f"{s}_{piece}.png")).convert("RGBA")
            icons.alpha_composite(ic.resize((64, 64), Image.NEAREST), (120 + j * 72, i * 72 + 4))
        # шлем в слоте (display.gui)
        icons.alpha_composite(gui_helmet(s, hq), (120 + 3 * 72, i * 72 + 4))
    contact.save(os.path.join(OUT, "contact_sheet.png"))
    fb.save(os.path.join(OUT, "fallback_2d.png"))
    icons.save(os.path.join(OUT, "icons.png"))
    print("готово:", os.path.relpath(OUT, REPO))


def gui_helmet(set_id, hq):
    """Шлем в слоте по display.gui (орто, 64x64)."""
    m = json.load(open(os.path.join(ASSETS, "models", "item", "reforged", f"{set_id}_helmet.json"), encoding="utf-8"))
    g = m["display"]["gui"]
    sc = g["scale"][0]
    tr = np.array(g.get("translation", [0, 0, 0]), float)
    rx, ry = g["rotation"][0] * C.DEG, g["rotation"][1] * C.DEG
    Rd = (np.array([[1, 0, 0], [0, math.cos(rx), -math.sin(rx)], [0, math.sin(rx), math.cos(rx)]])
          @ np.array([[math.cos(ry), 0, math.sin(ry)], [0, 1, 0], [-math.sin(ry), 0, math.cos(ry)]]))
    _, tex = hq
    r = Raster(64, 64)
    for e in m["elements"]:
        R = np.eye(3)
        o = np.zeros(3)
        if "rotation" in e:
            o = np.array(e["rotation"]["origin"], float)
            R = C.rot_zyx([e["rotation"].get("x", 0), e["rotation"].get("y", 0), e["rotation"].get("z", 0)])
        for name, fc in e["faces"].items():
            u1, v1, u2, v2 = fc["uv"]
            uvs = [(u1, v1), (u2, v1), (u2, v2), (u1, v2)]
            k = (int(fc.get("rotation", 0)) // 90) % 4
            uvs = [(uvs[(i - k) % 4][0] / 16, uvs[(i - k) % 4][1] / 16) for i in range(4)]
            pts = [R @ (np.asarray(p, float) - o) + o for p in C.bb_face_corners(e["from"], e["to"], name)]
            q = [tr / 16 + Rd @ (sc * (p / 16.0 - 0.5)) for p in pts]          # блоки, центр слота = 0
            s4 = [np.array([32 + v[0] * 64, 32 - v[1] * 64, v[2]]) for v in q]
            n = Rd @ (R @ np.asarray(C.BB_NORMAL[name], float))
            r.quad(s4[::-1], uvs[::-1], tex, "3d", mix_light(np.array([n[0], n[1], n[2]])), double=False)
    return r.image()


if __name__ == "__main__":
    main(sys.argv[1:])
