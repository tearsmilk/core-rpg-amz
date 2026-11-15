#!/usr/bin/env python3
"""BDEngine (.bdengine) → Blockbench .bbmodel (Generic/free) для ModelEngine R4.

Поддержано то, из чего обычно собирают «людей» в BDEngine: item_display player_head (display=none) со скином —
вшитым (refs.paintTextures) или ссылкой на textures.minecraft.net (качается в --skins, нужен доступ к хосту).
Каждая голова → своя кость (поворот головы целиком), коллекции BDEngine → кости-группы с опорой в их начале
координат (для будущих анимаций суставов). Куб головы 8×8×8 px + слой «шляпы» 8.5 px (если в скине он не пуст).
Матрицы BDEngine (4×4, по строкам) раскладываются на поворот × масштаб; сдвиг/зеркало — ошибка.

    python3 bdengine2bbmodel.py Janet.bdengine janet.bbmodel --name janet --skins skins/ [--scale 1.0] [--preview p.png]
"""
import argparse, base64, gzip, io, json, math, os, re, struct, urllib.request, uuid
import numpy as np
from PIL import Image

HEAD_Y = (-0.5, 0.0)   # куб головы в координатах дисплея: x,z ∈ [-0.25, 0.25], y ∈ HEAD_Y (переопределяется --head-y)


def load_scene(path):
    raw = gzip.decompress(open(path, 'rb').read())
    assert raw[:4] == b'PRJ2', 'не BDEngine PRJ2'
    pos, out = 4, {}
    _ver, count = raw[pos], raw[pos + 1]; pos += 5  # PRJ2 01 01 00 00 00 ? — дальше записи (u16 имя, u32 данные)
    pos = 9
    while pos < len(raw):
        n = struct.unpack('<H', raw[pos:pos + 2])[0]; pos += 2
        name = raw[pos:pos + n].decode(); pos += n
        size = struct.unpack('<I', raw[pos:pos + 4])[0]; pos += 4
        out[name] = raw[pos:pos + size]; pos += size
    return json.loads(out['scene.json'])


def skin_url_hash(v):
    v = v.strip(); v += '=' * (-len(v) % 4)
    s = base64.b64decode(v).decode('utf8', 'replace')
    return re.search(r'texture/([0-9a-f]+)', s).group(1)


def mat(t):
    return np.array(t, dtype=float).reshape(4, 4)


def decompose(m):
    lin = m[:3, :3]
    s = np.linalg.norm(lin, axis=0)
    r = lin / s
    if np.linalg.det(r) < 0:
        raise SystemExit('зеркальная матрица (отрицательный масштаб) не поддержана')
    err = np.abs(r.T @ r - np.eye(3)).max()
    if err > 1e-3:
        raise SystemExit(f'матрица со сдвигом (shear {err:.4f}) не поддержана')
    return m[:3, 3], r, s


