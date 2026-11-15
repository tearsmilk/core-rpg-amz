#!/usr/bin/env python3
"""Программный рендер JSON-модели предмета Minecraft (кубы с поворотом, текстуры граней) — проверить модель глазами.

    python3 tools/render_model.py <assets> <ns:item/модель> <папка вывода> [префикс]

Рисует: gui-иконку (как в слоте, по display.gui), модель с четырёх сторон, вид от третьего лица (простой манекен
игрока, цепочка преобразований как у ItemInHandLayer: рука → X−90° → Y180° → сдвиг (1/16, 0.125, −0.625) →
display → −0.5), вид от первого лица (перспектива 70°, сдвиг руки (0.56, −0.52, −0.72)) и ночной вид (кубы с
light_emission светятся, остальное в темноте). Развёртка UV — как в Minecraft (north: u от x_max к x_min и т. д.).
Свет граней — приближение освещения предметов (две лампы + 0.4 окружения). Кадры замаха: передайте модель кадра.
"""
import json, math, os, sys

import numpy as np
from PIL import Image

DEG = math.pi / 180


def load_model(assets, ref):
    ns, path = ref.split(':', 1) if ':' in ref else ('minecraft', ref)
    j = json.load(open(os.path.join(assets, ns, 'models', path + '.json'), encoding='utf-8'))
    if 'parent' in j:
        par = load_model(assets, j['parent'])
        disp = dict(par.get('display', {}))
        disp.update(j.get('display', {}))
        out = dict(par)
        out.update({k: v for k, v in j.items() if k not in ('parent', 'display')})
        out['display'] = disp
        return out
    return j


def tex_image(assets, ref):
    ns, path = ref.split(':', 1)
    return np.asarray(Image.open(os.path.join(assets, ns, 'textures', path + '.png')).convert('RGBA')).astype(np.float32)


# ------------------------------------------------------------------------------------------- матрицы
def T(x, y, z):
    m = np.eye(4)
    m[:3, 3] = (x, y, z)
    return m


def S(x, y=None, z=None):
    y = x if y is None else y
    z = x if z is None else z
    return np.diag([x, y, z, 1.0])


def Rx(a):
    c, s = math.cos(a * DEG), math.sin(a * DEG)
    m = np.eye(4)
    m[1, 1], m[1, 2], m[2, 1], m[2, 2] = c, -s, s, c
    return m


def Ry(a):
    c, s = math.cos(a * DEG), math.sin(a * DEG)
    m = np.eye(4)
    m[0, 0], m[0, 2], m[2, 0], m[2, 2] = c, s, -s, c
    return m


def Rz(a):
    c, s = math.cos(a * DEG), math.sin(a * DEG)
    m = np.eye(4)
    m[0, 0], m[0, 1], m[1, 0], m[1, 1] = c, -s, s, c
    return m


def display(model, ctx, left=False):
    d = model.get('display', {})
    if ctx not in d and ctx.endswith('lefthand'):
        d = {ctx: d.get(ctx.replace('lefthand', 'righthand'), {})}
    d = d.get(ctx, {})
    r = d.get('rotation', [0, 0, 0])
    t = [max(-80, min(80, v)) / 16 for v in d.get('translation', [0, 0, 0])]
    s = [max(-4, min(4, v)) for v in d.get('scale', [1, 1, 1])]
    rx, ry, rz = r
    if left:
        ry, rz = -ry, -rz
        t[0] = -t[0]
    # Quaternionf.rotationXYZ(x, y, z) = Rx·Ry·Rz
    return T(*t) @ Rx(rx) @ Ry(ry) @ Rz(rz) @ S(*s) @ T(-0.5, -0.5, -0.5) @ S(1 / 16)


# ------------------------------------------------------------------------------------------- грани модели
def corners(fr, to, face):
    (x1, y1, z1), (x2, y2, z2) = fr, to
    # точки (u,v) = (0,0),(1,0),(1,1),(0,1) в развёртке Minecraft
    lerp = lambda a, b, s: a + (b - a) * s
    def pt(u, v):
        if face == 'north':
            return (lerp(x2, x1, u), lerp(y2, y1, v), z1)
        if face == 'south':
            return (lerp(x1, x2, u), lerp(y2, y1, v), z2)
        if face == 'east':
            return (x2, lerp(y2, y1, v), lerp(z2, z1, u))
        if face == 'west':
            return (x1, lerp(y2, y1, v), lerp(z1, z2, u))
        if face == 'up':
            return (lerp(x1, x2, u), y2, lerp(z1, z2, v))
        return (lerp(x1, x2, u), y1, lerp(z2, z1, v))
    return [pt(0, 0), pt(1, 0), pt(1, 1), pt(0, 1)]


