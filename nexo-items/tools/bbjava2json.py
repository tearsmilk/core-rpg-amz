#!/usr/bin/env python3
"""Blockbench «Java Block/Item» (.bbmodel) → модель предмета Minecraft (JSON) + текстура PNG для пакета Nexo.

    python3 bbjava2json.py <model.bbmodel> <assets_dir> <namespace> <id>
пишет <assets>/<ns>/models/item/<id>.json и <assets>/<ns>/textures/item/<id>[_N].png.
UV переводятся из разрешения проекта в 0..16, поворот куба — angle/axis/origin, вид в руке — из display проекта."""
import base64, json, os, sys

src, assets, ns, mid = sys.argv[1:5]
j = json.load(open(src, encoding='utf-8'))
assert j['meta'].get('model_format') in ('java_block', 'java_item', 'free'), j['meta']
rw, rh = j['resolution']['width'], j['resolution']['height']
tex_dir = os.path.join(assets, ns, 'textures', 'item')
mod_dir = os.path.join(assets, ns, 'models', 'item')
os.makedirs(tex_dir, exist_ok=True)
os.makedirs(mod_dir, exist_ok=True)
textures = {}
for i, t in enumerate(j['textures']):
    name = mid if len(j['textures']) == 1 else f'{mid}_{i}'
    data = base64.b64decode(t['source'].split(',', 1)[1])
    open(os.path.join(tex_dir, name + '.png'), 'wb').write(data)
    textures[str(i)] = f'{ns}:item/{name}'
textures['particle'] = textures['0']
r = lambda v: round(float(v), 4)
elements = []
for e in j['elements']:
    if e.get('type', 'cube') != 'cube' or e.get('visibility') is False:
        continue
    out = {'from': [r(v) for v in e['from']], 'to': [r(v) for v in e['to']]}
    rot = e.get('rotation') or [0, 0, 0]
    axes = [i for i, v in enumerate(rot) if v]
    if len(axes) > 1:
        raise SystemExit(f"{e['name']}: поворот сразу по нескольким осям {rot} — в Java-модели нельзя")
    if axes:
        out['rotation'] = {'angle': r(rot[axes[0]]), 'axis': 'xyz'[axes[0]], 'origin': [r(v) for v in e['origin']]}
        if e.get('rescale'):
            out['rotation']['rescale'] = True
    faces = {}
    for side, f in e['faces'].items():
        if f.get('texture') is None:
            continue
        u1, v1, u2, v2 = f['uv']
        face = {'uv': [r(u1 * 16 / rw), r(v1 * 16 / rh), r(u2 * 16 / rw), r(v2 * 16 / rh)], 'texture': '#' + str(f['texture'])}
        if f.get('rotation'):
            face['rotation'] = f['rotation']
        if f.get('cullface'):
            face['cullface'] = f['cullface']
        if f.get('tint', -1) >= 0:
            face['tintindex'] = f['tint']
        faces[side] = face
    if faces:
        out['faces'] = faces
        elements.append(out)
model = {'textures': textures, 'elements': elements}
if j.get('display'):
    model['display'] = {k: {kk: [r(x) for x in vv] for kk, vv in v.items() if kk in ('rotation', 'translation', 'scale')}
                        for k, v in j['display'].items()}
json.dump(model, open(os.path.join(mod_dir, mid + '.json'), 'w'), indent=1, ensure_ascii=False)
print(mid, 'кубов', len(elements), 'текстур', len(j['textures']))
