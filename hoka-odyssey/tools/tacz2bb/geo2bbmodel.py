# Bedrock geo (+ animations) -> Blockbench .bbmodel (format 4.10, free model), чистая конвертация формата.
# Правила осей — как у импорта Bedrock в самом Blockbench (js/formats/bedrock/bedrock.js):
#   кость: origin = pivot с x*-1, rotation x,y *-1;
#   куб: from.x = -(origin.x+size.x), to = from+size; pivot x*-1; rotation x,y *-1; inflate как есть;
#   per-face uv: [u, v, u+w, v+h]; для up/down — [u+w, v+h, u, v].
# Ключи анимаций пишутся как есть (bbmodel < 5.0 хранит их в соглашении Bedrock; ядро при загрузке
# переворачивает rotation x,y и position x само — BbModel.legacyKeys).
# Использование: python3 -I geo2bbmodel.py GEO.json TEXTURE.png OUT.bbmodel [--anim ANIM.json]
#                [--drop bone1,bone2] [--strip-cubes bone1,bone2] [--name ak47]
#                [--attach GEO.json TEXTURE.png BONE [--attach-name scope]] [--view GUN_DISPLAY.json [SCOPE_DISPLAY.json]]
#                [--hide bone1,bone2]
# --hide: кости остаются в модели (с кубами), но перечислены в поле hoka_hidden — клиентский модуль guns их не рисует
# (так TACZ прячет без обвесов удлинённые магазины, крепление прицела, тактическое цевьё, другие приклады).
# --attach: модель обвеса (прицел TACZ) ставится в кость BONE оружия, как её ставит сам TACZ при рисовании: кости и
# кубы обвеса — как есть, только сдвинуты в точку кости (origin обвеса = пивот BONE); своя текстура (вторая в файле,
# со своим uv_width/uv_height). Имена костей обвеса, совпавшие с костями оружия, получают приставку «<attach-name>_».
# --view: угол обзора модели от первого лица из вкладки display оружия TACZ (model_fov — от бедра, zoom_model_fov —
# в прицеле; нет — 70, как у TACZ); с прицелом (второй файл — display обвеса) в прицеле — его views_fov[0], как у TACZ.
# Пишется в поле hoka_view {model_fov, zoom_model_fov} (его читает клиентский модуль guns; Blockbench — нет).
import argparse, base64, json, re, struct, sys, uuid as uuidlib


def strip_comments(text):
    # TACZ местами пишет JSON с // и /* */ комментариями; строки не трогаем
    out, i, n, s = [], 0, len(text), None
    while i < n:
        c = text[i]
        if s:
            out.append(c)
            if c == '\\' and i + 1 < n:
                out.append(text[i + 1]); i += 2; continue
            if c == s: s = None
            i += 1; continue
        if c == '"': s = c; out.append(c); i += 1; continue
        if text.startswith('//', i):
            j = text.find('\n', i); i = n if j < 0 else j; continue
        if text.startswith('/*', i):
            j = text.find('*/', i + 2); i = n if j < 0 else j + 2; continue
        out.append(c); i += 1
    return re.sub(r',(\s*[}\]])', r'\1', ''.join(out))


def load(path):
    with open(path, encoding='utf-8') as f:
        return json.loads(strip_comments(f.read()))


def uid(seed):
    return str(uuidlib.uuid5(uuidlib.NAMESPACE_URL, 'hoka-guns/' + seed))


def png_size(b):
    if b[:8] != b'\x89PNG\r\n\x1a\n': raise ValueError('не PNG')
    return struct.unpack('>II', b[16:24])


def num(v):
    if isinstance(v, (int, float)): return float(v)
    try: return float(str(v).strip())
    except ValueError: return None   # molang — оставим строкой, ядро прочтёт 0


def vec3(v):
    if isinstance(v, (int, float, str)): v = [v, v, v]
    return [num(x) if num(x) is not None else str(x) for x in v]