def element_rot(e):
    r = e.get('rotation')
    if not r:
        return np.eye(4)
    o = r['origin']
    R = {'x': Rx, 'y': Ry, 'z': Rz}[r['axis']](r['angle'])
    if r.get('rescale'):
        k = 1 / math.cos(abs(r['angle']) * DEG)
        sc = [k, k, k]
        sc['xyz'.index(r['axis'])] = 1
        R = R @ S(*sc)
    return T(*o) @ R @ T(-o[0], -o[1], -o[2])


def quads(model, textures):
    out = []
    for e in model['elements']:
        M = element_rot(e)
        for face, f in e.get('faces', {}).items():
            uv = f.get('uv')
            if uv is None:
                continue
            tex = model['textures'][f['texture'].lstrip('#')]
            while tex.startswith('#'):
                tex = model['textures'][tex[1:]]
            pts = [M @ np.array([*p, 1.0]) for p in corners(e['from'], e['to'], face)]
            u1, v1, u2, v2 = uv
            uvs = [(u1, v1), (u2, v1), (u2, v2), (u1, v2)]
            rot = f.get('rotation', 0) // 90
            uvs = uvs[rot:] + uvs[:rot]
            out.append((np.array(pts), uvs, tex, e.get('light_emission', 0), e.get('shade', True)))
    return out


# ------------------------------------------------------------------------------------------- растеризация
class Canvas:
    def __init__(self, w, h, bg=(32, 30, 38)):
        self.w, self.h = w, h
        self.rgb = np.zeros((h, w, 3), np.float32)
        self.rgb[:] = bg
        self.z = np.full((h, w), np.inf, np.float32)

    def tri(self, P, UV, W, tex, light):
        """P: 3×(x,y,z) экран (z — глубина, меньше ближе), UV в пикселях текстуры, W — 1/w для перспективы."""
        x0 = max(int(np.floor(P[:, 0].min())), 0)
        x1 = min(int(np.ceil(P[:, 0].max())), self.w - 1)
        y0 = max(int(np.floor(P[:, 1].min())), 0)
        y1 = min(int(np.ceil(P[:, 1].max())), self.h - 1)
        if x0 > x1 or y0 > y1:
            return
        ys, xs = np.mgrid[y0:y1 + 1, x0:x1 + 1].astype(np.float32) + 0.5
        (ax, ay), (bx, by), (cx, cy) = P[0, :2], P[1, :2], P[2, :2]
        den = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy)
        if abs(den) < 1e-9:
            return
        l0 = ((by - cy) * (xs - cx) + (cx - bx) * (ys - cy)) / den
        l1 = ((cy - ay) * (xs - cx) + (ax - cx) * (ys - cy)) / den
        l2 = 1 - l0 - l1
        inside = (l0 >= -1e-6) & (l1 >= -1e-6) & (l2 >= -1e-6)
        if not inside.any():
            return
        z = l0 * P[0, 2] + l1 * P[1, 2] + l2 * P[2, 2]
        wsum = l0 * W[0] + l1 * W[1] + l2 * W[2]
        u = (l0 * UV[0][0] * W[0] + l1 * UV[1][0] * W[1] + l2 * UV[2][0] * W[2]) / wsum
        v = (l0 * UV[0][1] * W[0] + l1 * UV[1][1] * W[1] + l2 * UV[2][1] * W[2]) / wsum
        th, tw = tex.shape[:2]
        ui = np.clip(np.floor(u).astype(int), 0, tw - 1)
        vi = np.clip(np.floor(v).astype(int), 0, th - 1)
        col = tex[vi, ui]
        zb = self.z[y0:y1 + 1, x0:x1 + 1]
        ok = inside & (col[..., 3] > 25) & (z < zb)
        zb[ok] = z[ok]
        self.rgb[y0:y1 + 1, x0:x1 + 1][ok] = np.clip(col[..., :3][ok] * light, 0, 255)

    def flat(self, P, color):
        tex = np.zeros((1, 1, 4), np.float32)
        tex[0, 0] = (*color, 255)
        self.tri(P, [(0, 0)] * 3, np.ones(3), tex, 1.0)

    def image(self):
        return Image.fromarray(self.rgb.astype(np.uint8))