def euler_zyx(r):
    """R = Rz(c)·Ry(b)·Rx(a) (порядок Euler 'ZYX' в Blockbench) → [a, b, c] в градусах."""
    b = math.asin(max(-1, min(1, -r[2, 0])))
    if abs(math.cos(b)) > 1e-6:
        a = math.atan2(r[2, 1], r[2, 2]); c = math.atan2(r[1, 0], r[0, 0])
    else:
        a = math.atan2(-r[1, 2], r[1, 1]); c = 0.0
    return [round(math.degrees(x), 4) for x in (a, b, c)]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('src'); ap.add_argument('out'); ap.add_argument('--name', required=True)
    ap.add_argument('--skins', default='skins'); ap.add_argument('--scale', type=float, default=1.0)
    ap.add_argument('--head-y', default='-0.5,0'); ap.add_argument('--preview')
    a = ap.parse_args()
    global HEAD_Y
    HEAD_Y = tuple(float(x) for x in a.head_y.split(','))
    scene = load_scene(a.src)[0]
    paints = scene.get('refs', {}).get('paintTextures', [])
    os.makedirs(a.skins, exist_ok=True)

    strips, strip_of = [], {}

    def strip_for(key, img):
        if key not in strip_of:
            strip_of[key] = len(strips)
            strips.append(img.convert('RGBA').crop((0, 0, 64, 16)))
        return strip_of[key]

    def skin_of(e):
        if e.get('paintTexture') is not None:
            p = paints[e['paintTexture']]
            return f"paint{e['paintTexture']}", Image.open(io.BytesIO(base64.b64decode(p.split(',', 1)[1])))
        h = skin_url_hash((e.get('tagHead') or {}).get('Value') or e.get('defaultTextureValue'))
        f = os.path.join(a.skins, h + '.png')
        if not os.path.exists(f):
            urllib.request.urlretrieve('https://textures.minecraft.net/texture/' + h, f)
        return h, Image.open(f)

    groups, elements, heads = [], [], []
    root_m = np.eye(4); root_m[:3, :3] *= a.scale
    used_names = {}

    def uniq(n):
        n = re.sub(r'[^a-z0-9_]+', '_', n.strip().lower()).strip('_') or 'bone'
        k = used_names.get(n, 0); used_names[n] = k + 1
        return n if k == 0 else f'{n}_{k}'

    def build(node, parent_m):
        m = parent_m @ mat(node['transforms'])
        if node.get('isCollection'):
            t, _, _ = decompose(m)
            g = {'name': uniq(node['name']), 'origin': list(np.round(t * 16, 4)), 'rotation': [0, 0, 0],
                 'uuid': str(uuid.uuid4()), 'export': True, 'visibility': True, 'autouv': 0, 'color': 0,
                 'children': []}
            groups.append(g)
            for c in node.get('children', []):
                g['children'].append(build(c, m))
            return g
        if not node.get('isItemDisplay') or not node.get('name', '').startswith('player_head'):
            raise SystemExit(f"не поддержано: {node.get('name')}")
        key, img = skin_of(node)
        si = strip_for(key, img)
        t, r, s = decompose(m)
        heads.append((t, r, s, si))
        bone = {'name': uniq('part'), 'origin': list(np.round(t * 16, 4)), 'rotation': euler_zyx(r),
                'uuid': str(uuid.uuid4()), 'export': True, 'visibility': True, 'autouv': 0, 'color': 0, 'children': []}
        groups.append(bone)
        for hat in (False, True):
            if hat and img.convert('RGBA').crop((32, 0, 64, 16)).getextrema()[3][1] == 0:
                continue
            grow = 0.25 / 16 if hat else 0.0  # px → блоки (до масштаба)
            lo = np.array([-0.25 - grow, HEAD_Y[0] - grow, -0.25 - grow]) * s
            hi = np.array([0.25 + grow, HEAD_Y[1] + grow, 0.25 + grow]) * s
            u0, v0 = (si % 4) * 64 + (32 if hat else 0), (si // 4) * 16
            uv = lambda x0, y0, x1, y1: [u0 + x0, v0 + y0, u0 + x1, v0 + y1]
            faces = {'south': uv(8, 8, 16, 16), 'north': uv(24, 8, 32, 16), 'west': uv(0, 8, 8, 16),
                     'east': uv(16, 8, 24, 16), 'up': uv(8, 0, 16, 8), 'down': uv(16, 8, 24, 0)}
            el = {'name': 'hat' if hat else 'head', 'box_uv': False, 'rescale': False, 'locked': False,
                  'render_order': 'default', 'allow_mirror_modeling': True,
                  'from': list(np.round((t + lo) * 16, 4)), 'to': list(np.round((t + hi) * 16, 4)),
                  'autouv': 0, 'color': 0, 'origin': list(np.round(t * 16, 4)), 'rotation': [0, 0, 0],
                  'faces': {k: {'uv': v, 'texture': 0} for k, v in faces.items()},
                  'type': 'cube', 'uuid': str(uuid.uuid4())}
            elements.append(el)
            bone['children'].append(el['uuid'])
        return bone

    outliner = [build(c, root_m) for c in scene['children']]
    rows = max(1, math.ceil(len(strips) / 4))
    size = 64
    while size < max(256, rows * 16): size *= 2
    size = max(size, 256)
    atlas = Image.new('RGBA', (256, max(256, rows * 16)))
    for i, st in enumerate(strips):
        atlas.paste(st, ((i % 4) * 64, (i // 4) * 16))
    if atlas.height != 256:
        raise SystemExit('больше 64 скинов — расширьте атлас')
    buf = io.BytesIO(); atlas.save(buf, 'PNG')

    def strip_groups(o):  # в outliner — только uuid, имя и т.п., сами группы — в groups
        if isinstance(o, str): return o
        return {'uuid': o['uuid'], 'isOpen': False, 'children': [strip_groups(c) for c in o['children']]}

    bb = {'meta': {'format_version': '5.0', 'model_format': 'free', 'box_uv': False},
          'name': a.name, 'model_identifier': a.name, 'visible_box': [2, 3, 0], 'variable_placeholders': '',
          'variable_placeholder_buttons': [], 'timeline_setups': [], 'unhandled_root_fields': {},
          'resolution': {'width': 256, 'height': 256},
          'elements': elements,
          'groups': [{k: v for k, v in g.items() if k != 'children'} for g in groups],
          'outliner': [strip_groups(o) for o in outliner],
          'textures': [{'path': '', 'name': a.name + '.png', 'folder': '', 'namespace': '', 'id': '0',
                        'group': '', 'width': 256, 'height': 256, 'uv_width': 256, 'uv_height': 256,
                        'particle': False, 'use_as_default': False, 'layers_enabled': False, 'sync_to_project': '',
                        'render_mode': 'default', 'render_sides': 'auto', 'frame_time': 1, 'frame_order_type': 'loop',
                        'frame_order': '', 'frame_interpolate': False, 'visible': True, 'internal': True,
                        'saved': False, 'uuid': str(uuid.uuid4()), 'relative_path': '',
                        'source': 'data:image/png;base64,' + base64.b64encode(buf.getvalue()).decode()}],
          'animations': []}
    json.dump(bb, open(a.out, 'w', encoding='utf-8'), ensure_ascii=False)
    lo = np.min([[float(x) for x in e['from']] for e in elements], axis=0)
    hi = np.max([[float(x) for x in e['to']] for e in elements], axis=0)
    print(f'{a.out}: костей {len(groups)}, кубов {len(elements)}, скинов {len(strips)}, размер px {np.round(hi - lo, 2)}')
    if a.preview:
        preview(heads, strips, a.preview)


def preview(heads, strips, out):
    """Грубый рендер точками: спереди (+Z), сбоку (+X), сзади — проверить сборку глазами."""
    views = [('front', np.array([0, 0, 1.0])), ('side', np.array([1.0, 0, 0])), ('back', np.array([0, 0, -1.0]))]
    W = 220; img = Image.new('RGB', (W * 3, W * 2), (40, 40, 48))
    pts = []
    for t, r, s, si in heads:
        st = np.asarray(strips[si]).astype(float)
        def face(fx0, fy0, origin, du, dv):
            N = 24
            for i in range(N):
                for k in range(N):
                    c = st[fy0 + k * 8 // N, fx0 + i * 8 // N]
                    if c[3] < 10: continue
                    p = origin + du * (i + 0.5) / N + dv * (k + 0.5) / N
                    pts.append((t + r @ (p * s), c[:3]))
        y0, y1 = HEAD_Y
        X0, X1, Z0, Z1 = -0.25, 0.25, -0.25, 0.25
        face(8, 8, np.array([X0, y1, Z1]), np.array([0.5, 0, 0]), np.array([0, y0 - y1, 0]))      # south: лицо
        face(24, 8, np.array([X1, y1, Z0]), np.array([-0.5, 0, 0]), np.array([0, y0 - y1, 0]))    # north
        face(0, 8, np.array([X0, y1, Z0]), np.array([0, 0, 0.5]), np.array([0, y0 - y1, 0]))      # west
        face(16, 8, np.array([X1, y1, Z1]), np.array([0, 0, -0.5]), np.array([0, y0 - y1, 0]))    # east
        face(8, 0, np.array([X0, y1, Z0]), np.array([0.5, 0, 0]), np.array([0, 0, 0.5]))          # up
    P = np.array([p for p, _ in pts]); C = np.array([c for _, c in pts])
    lo, hi = P.min(0), P.max(0); span = (hi - lo).max()
    for vi, (name, d) in enumerate(views):
        zbuf = np.full((W * 2, W), -1e9); pix = np.zeros((W * 2, W, 3))
        if name == 'front': u, depth = P[:, 0], P[:, 2]
        elif name == 'side': u, depth = -P[:, 2], P[:, 0]
        else: u, depth = -P[:, 0], -P[:, 2]
        ui = ((u - (lo[0] + hi[0]) / 2 if name != 'side' else u + (lo[2] + hi[2]) / 2) / span * W * 1.6 + W / 2).astype(int)
        vi_ = ((hi[1] - P[:, 1]) / span * W * 1.6 + 10).astype(int)
        for x, y, z, c in zip(ui, vi_, depth, C):
            for dx in (0, 1):
                for dy in (0, 1):
                    xx, yy = x + dx, y + dy
                    if 0 <= xx < W and 0 <= yy < W * 2 and z > zbuf[yy, xx]:
                        zbuf[yy, xx] = z; pix[yy, xx] = c
        mask = zbuf > -1e9
        tile = np.full((W * 2, W, 3), (40, 40, 48), dtype=float); tile[mask] = pix[mask]
        img.paste(Image.fromarray(tile.astype(np.uint8)), (vi * W, 0))
    img.save(out)


if __name__ == '__main__':
    main()