def convert(geo, png, anim, drop, strip, name, attach=None, attach_name='scope'):
    g = geo['minecraft:geometry'][0]
    desc = g['description']
    tw, th = desc.get('texture_width', 16), desc.get('texture_height', 16)
    bones = {b['name']: b for b in g['bones']}
    dropped = set()
    for d in drop:      # кость и все потомки
        stack = [d]
        while stack:
            x = stack.pop()
            if x in bones and x not in dropped:
                dropped.add(x)
                stack += [b['name'] for b in g['bones'] if b.get('parent') == x]
    elements, groups, children = [], {}, {}
    order = []
    for b in g['bones']:
        n = b['name']
        if n in dropped: continue
        piv = list(map(float, b.get('pivot', [0, 0, 0])))
        rot = list(map(float, b.get('rotation', [0, 0, 0])))
        grp = {'name': n, 'origin': [-piv[0], piv[1], piv[2]], 'rotation': [-rot[0], -rot[1], rot[2]],
               'uuid': uid(name + '/bone/' + n), 'export': True, 'isOpen': False, 'visibility': True,
               'children': []}
        groups[n] = grp
        order.append(b)
        if n in strip: continue
        for ci, c in enumerate(b.get('cubes', [])):
            e = cube_element(c, n, f'{name}/cube/{n}/{ci}', 0, [0, 0, 0])
            elements.append(e)
            grp['children'].append(e['uuid'])
    outliner = []
    for b in order:      # потомки — после кубов, в порядке файла
        n, p = b['name'], b.get('parent')
        if p and p in groups: groups[p]['children'].append(groups[n])
        else: outliner.append(groups[n])
    if attach:
        a_geo, a_png, a_bone = attach
        if a_bone not in groups: raise ValueError(f'нет кости {a_bone} для обвеса')
        add_attachment(a_geo, a_png, groups[a_bone], groups, elements, name, attach_name, tex_index=1)
    w, h = png_size(png)
    tex = {'path': '', 'name': name + '.png', 'folder': '', 'namespace': '', 'id': '0', 'particle': False,
           'render_mode': 'default', 'visible': True, 'mode': 'bitmap', 'saved': True,
           'uuid': uid(name + '/tex'), 'width': w, 'height': h, 'uv_width': tw, 'uv_height': th,
           'source': 'data:image/png;base64,' + base64.b64encode(png).decode()}
    out = {'meta': {'format_version': '4.10', 'model_format': 'free', 'box_uv': False},
           'name': name, 'model_identifier': '', 'visible_box': [1, 1, 0],
           'resolution': {'width': tw, 'height': th},
           'elements': elements, 'outliner': outliner, 'textures': [tex], 'animations': []}
    if attach:
        aw, ah = png_size(attach[1])
        ad = attach[0]['minecraft:geometry'][0]['description']
        out['textures'].append({'path': '', 'name': attach_name + '.png', 'folder': '', 'namespace': '', 'id': '1',
                                'particle': False, 'render_mode': 'default', 'visible': True, 'mode': 'bitmap',
                                'saved': True, 'uuid': uid(name + '/tex/' + attach_name), 'width': aw, 'height': ah,
                                'uv_width': ad.get('texture_width', 16), 'uv_height': ad.get('texture_height', 16),
                                'source': 'data:image/png;base64,' + base64.b64encode(attach[1]).decode()})
    if anim:
        for an_name, a in anim.get('animations', {}).items():
            loop = a.get('loop', False)
            loop = 'loop' if loop is True else 'hold' if loop == 'hold_on_last_frame' else 'once'
            animators = {}
            for bn, ch in a.get('bones', {}).items():
                if bn not in groups: continue
                keys = []
                for channel in ('rotation', 'position', 'scale'):
                    if channel not in ch: continue
                    val = ch[channel]
                    if not isinstance(val, dict) or {'pre', 'post'} & set(val):   # статическое значение
                        val = {'0.0': val}
                    for t, kv in val.items():
                        t = float(t)
                        interp = 'linear'
                        if isinstance(kv, dict):
                            if kv.get('lerp_mode') == 'catmullrom': interp = 'catmullrom'
                            pre, post = kv.get('pre'), kv.get('post', kv.get('pre'))
                            if pre is not None and post is not None and vec3(pre) != vec3(post):
                                keys.append((channel, max(0.0, t - 1e-4), vec3(pre), interp))
                            kv = post
                        keys.append((channel, t, vec3(kv), interp))
                kf = [{'channel': c, 'data_points': [{'x': v[0], 'y': v[1], 'z': v[2]}], 'time': round(t, 5),
                       'interpolation': i, 'uuid': uid(f'{name}/kf/{an_name}/{bn}/{c}/{t}')}
                      for (c, t, v, i) in keys]
                animators[groups[bn]['uuid']] = {'name': bn, 'type': 'bone', 'keyframes': kf}
            sounds = [{'time': float(t), 'effect': s.get('effect')} for t, s in a.get('sound_effects', {}).items()]
            out['animations'].append({'uuid': uid(name + '/anim/' + an_name), 'name': an_name, 'loop': loop,
                                      'override': False, 'length': float(a.get('animation_length', 0) or 0),
                                      'snapping': 60, 'animators': animators,
                                      # для нашего клиентского модуля: звуки по времени (Blockbench это поле не читает)
                                      'hoka_sounds': sounds})
    return out, dropped