LIGHT0 = np.array([0.2, 1.0, -0.7]) / np.linalg.norm([0.2, 1.0, -0.7])
LIGHT1 = np.array([-0.2, 1.0, 0.7]) / np.linalg.norm([-0.2, 1.0, 0.7])


def shade(n, lights=(LIGHT0, LIGHT1)):
    return min(1.0, 0.4 + 0.6 * sum(max(0.0, float(n @ l)) for l in lights))


def draw(canvas, qs, M, texs, project, lights=(LIGHT0, LIGHT1), world=1.0):
    """M — модель → мир (блоки), project(мир) → (x, y, z, w)."""
    for pts, uvs, tex, emis, sh in qs:
        wp = (M @ pts.T).T[:, :3]
        n = np.cross(wp[1] - wp[0], wp[3] - wp[0])
        nl = np.linalg.norm(n)
        if nl < 1e-12:
            continue
        n = n / nl
        lit = shade(n, lights) if sh else 1.0
        lvl = max(world, emis / 15.0)
        img = texs[tex]
        th, tw = img.shape[:2]
        scr = [project(p) for p in wp]
        if any(s is None for s in scr):
            continue
        P = np.array([s[:3] for s in scr], np.float32)
        Wt = np.array([1.0 / s[3] for s in scr], np.float32)
        UV = [(u * tw / 16, v * th / 16) for u, v in uvs]
        for a, b, c in ((0, 1, 2), (0, 2, 3)):
            canvas.tri(P[[a, b, c]], [UV[a], UV[b], UV[c]], Wt[[a, b, c]], img, lit * lvl)


def ortho(canvas_w, canvas_h, center, scale, view):
    """view — матрица мира в вид (камера смотрит вдоль −Z вида)."""
    def pr(p):
        v = view @ np.array([*p, 1.0])
        return ((v[0] - center[0]) * scale + canvas_w / 2, -(v[1] - center[1]) * scale + canvas_h / 2, -v[2], 1.0)
    return pr


def persp(canvas_w, canvas_h, fov=70):
    f = 1 / math.tan(fov * DEG / 2)
    def pr(p):
        x, y, z = p
        if z > -0.05:
            return None
        return (canvas_w / 2 + (f * x / -z) * canvas_h / 2, canvas_h / 2 - (f * y / -z) * canvas_h / 2, -z, -z)
    return pr


def box_quads(fr, to, M):
    """Кубик манекена: 6 граней цветом по нормали."""
    (x1, y1, z1), (x2, y2, z2) = fr, to
    out = []
    for face in ('north', 'south', 'east', 'west', 'up', 'down'):
        pts = np.array([M @ np.array([*p, 1.0]) for p in corners(fr, to, face)])
        out.append(pts)
    return out


def draw_box(canvas, fr, to, M, project, color, lights=(LIGHT0, LIGHT1)):
    for pts in box_quads(fr, to, M):
        wp = pts[:, :3]
        n = np.cross(wp[1] - wp[0], wp[3] - wp[0])
        nl = np.linalg.norm(n)
        if nl < 1e-12:
            continue
        lit = shade(n / nl, lights)
        scr = [project(p) for p in wp]
        P = np.array([s[:3] for s in scr], np.float32)
        c = tuple(v * lit for v in color)
        canvas.flat(P[[0, 1, 2]], c)
        canvas.flat(P[[0, 2, 3]], c)


# ------------------------------------------------------------------------------------------- сцены
def render_gui(model, qs, texs, size=256):
    c = Canvas(size, size, (139, 139, 139))
    # слот 16×16: блок −0.5..0.5 → весь холст
    M = display(model, 'gui')
    pr = ortho(size, size, (0, 0), size, np.eye(4))
    # GUI-лампы предметов (свет «сбоку», как у блоков в инвентаре)
    gl = (np.array([0.2, 1.0, 0.7]) / np.linalg.norm([0.2, 1.0, 0.7]), np.array([-0.55, 0.6, 0.6]) / np.linalg.norm([-0.55, 0.6, 0.6]))
    draw(c, qs, M, texs, pr, lights=gl)
    return c.image()


def render_turn(model, qs, texs, yaw, pitch=12, size=512, night=False):
    c = Canvas(size, size, (14, 12, 20) if night else (40, 38, 48))
    M = S(1 / 16)  # модельные пиксели → блоки
    V = Rx(pitch) @ Ry(yaw)
    allp = np.concatenate([(V @ M @ q[0].T).T[:, :3] for q in qs])
    mn, mx = allp.min(0), allp.max(0)
    ctr = (mn + mx) / 2
    sc = size * 0.92 / max(mx[0] - mn[0], mx[1] - mn[1])
    pr = ortho(size, size, ctr, sc, V)
    draw(c, qs, M, texs, pr, world=0.18 if night else 1.0)
    return c.image()


