#!/usr/bin/env python3
"""Готовит .bbmodel к ModelEngine R4: оставляет одну корневую кость (--root), выкидывает чужие кубы, текстуры
и треки анимаций, убирает грани без текстуры, приводит повороты кубов к допустимым в Java (0/±22.5/±45, одна ось),
переименовывает анимацию (--anim old=new).
    python3 prepare_bbmodel.py IN.bbmodel OUT.bbmodel --root root --name vexa_npc --anim animation=idle"""
import argparse, copy, json, uuid

ap = argparse.ArgumentParser()
ap.add_argument('src'); ap.add_argument('out'); ap.add_argument('--root', required=True); ap.add_argument('--name', required=True)
ap.add_argument('--anim', action='append', default=[])
a = ap.parse_args()
j = json.load(open(a.src, encoding='utf-8'))
G = {g['uuid']: g for g in j['groups']}
E = {e['uuid']: e for e in j['elements']}
root = next(o for o in j['outliner'] if isinstance(o, dict) and G[o['uuid']]['name'] == a.root)
keep_g, keep_e = set(), set()
def walk(o):
    keep_g.add(o['uuid'])
    for c in o.get('children', []):
        if isinstance(c, str): keep_e.add(c)
        else: walk(c)
walk(root)
j['outliner'] = [root]
j['groups'] = [g for g in j['groups'] if g['uuid'] in keep_g]
j['elements'] = [e for e in j['elements'] if e['uuid'] in keep_e]
used = {f.get('texture') for e in j['elements'] for f in e['faces'].values()} - {None}
remap = {old: new for new, old in enumerate(sorted(used))}
j['textures'] = [t for i, t in enumerate(j['textures']) if i in used]
for i, t in enumerate(j['textures']):
    t['name'] = f"{a.name}_{i}.png" if len(j['textures']) > 1 else f"{a.name}.png"
    t['uuid'] = str(uuid.uuid4())
for e in j['elements']:
    e['faces'] = {k: f for k, f in e['faces'].items() if f.get('texture') is not None}
    for f in e['faces'].values(): f['texture'] = remap[f['texture']]
    r = e.get('rotation')
    if r and any(r):
        axes = [i for i, v in enumerate(r) if v]
        fixed = [0, 0, 0]
        if len(axes) == 1:
            v = r[axes[0]]; snapped = max(-45, min(45, round(v / 22.5) * 22.5)); fixed[axes[0]] = snapped
        print(f"cube {e['name']}: rotation {r} -> {fixed}")
        e['rotation'] = fixed
ren = dict(x.split('=', 1) for x in a.anim)
for an in j.get('animations', []):
    an['animators'] = {k: v for k, v in an['animators'].items() if k in keep_g}
    an['name'] = ren.get(an['name'], an['name'])
j['name'] = a.name
j['model_identifier'] = a.name
json.dump(j, open(a.out, 'w', encoding='utf-8'), ensure_ascii=False)
print(a.out, 'bones', len(j['groups']), 'cubes', len(j['elements']), 'textures', len(j['textures']),
      'anims', [x['name'] for x in j.get('animations', [])])