def cube_element(c, n, tag, tex_index, off):
    """Куб Bedrock → элемент .bbmodel (правила осей — в начале файла); off — сдвиг [x, y, z] в координатах .bbmodel."""
    o, s = c['origin'], c['size']
    frm = [-(o[0] + s[0]) + off[0], o[1] + off[1], o[2] + off[2]]
    to = [frm[0] + s[0], frm[1] + s[1], frm[2] + s[2]]
    cp = c.get('pivot', [0, 0, 0]); cr = c.get('rotation', [0, 0, 0])
    faces = {}
    uv = c.get('uv')
    if not isinstance(uv, dict):
        raise ValueError(f'{n}: box-UV не поддержан этим конвертером')
    for f in ('north', 'east', 'south', 'west', 'up', 'down'):
        if f in uv:
            u, v = uv[f]['uv']; w, h = uv[f].get('uv_size', [0, 0])
            q = [u, v, u + w, v + h]
            if f in ('up', 'down'): q = [q[2], q[3], q[0], q[1]]
            face = {'uv': q, 'texture': tex_index}
            if uv[f].get('uv_rotation'): face['rotation'] = uv[f]['uv_rotation']
            faces[f] = face
        else:
            faces[f] = {'uv': [0, 0, 0, 0], 'texture': None}
    e = {'name': n, 'type': 'cube', 'box_uv': False, 'rescale': False, 'locked': False,
         'from': frm, 'to': to, 'autouv': 0, 'color': 0,
         'origin': [-cp[0] + off[0], cp[1] + off[1], cp[2] + off[2]], 'rotation': [-cr[0], -cr[1], cr[2]],
         'faces': faces, 'uuid': uid(tag)}
    if c.get('inflate'): e['inflate'] = c['inflate']
    return e