def player_parts(arm_x=-18.0, arm_y=0.0, arm_z=0.0):
    """Кубики манекена в координатах модели сущности (пиксели, y вниз): (pivot, rot, from, to, цвет)."""
    skin, shirt, pants = (196, 150, 120), (60, 110, 160), (50, 50, 110)
    return [
        ((0, 0, 0), (0, 0, 0), (-4, -8, -4), (4, 0, 4), skin),          # голова
        ((0, 0, 0), (0, 0, 0), (-4, 0, -2), (4, 12, 2), shirt),          # тело
        ((-5, 2, 0), (arm_x, arm_y, arm_z), (-3, -2, -2), (1, 10, 2), shirt),   # правая рука
        ((5, 2, 0), (0, 0, 0), (-1, -2, -2), (3, 10, 2), shirt),         # левая рука
        ((-1.9, 12, 0), (0, 0, 0), (-2, 0, -2), (2, 12, 2), pants),
        ((1.9, 12, 0), (0, 0, 0), (-2, 0, -2), (2, 12, 2), pants),
    ]


def part_matrix(pivot, rot):
    # ModelPart.translateAndRotate: translate(pivot/16), rotationZYX(z, y, x) = Rz·Ry·Rx
    return T(*(v / 16 for v in pivot)) @ Rz(rot[2]) @ Ry(rot[1]) @ Rx(rot[0])


def render_third(model, qs, texs, cam_yaw=-35, size=640, arm=(-18.0, 0.0, 0.0), ctx='thirdperson_righthand',
                 night=False):
    c = Canvas(size, size, (14, 12, 20) if night else (40, 38, 48))
    # сущность → мир: Ry(180)·scale(−1,−1,1)·translate(0,−1.501,0)
    E = Ry(180) @ S(-1, -1, 1) @ T(0, -1.501, 0)
    V = Rx(10) @ Ry(cam_yaw)
    pr = ortho(size, size, (0, 1.2, 0), size / 3.6, V)
    for pivot, rot, fr, to, col in player_parts(*arm):
        draw_box(c, [v / 16 for v in fr], [v / 16 for v in to], E @ part_matrix(pivot, rot), pr, col)
    hand = E @ part_matrix((-5, 2, 0), arm) @ Rx(-90) @ Ry(180) @ T(1 / 16, 0.125, -0.625)
    draw(c, qs, hand @ display(model, ctx), texs, pr, world=0.18 if night else 1.0)
    # земля
    for gx in range(-2, 3):
        for gz in range(-2, 3):
            pass
    return c.image()


def render_first(model, qs, texs, w=640, h=360, ctx='firstperson_righthand'):
    c = Canvas(w, h, (120, 160, 210))
    M = T(0.56, -0.52, -0.72) @ display(model, ctx)
    draw(c, qs, M, texs, persp(w, h))
    return c.image()


def main():
    assets, ref, out = sys.argv[1:4]
    prefix = sys.argv[4] if len(sys.argv) > 4 else ref.split('/')[-1]
    os.makedirs(out, exist_ok=True)
    model = load_model(assets, ref)
    texs = {}
    for k, v in model['textures'].items():
        if not v.startswith('#'):
            texs[v] = tex_image(assets, v)
    qs = quads(model, texs)
    render_gui(model, qs, texs).save(os.path.join(out, prefix + '_gui.png'))
    imgs = [render_turn(model, qs, texs, y) for y in (0, 35, 90, 180)]
    sheet = Image.new('RGB', (512 * 4, 512))
    for i, im in enumerate(imgs):
        sheet.paste(im, (512 * i, 0))
    sheet.save(os.path.join(out, prefix + '_angles.png'))
    render_turn(model, qs, texs, 0, 0, size=768).save(os.path.join(out, prefix + '_front.png'))
    render_turn(model, qs, texs, 20, 8, size=768, night=True).save(os.path.join(out, prefix + '_night.png'))
    tp = Image.new('RGB', (640 * 3, 640))
    for i, yaw in enumerate((-35, 90, 160)):
        tp.paste(render_third(model, qs, texs, yaw), (640 * i, 0))
    tp.save(os.path.join(out, prefix + '_thirdperson.png'))
    render_first(model, qs, texs).save(os.path.join(out, prefix + '_firstperson.png'))
    print('готово:', out, prefix)


if __name__ == '__main__':
    main()