def add_attachment(geo, png, parent, groups, elements, name, prefix, tex_index):
    """Кости и кубы обвеса — детьми кости parent, сдвинутыми в её точку (как TACZ рисует обвес в кости *_pos)."""
    g = geo['minecraft:geometry'][0]
    off = list(parent['origin'])
    own = {}
    for b in g['bones']:
        n = b['name']
        out_name = prefix + '_' + n if n in groups else n
        piv = list(map(float, b.get('pivot', [0, 0, 0])))
        rot = list(map(float, b.get('rotation', [0, 0, 0])))
        grp = {'name': out_name, 'origin': [-piv[0] + off[0], piv[1] + off[1], piv[2] + off[2]],
               'rotation': [-rot[0], -rot[1], rot[2]], 'uuid': uid(f'{name}/{prefix}/bone/{n}'), 'export': True,
               'isOpen': False, 'visibility': True, 'children': []}
        own[n] = grp
        for ci, c in enumerate(b.get('cubes', [])):
            e = cube_element(c, out_name, f'{name}/{prefix}/cube/{n}/{ci}', tex_index, off)
            elements.append(e)
            grp['children'].append(e['uuid'])
    for b in g['bones']:
        p = b.get('parent')
        (own[p]['children'] if p and p in own else parent['children']).append(own[b['name']])
    for n, grp in own.items():
        groups[grp['name']] = grp


def view_fov(files):
    """Углы обзора модели от первого лица, как их берёт TACZ (CameraSetupEvent.applyGunModelFovModifying)."""
    gun = load(files[0])
    hip = float(gun.get('model_fov', 70))
    zoom = float(gun.get('zoom_model_fov', 70))
    if len(files) > 1:                     # прицел: в прицеле — его views_fov (первая кратность)
        fovs = load(files[1]).get('views_fov')
        if isinstance(fovs, list) and fovs: zoom = float(fovs[0])
        elif isinstance(fovs, (int, float)): zoom = float(fovs)
    return {'model_fov': hip, 'zoom_model_fov': zoom}


def _outliner_names(nodes):
    for n in nodes:
        if isinstance(n, dict):
            yield n.get('name')
            yield from _outliner_names(n.get('children', []))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('geo'); ap.add_argument('png'); ap.add_argument('out')
    ap.add_argument('--anim'); ap.add_argument('--drop', default=''); ap.add_argument('--strip-cubes', default='')
    ap.add_argument('--name', default='model')
    ap.add_argument('--attach', nargs=3, metavar=('GEO', 'PNG', 'BONE'),
                    help='модель обвеса (Bedrock geo) и её текстура — в кость BONE оружия (как TACZ ставит прицел)')
    ap.add_argument('--attach-name', default='scope')
    ap.add_argument('--view', nargs='+', metavar='DISPLAY',
                    help='display оружия TACZ (model_fov, zoom_model_fov) [и display прицела (views_fov)] → hoka_view')
    ap.add_argument('--hide', default='',
                    help='кости, которые TACZ показывает только с обвесами: остаются в модели, но в поле hoka_hidden '
                         '(клиентский модуль guns их не рисует, пока обвеса нет)')
    ap.add_argument('--display', help='JSON-файл вкладки Display (Java: thirdperson_righthand, firstperson_righthand, ground, fixed, gui…)')
    a = ap.parse_args()
    with open(a.png, 'rb') as f: png = f.read()
    attach = None
    if a.attach:
        with open(a.attach[1], 'rb') as f: attach = (load(a.attach[0]), f.read(), a.attach[2])
    res, dropped = convert(load(a.geo), png, load(a.anim) if a.anim else None,
                           [x for x in a.drop.split(',') if x], set(x for x in a.strip_cubes.split(',') if x), a.name,
                           attach, a.attach_name)
    if a.display: res['display'] = load(a.display)
    if a.view: res['hoka_view'] = view_fov(a.view)
    if a.hide:
        names = {o.get('name') for o in res.get('groups', [])} | set(_outliner_names(res.get('outliner', [])))
        hide = [x for x in a.hide.split(',') if x]
        missing = [x for x in hide if x not in names]
        if missing: sys.exit(f'--hide: нет костей {missing}')
        res['hoka_hidden'] = hide
    with open(a.out, 'w', encoding='utf-8') as f: json.dump(res, f, ensure_ascii=False, separators=(',', ':'))
    print(f'{a.out}: кубов {len(res["elements"])}, анимаций {len(res["animations"])}, убрано костей {len(dropped)}: {sorted(dropped)}')


if __name__ == '__main__':
    main()
