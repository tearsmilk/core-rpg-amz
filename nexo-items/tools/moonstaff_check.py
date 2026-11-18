#!/usr/bin/env python3
"""Проверка «Посоха Лунного Странника»: маркеры, общий шейдер, GLSL, логика хука, файлы.

    python3 -I tools/moonstaff_check.py [--pack pack.zip] [--quick]

(a) Маркеры не пересекаются ни с чем: сигнатура S0 = (177,126,90,3) есть только в (0,0) наших 10 спрайтов, (K,90,126,3)
    — только в (1,0); ни ±2 по каналу от S0, ни (G,B,A) = (90,126,3) больше нигде. Скан: все PNG паков репозитория,
    server/plugins (снимок прода, в т. ч. zip внешних паков), models/, картинки внутри .bbmodel и ВСЕ zip/jar
    репозитория (включая ванильный client-1.21.11.jar). Оттенок (R≤250, 233, 253) не встречается ни в одном
    текстовом файле, кроме наших (core_magic, moonstaff_*, items/moon_staff_*.json). --quick — без zip/jar.
(b) 3 копии .vsh одинаковы, 2 копии .fsh одинаковы; без блоков moonstaff md5 = известные (= прод до хука).
(c) #moj_import развёрнут как в игре (ванильные include — из client-1.21.11.jar, coreportals/coreskyrift/azerus — из
    паков репозитория), glslangValidator -l на паре vert+frag при #version 330 без определений (у шейдера один
    ванильный конвейер — pipeline/item_entity_translucent_cull — и он без определений). В наших include нет
    #version и #extension, имена — только ms/ms_.
(d) Python-копия хука на квадах, «запечённых» в ванильном порядке FaceInfo из обеих моделей эффектов, спрайты
    в случайных местах атласа 2048²: каждая грань узнаёт свой K, «вдоль луча» = +Z модели; кожаная броня с оттенком
    (100,233,253) при UV2 (240,240) и ванильной текстурой — ванильный путь; ключ A не срабатывает на чужих маркерах.
(e) Размеры: спрайты 128×128, текстура посоха 64×64; модели — повороты по одной оси на ±22.5/±45, координаты −16..32.
(f) Барьер (K10 hex_cell): классы альфы ячейки, вне шестигранника и при L=0 — альфа 0, L=9 ярче L=8, L=4 тусклее,
    шов GameTime (t и t+1200 равны), раскладка сот FX == core_magic/keisan.py (蜂巣, 組順, 輝き番号), если он есть.
--pack: наши пиксели и модели в собранном Nexo pack.zip == репозиторий, include azerus на месте, общий шейдер == наш.
Затем запускается armor3d_check.py (в nexo-items/pack не должно быть копии общего шейдера).
Код выхода ≠ 0 при любой ошибке.
"""
import hashlib
import io
import json
import math
import os
import random
import re
import subprocess
import sys
import tempfile
import zipfile

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
REPO = os.path.dirname(ROOT)
sys.path.insert(0, HERE)
import moonstaff_fx as FX  # noqa: E402
import moonstaff_hook as HOOK  # noqa: E402

JAR = os.path.join(REPO, 'core-npc', 'tools', '.cache', 'client-1.21.11.jar')
AZ = os.path.join(ROOT, 'pack', 'assets', 'azerus')
INC = os.path.join(AZ, 'shaders', 'include')
S0 = np.array(FX.S0)
PROBLEMS = []


def bad(msg):
    PROBLEMS.append(msg)
    print('   ✗', msg)


def ok(msg):
    print('   ✓', msg)


def ours_png(path):
    p = path.replace('\\', '/')
    return '/azerus/textures/item/moon_staff_fx/' in p and not ('!' in p)


# ------------------------------------------------------------------------------------------- (a) маркеры
def check_markers(quick):
    print('(a) маркеры')
    roots = [os.path.join(REPO, d) for d in ('nexo-items/pack', 'nexo-items/entity_shaders', 'nexo-items/source',
                                             'rezusmenu/pack', 'core-portals/pack', 'core-skyrift/pack',
                                             'core-npc/pack', 'core-effects/pack', 'server/plugins', 'models')]
    st = dict(png=0, alpha3=0)
    sentinel_at = []

    def scan(name, data, mine):
        try:
            a = np.asarray(Image.open(io.BytesIO(data)).convert('RGBA')).astype(int)
        except Exception:
            return
        st['png'] += 1
        al = a[..., 3]
        st['alpha3'] += int((al == 3).sum())
        m0 = np.all(a == S0, axis=-1)
        m1 = (a[..., 1] == 90) & (a[..., 2] == 126) & (al == 3)
        near = np.all(np.abs(a - S0) <= 2, axis=-1)
        if mine:
            exp0 = np.zeros_like(m0)
            exp0[0, 0] = True
            exp1 = np.zeros_like(m1)
            exp1[0, 1] = True
            if not (np.array_equal(m0, exp0) and np.array_equal(near, exp0) and np.array_equal(m1, exp1)):
                bad(f'{name}: сигнатура не только в (0,0)/(1,0)')
            other3 = (al == 3)
            other3[0, 0] = other3[0, 1] = False
            if other3.any():
                bad(f'{name}: альфа 3 вне сигнатуры ({int(other3.sum())} текс.)')
            sentinel_at.append(name)
        elif m0.any() or m1.any() or near.any():
            bad(f'{name}: похоже на нашу сигнатуру (S0 {int(m0.sum())}, K {int(m1.sum())}, ±2 {int(near.sum())})')

    for root in roots:
        for r, _, fs in os.walk(root):
            for f in fs:
                p = os.path.join(r, f)
                if f.lower().endswith('.png'):
                    scan(os.path.relpath(p, REPO), open(p, 'rb').read(), ours_png(p))
                elif f.endswith('.bbmodel'):
                    t = open(p, encoding='utf-8', errors='ignore').read()
                    import base64
                    for b in re.findall(r'data:image/png;base64,([A-Za-z0-9+/=]+)', t):
                        scan(os.path.relpath(p, REPO) + '#png', base64.b64decode(b), False)
    nz = 0
    if not quick:
        for r, ds, fs in os.walk(REPO):
            if '/.git' in r:
                continue
            for f in fs:
                if f.endswith(('.zip', '.jar')):
                    try:
                        z = zipfile.ZipFile(os.path.join(r, f))
                    except Exception:
                        continue
                    nz += 1
                    for n in z.namelist():
                        if n.lower().endswith('.png'):
                            scan(os.path.relpath(os.path.join(r, f), REPO) + '!' + n, z.read(n), False)
    # Считаем только nexo-items/pack; копии в server/plugins (снимок прода) проверены выше на ту же уникальность.
    ours = [n for n in sentinel_at if n.replace('\\', '/').startswith('nexo-items/pack/')]
    if len(ours) != len(FX.KINDS):
        bad(f'наших спрайтов в nexo-items/pack найдено {len(ours)}, ожидалось {len(FX.KINDS)}')
    ok(f'PNG: {st["png"]} (zip/jar: {nz}{" — пропущены, --quick" if quick else ""}), текселей с альфой 3 '
       f'всего {st["alpha3"]} (из них наших сигнатур {2 * len(sentinel_at)}); сигнатура только у наших '
       f'{len(FX.KINDS)} спрайтов (+ {len(sentinel_at) - len(ours)} копий в снимке прода)')
    # оттенок
    vals = {(r << 16) | (FX.MARK_G << 8) | FX.MARK_B for r in range(0, 251)}
    pi = re.compile(r'(?<![0-9.])(-?\d{4,10})(?![0-9.])')
    ph = re.compile(r'(?i)(#|0x)(ff)?[0-9a-f]{2}e9fd\b')
    pr = re.compile(r'\b\d{1,3}\s*,\s*233\s*,\s*253\b')
    n = 0
    hits = []
    for r, ds, fs in os.walk(REPO):
        if '/.git' in r:
            continue
        rr = r.replace('\\', '/')
        if '/core-python/scripts/core_magic' in rr:
            continue
        for f in fs:
            if not f.endswith(('.json', '.yml', '.yaml', '.java', '.py', '.glsl', '.vsh', '.fsh', '.mcmeta', '.kt',
                               '.js', '.toml')):
                continue
            if f.startswith(('moonstaff_', 'moon_staff_')) and (f.endswith('.py') or f.endswith('.glsl')
                                                                 or '/items' in rr):
                continue
            p = os.path.join(r, f)
            n += 1
            t = open(p, encoding='utf-8', errors='ignore').read()
            for m in pi.finditer(t):
                v = int(m.group(1))
                if (v & 0xFFFFFF) in vals and (0 <= v <= 0xFFFFFF or (v < 0 and (v & 0xFF000000) == 0xFF000000)):
                    hits.append((p, m.group(0)))
            hits += [(p, m.group(0)) for m in ph.finditer(t)] + [(p, m.group(0)) for m in pr.finditer(t)]
    for p, h in hits:
        bad(f'оттенок-маркер в {os.path.relpath(p, REPO)}: {h}')
    if not hits:
        ok(f'текстовых файлов {n}: оттенок (R≤250, 233, 253) нигде не используется')


# ------------------------------------------------------------------------------------------- (b) общий шейдер
def check_shared():
    print('(b) общий шейдер')
    texts = {}
    for p, edits, base in HOOK.files():
        t = open(p, encoding='utf-8', newline='').read()
        texts[p] = t
        rel = os.path.relpath(p, REPO)
        if not HOOK.has_hook(t):
            bad(f'{rel}: нет хука moonstaff')
        if t.count('azerus moonstaff hook') != 4:
            bad(f'{rel}: блоков moonstaff не 2 (меток {t.count("azerus moonstaff hook")})')
        if HOOK.md5(HOOK.strip_text(t, edits)) != base:
            bad(f'{rel}: без блоков moonstaff md5 {HOOK.md5(HOOK.strip_text(t, edits))} ≠ {base}')
    for group in (HOOK.VSH, HOOK.FSH):
        if len({texts[p] for p in group}) != 1:
            bad('копии общего шейдера различаются: ' + ', '.join(os.path.relpath(p, REPO) for p in group))
    if os.path.exists(os.path.join(ROOT, 'pack', 'assets', 'minecraft', 'shaders', 'core',
                                   'rendertype_item_entity_translucent_cull.vsh')):
        bad('в nexo-items/pack лишняя копия общего шейдера')
    if not PROBLEMS:
        ok('3×.vsh и 2×.fsh совпадают байт-в-байт; без блоков moonstaff — md5 '
           f'{HOOK.MD5_VSH} / {HOOK.MD5_FSH} (прод до хука)')
    # вершина и цвет не трогаются хуком: ms_vertex только пишет свои выходы
    v = open(os.path.join(INC, 'moonstaff_vertex.glsl'), encoding='utf-8').read()
    if re.search(r'\b(gl_Position|vertexColor)\b\s*=', v):
        bad('moonstaff_vertex.glsl пишет gl_Position/vertexColor')


# ------------------------------------------------------------------------------------------- (c) GLSL
PACKS = {'coreportals': os.path.join(REPO, 'core-portals', 'pack'),
         'coreskyrift': os.path.join(REPO, 'core-skyrift', 'pack'),
         'azerus': os.path.join(ROOT, 'pack')}


def expand(src, vanilla, seen):
    out = []
    for line in src.split('\n'):
        m = re.match(r'^#moj_import <([a-z_]+):([^>]+)>', line)
        if m:
            ns, p = m.groups()
            if (ns, p) in seen:
                continue
            seen.add((ns, p))
            if ns == 'minecraft':
                text = vanilla[f'assets/minecraft/shaders/include/{p}']
            else:
                text = open(os.path.join(PACKS[ns], 'assets', ns, 'shaders', 'include', p), encoding='utf-8').read()
            text = '\n'.join(l for l in text.split('\n') if not l.startswith('#version'))
            out.append(expand(text, vanilla, seen))
        else:
            out.append(line)
    return '\n'.join(out)


def check_glsl():
    print('(c) GLSL')
    for f in ('moonstaff_vertex.glsl', 'moonstaff_fragment.glsl'):
        t = open(os.path.join(INC, f), encoding='utf-8').read()
        code = '\n'.join(l.split('//')[0] for l in t.split('\n'))
        if re.search(r'^\s*#\s*(version|extension)', code, re.M):
            bad(f'{f}: есть #version/#extension')
        names = set(re.findall(r'\b(?:float|int|bool|vec[234]|ivec2)\s+([A-Za-z_]\w*)\s*\(', code))
        names |= set(re.findall(r'^\s*(?:flat\s+)?(?:in|out|const)\s+\w+\s+([A-Za-z_]\w*)', code, re.M))
        foreign = sorted(n for n in names if not (n.startswith('ms') or n.startswith('MS_')))
        if foreign:
            bad(f'{f}: глобальные имена не ms/ms_: {foreign}')
    with zipfile.ZipFile(JAR) as z:
        vanilla = {n: z.read(n).decode() for n in z.namelist() if n.startswith('assets/minecraft/shaders/include/')}
    base = os.path.join(REPO, 'core-skyrift', 'pack', 'assets', 'minecraft', 'shaders', 'core',
                        'rendertype_item_entity_translucent_cull')
    with tempfile.TemporaryDirectory() as tmp:
        pairs = [('с хуком (все копии одинаковы)', open(base + '.vsh').read(), open(base + '.fsh').read())]
        for label, vs, fs in pairs:
            v, f = os.path.join(tmp, 'a.vert'), os.path.join(tmp, 'a.frag')
            vs, fs = expand(vs, vanilla, set()), expand(fs, vanilla, set())
            assert vs.startswith('#version 330') and fs.startswith('#version 330')
            if '#extension' in vs + fs:
                bad('в развёрнутом шейдере есть #extension')
            open(v, 'w').write(vs)
            open(f, 'w').write(fs)
            check_varyings(vs, fs)
            r = subprocess.run(['glslangValidator', '-l', v, f], capture_output=True, text=True)
            text = (r.stdout + r.stderr).strip()
            if r.returncode != 0 or 'WARNING' in text or 'ERROR' in text:
                bad(f'glslangValidator ({label}): код {r.returncode}\n      ' + text.replace('\n', '\n      ')[:3000])
            else:
                ok(f'glslangValidator -l vert+frag, #version 330, без определений: компилируется и линкуется ({label})')


# Лимит интерфейса vsh→fsh. glslang лимитов реализации не проверяет. Спецификация GL 4.1 Core (macOS) считает
# лимит в КОМПОНЕНТАХ: GL_MAX_VERTEX_OUTPUT_COMPONENTS = 64 (минимум спецификации и значение на macOS) — это и есть
# проверка «ошибка». Худший случай «один слот vec4 на каждую переменную» (драйвер не упаковывает flat int вместе с
# float) — только справка: общий шейдер ДО нашего хука (прод сейчас) уже объявляет 17 переменных > 16 слотов и
# работает у игроков, т.е. драйверы упаковывают. Печатаем, сколько добавляет moonstaff, чтобы запас был виден.
VARYING_SLOTS_MAX = 16
_COMP = {'float': 1, 'int': 1, 'uint': 1, 'bool': 1, 'vec2': 2, 'ivec2': 2, 'vec3': 3, 'ivec3': 3,
         'vec4': 4, 'ivec4': 4}
_VAR_RE = re.compile(r'^\s*(?:(?:flat|smooth|noperspective)\s+)?(in|out)\s+(\w+)\s+(\w+)\s*(?:\[(\d+)\])?\s*;', re.M)


def _globals(src, kind):
    code = '\n'.join(l.split('//')[0] for l in src.split('\n'))
    res = {}
    for io, typ, name, n in _VAR_RE.findall(code):
        if io == kind and typ in _COMP:
            res[name] = (typ, int(n) if n else 1)
    return res


def check_varyings(vs, fs):
    outs = _globals(vs, 'out')
    ins = _globals(fs, 'in')
    missing = sorted(set(ins) - set(outs))
    if missing:
        bad(f'фрагментный читает varying, которых нет в вершинном: {missing}')
    slots_all = sum(n for _, n in outs.values())
    comps_all = sum(_COMP[t] * n for t, n in outs.values())
    used = {k: v for k, v in outs.items() if k in ins}
    slots_used = sum(n for _, n in used.values())
    comps_used = sum(_COMP[t] * n for t, n in used.values())
    ms = {k: v for k, v in outs.items() if k.startswith('ms')}
    print(f'  varying vsh→fsh: объявлено {len(outs)} ({comps_all} компонент, худший случай {slots_all} слотов vec4), '
          f'из них читает фрагментный {len(used)} ({comps_used} компонент, {slots_used} слотов); '
          f'moonstaff добавляет {len(ms)} ({sum(_COMP[t] * n for t, n in ms.values())} компоненты); '
          f'лимит GL 4.1: {VARYING_SLOTS_MAX} слотов / 64 компоненты')
    base_slots = slots_all - sum(n for _, n in ms.values())
    if comps_all > 64:
        bad(f'интерфейс vsh→fsh больше лимита GL 4.1: {comps_all} компонент > 64')
    else:
        ok(f'интерфейс vsh→fsh: {comps_all}/64 компонент (лимит GL 4.1 Core / macOS), запас {64 - comps_all}')
    if slots_all > VARYING_SLOTS_MAX:
        print(f'   ! без упаковки было бы {slots_all} слотов vec4 > {VARYING_SLOTS_MAX}; но и без хука (прод) уже '
              f'{base_slots} > {VARYING_SLOTS_MAX} — драйверы упаковывают. Окончательно — проверка в клиенте на macOS.')


# ------------------------------------------------------------------------------------------- (d) логика хука
# Ванильный FaceInfo (порядок вершин грани) и UV вершин при uv-повороте 0: (u0,v0), (u0,v1), (u1,v1), (u1,v0).
FACEINFO = {
    'down': [('x0', 'y0', 'z1'), ('x0', 'y0', 'z0'), ('x1', 'y0', 'z0'), ('x1', 'y0', 'z1')],
    'up': [('x0', 'y1', 'z0'), ('x0', 'y1', 'z1'), ('x1', 'y1', 'z1'), ('x1', 'y1', 'z0')],
    'north': [('x1', 'y1', 'z0'), ('x1', 'y0', 'z0'), ('x0', 'y0', 'z0'), ('x0', 'y1', 'z0')],
    'south': [('x0', 'y1', 'z1'), ('x0', 'y0', 'z1'), ('x1', 'y0', 'z1'), ('x1', 'y1', 'z1')],
    'west': [('x0', 'y1', 'z0'), ('x0', 'y0', 'z0'), ('x0', 'y0', 'z1'), ('x0', 'y1', 'z1')],
    'east': [('x1', 'y1', 'z1'), ('x1', 'y0', 'z1'), ('x1', 'y0', 'z0'), ('x1', 'y1', 'z0')],
}
LOCAL = [(0.0, 0.0), (0.0, 1.0), (1.0, 1.0), (1.0, 0.0)]


def bake(model):
    """→ [(texture_var, вершины 4×3 после поворота элемента, имя грани)]."""
    out = []
    for e in model['elements']:
        (x0, y0, z0), (x1, y1, z1) = e['from'], e['to']
        env = dict(x0=x0, y0=y0, z0=z0, x1=x1, y1=y1, z1=z1)
        R = np.eye(3)
        o = np.zeros(3)
        if 'rotation' in e:
            a = math.radians(e['rotation']['angle'])
            c, s = math.cos(a), math.sin(a)
            R = {'x': np.array([[1, 0, 0], [0, c, -s], [0, s, c]]), 'y': np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]]),
                 'z': np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])}[e['rotation']['axis']]
            o = np.array(e['rotation']['origin'], float)
        for face, fd in e['faces'].items():
            assert fd['uv'] == [0, 0, 16, 16] and not fd.get('rotation'), (face, fd)
            assert fd.get('tintindex') == 0, face
            pts = np.array([[env[k] for k in v] for v in FACEINFO[face]], float)
            pts = (R @ (pts - o).T).T + o
            out.append((fd['texture'].lstrip('#'), pts, face))
    return out


def vertex_key(rgb255, uv2):
    """Копия ms_vertex: цвет приходит как байт/255."""
    c = np.array(rgb255, float) / 255.0 * 255.0
    on = uv2 == (240, 240) and abs(c[1] - 233.0) < 0.5 and abs(c[2] - 253.0) < 0.5 and c[0] < 250.5
    return on, min(max(rgb255[0] / 250.0, 0.0), 1.0)


def cpu_kind(atlas, uv, local):
    """Копия начала ms_fragment: прямоугольник спрайта по uv и msLocal, обе сигнатуры. → K или None."""
    H, W = atlas.shape[:2]
    us = np.array([128.0 / W, 128.0 / H])
    uvmin = np.array(uv) - np.array(local) * us
    p0 = np.floor(uvmin * np.array([W, H]) + 0.5).astype(int)
    p0 = np.clip(p0, [0, 0], [W - 2, H - 1])
    t0 = atlas[p0[1], p0[0]].astype(float)
    if np.any(np.abs(t0 - S0) >= 0.5):
        return None
    k = atlas[p0[1], p0[0] + 1].astype(float)
    kind = int(math.floor(k[0] + 0.5))
    if kind < 1 or kind > max(FX.KINDS.values()) or np.any(np.abs(k - np.array([kind, 90, 126, 3])) >= 0.5):
        return None
    return kind


def check_logic():
    print('(d) логика хука (Python-копия)')
    # ключ A
    cases = [((255, 255, 255), (240, 240), False, 'белый (обычный предмет)'),
             ((254, 120, 254), (240, 224), False, 'курсор rezusmenu'),
             ((255, 80, 200), (240, 240), False, 'core-portals'),
             ((255, 97, 100), (240, 240), False, 'core-skyrift'),
             ((250, 233, 254), (240, 240), False, 'B 254'),
             ((251, 233, 253), (240, 240), False, 'R 251'),
             ((0, 233, 253), (240, 224), False, 'наш цвет при UV2 (240,224)'),
             ((0, 233, 253), (240, 240), True, 'наш, заряд 0'),
             ((125, 233, 253), (240, 240), True, 'наш, заряд 0.5'),
             ((250, 233, 253), (240, 240), True, 'наш, заряд 1'),
             ((249, 233, 253), (240, 240), True, 'барьер g 24, L 9'),
             ((249, 233, 253), (240, 224), False, 'барьер при UV2 (240,224)')]
    for rgb, uv2, want, label in cases:
        got, _ = vertex_key(rgb, uv2)
        if got != want:
            bad(f'ключ A: {label} {rgb} {uv2} → {got}, ожидалось {want}')
    ok(f'ключ A: {len(cases)} случаев (белый, rezusmenu, порталы, разлом, соседи по цвету, UV2) — как задумано')
    # атлас: шум + спрайты в случайных местах (16-кратно) + ванильная текстура кожаной брони
    rng = random.Random(20261116)
    nrng = np.random.default_rng(7)
    W = 2048
    atlas = nrng.integers(0, 256, (W, W, 4), dtype=np.uint8)
    sprites = FX.load_sprites()
    with zipfile.ZipFile(JAR) as z:
        leather = np.asarray(Image.open(io.BytesIO(z.read('assets/minecraft/textures/item/leather_helmet.png')))
                             .convert('RGBA'))
    taken = set()

    def place(h, w):
        while True:
            x, y = rng.randrange(0, W - w, 16), rng.randrange(0, W - h, 16)
            cells = {(cx, cy) for cx in range(x // 16, (x + w + 15) // 16) for cy in range(y // 16, (y + h + 15) // 16)}
            if not cells & taken:
                taken.update(cells)
                return x, y

    rects = {}
    for name, spr in sprites.items():
        x, y = place(128, 128)
        atlas[y:y + 128, x:x + 128] = spr
        rects[name] = (x, y, 128, 128)
    lx, ly = place(16, 16)
    atlas[ly:ly + 16, lx:lx + 16] = leather
    rects['leather'] = (lx, ly, 16, 16)
    tex_kind = {}
    for mname, model in (('moon_staff_circle', FX.circle_model()), ('moon_staff_beam', FX.beam_model()),
                         ('moon_staff_barrier', FX.barrier_model()), ('stick_ember_orb', FX.ember_orb_model()),
                         ('stick_ember_beam', FX.ember_beam_model()), ('stick_slash', FX.slash_model())):
        disk = json.load(open(os.path.join(AZ, 'models', 'item', mname + '.json'), encoding='utf-8'))
        if disk != model:
            bad(f'models/item/{mname}.json отличается от генератора — перезапустите moon_staff.py')
        for var, ref in model['textures'].items():
            tex_kind[(mname, var)] = ref.split('/')[-1]
        n = 0
        луч = False
        for var, pts, face in bake(model):
            name = tex_kind[(mname, var)]
            K = FX.KINDS[name]
            x, y, w, h = rects[name]
            shrink = rng.uniform(0, 0.2)   # ванильное «поджатие» UV к центру (доли текселя)
            u0, v0, u1, v1 = (x + shrink) / W, (y + shrink) / W, (x + w - shrink) / W, (y + h - shrink) / W
            for _ in range(24):
                l = (rng.random(), rng.random())
                uv = (u0 + l[0] * (u1 - u0), v0 + l[1] * (v1 - v0))
                got = cpu_kind(atlas, uv, l)
                if got != K:
                    bad(f'{mname} {face} ({name}): K {got}, ожидалось {K}')
                    break
            n += 1
            if 5 <= K <= 8 or 30 <= K <= 33:
                луч = True
                Kb = K - 25 if K >= 30 else K
                d_l = {5: pts[1] - pts[0], 6: pts[0] - pts[1], 7: pts[3] - pts[0], 8: pts[0] - pts[3]}[Kb]
                d_l = d_l / np.linalg.norm(d_l)
                if d_l @ np.array([0, 0, 1.0]) < 0.999:
                    bad(f'{mname} {face} ({name}): «вдоль луча» = {d_l.round(3)}, а не +Z')
        ok(f'{mname}: {n} граней, у каждой свой K' + ('; у граней луча s растёт вдоль +Z' if луч else ''))
    # чужой квад с нашим оттенком
    on, _ = vertex_key((100, 233, 253), (240, 240))
    x, y, w, h = rects['leather']
    rej = 0
    for _ in range(200):
        l = (rng.random(), rng.random())
        uv = ((x + l[0] * w) / W, (y + l[1] * h) / W)
        rej += cpu_kind(atlas, uv, l) is None
    if on and rej == 200:
        ok('кожаная броня (100,233,253) при UV2 (240,240): ключ A совпал, ключ B — нет → ванильный путь (200/200)')
    else:
        bad(f'кожаная броня: ключ A {on}, отвергнуто {rej}/200')
    # каждый спрайт целиком (сдвиг на 1 тексель от реального угла не находит сигнатуру)
    for name, (x, y, w, h) in rects.items():
        if name == 'leather':
            continue
        if cpu_kind(atlas, ((x + 1.5 + 0.5) / W, (y + 0.5) / W), (0.5 / 128 + 1.5 / 128, 0.5 / 128)) is None:
            bad(f'{name}: не находится у края')


# ------------------------------------------------------------------------------------------- (e) файлы
def check_files():
    print('(e) файлы')
    for name, K in FX.KINDS.items():
        p = os.path.join(FX.FX_DIR, name + '.png')
        im = Image.open(p)
        if im.size != (128, 128) or im.mode != 'RGBA':
            bad(f'{name}.png: {im.size} {im.mode}, нужно 128×128 RGBA')
        a = np.asarray(im.convert('RGBA'))
        if tuple(a[0, 1]) != (K, 90, 126, 3):
            bad(f'{name}.png: K в (1,0) = {tuple(a[0, 1])}')
        al = a[..., 3].copy()
        al[0, 0] = al[0, 1] = 0
        if al[:2].any() or al[-2:].any() or al[:, :2].any() or al[:, -2:].any():
            bad(f'{name}.png: рамка 2 px не прозрачная')
        if name not in ('beam_up', 'beam_down', 'beam_west', 'beam_east') + FX.TAILS + FX.EMBER_BEAMS:  # во весь спрайт
            if (al[FX.RR > FX.CIRCLE_ART_R] > 0).any():
                bad(f'{name}.png: рисунок за радиусом {FX.CIRCLE_ART_R}')
    st = Image.open(os.path.join(AZ, 'textures', 'item', 'moon_staff.png'))
    if st.size != (64, 64):
        bad(f'moon_staff.png: {st.size}, нужно 64×64')
    sa = np.asarray(st.convert('RGBA'))[..., 3]
    if ((sa > 0) & (sa < 255)).any():
        bad('moon_staff.png: полупрозрачные тексели (нужны только 0/255)')
    for m in ('moon_staff', 'moon_staff_cast', 'moon_staff_circle', 'moon_staff_beam', 'moon_staff_barrier',
              'stick_ember_orb', 'stick_ember_beam', 'stick_slash'):
        j = json.load(open(os.path.join(AZ, 'models', 'item', m + '.json'), encoding='utf-8'))
        for e in j.get('elements', []):
            for v in e['from'] + e['to']:
                if not -16 <= v <= 32:
                    bad(f'{m}: координата {v} вне −16..32')
            r = e.get('rotation')
            if r and (r['angle'] not in (-45, -22.5, 0, 22.5, 45) or r['axis'] not in 'xyz'):
                bad(f'{m}: поворот {r}')
    for i in ('moon_staff', 'moon_staff_circle', 'moon_staff_beam', 'moon_staff_barrier', 'stick_ember_orb',
              'stick_ember_beam', 'stick_slash'):
        if not os.path.exists(os.path.join(AZ, 'items', i + '.json')):
            bad(f'нет items/{i}.json')
    ymls = open(os.path.join(ROOT, 'items', 'azerus_weapons.yml'), encoding='utf-8').read()
    if 'azerus_moon_staff:' not in ymls or 'item_model: azerus:moon_staff' not in ymls:
        bad('items/azerus_weapons.yml: нет azerus_moon_staff')
    for word in ('аниме', 'anime', 'frieren', 'фрирен'):
        for p in (os.path.join(ROOT, 'items', 'azerus_weapons.yml'),):
            if word in open(p, encoding='utf-8').read().lower():
                bad(f'{os.path.basename(p)}: упоминание «{word}»')
    if not PROBLEMS:
        ok(f'{len(FX.KINDS)} спрайтов 128×128 (K, рамка, радиус), посох 64×64 (альфа 0/255), модели по правилам Java, items/*.json, yml')


# ------------------------------------------------------------------------------------------- (f) барьер
def check_barrier():
    print('(f) барьер (K10 hex_cell)')
    n0 = len(PROBLEMS)
    spr = np.asarray(Image.open(os.path.join(FX.FX_DIR, 'hex_cell.png')).convert('RGBA'))
    if not np.array_equal(spr, FX.sprite_hex_cell()):
        bad('hex_cell.png отличается от генератора — перезапустите moon_staff.py')
    cls = FX.hex_classes(spr)
    a = spr[..., 3].astype(int).copy()
    a[0, 0] = a[0, 1] = 0
    known = cls['rim'] | cls['glint'] | cls['bevel'] | cls['fill'] | cls['zero']
    if not known.all():
        bad(f'hex_cell: альфа вне классов 0/96..239/254/255: {sorted(set(a[~known].tolist()))}')
    if ((a > 0) & (FX.RHO > FX.HEX_R_PX)).any():
        bad('hex_cell: рисунок вне шестигранника ρ ≤ 60')
    if not (cls['rim'] == ((FX.RHO > 52) & (FX.RHO <= FX.HEX_R_PX))).all():
        bad('hex_cell: обвод (альфа 255) ≠ 52 < ρ ≤ 60')
    ng = int(cls['glint'].sum())
    if not 4 <= ng <= 40:
        bad(f'hex_cell: блёсток (альфа 254) {ng} текс.')
    # шейдер на сетке msLocal (центры текселей ×2)
    v = (np.arange(256) + 0.5) / 256
    lx, ly = np.meshgrid(v, v)
    ix, iy = np.floor(lx * 128).astype(int), np.floor(ly * 128).astype(int)
    outside = FX.RHO[iy, ix] > FX.HEX_R_PX
    rng = random.Random(10)
    for g in (0, 7, 24):
        for t in (0.0, 0.37, 1.3, 2.9, 777.7):
            for L in range(10):
                c = FX.shade(10, spr, lx, ly, FX.barrier_R(g, L) / 250.0, t)
                if (c[outside, 3] > 0).any():
                    bad(f'shade K10 g{g} L{L} t{t}: альфа вне шестигранника')
                if L == 0 and (c[..., 3] > 0).any():
                    bad(f'shade K10 g{g} t{t}: L=0, но альфа > 0')
            e = {L: FX.shade(10, spr, lx, ly, FX.barrier_R(g, L) / 250.0, t) for L in (4, 8, 9)}
            en = {L: float((np.clip(c[..., :3], 0, 1).mean(-1) * np.clip(c[..., 3], 0, 1)).sum()) for L, c in e.items()}
            if not en[9] > en[8] > en[4]:
                bad(f'shade K10 g{g} t{t}: яркость L4/L8/L9 = {en[4]:.0f}/{en[8]:.0f}/{en[9]:.0f}')
            t2 = rng.uniform(0, 1200 - 1e-3)
            a1 = FX.shade(10, spr, lx, ly, FX.barrier_R(g, 8) / 250.0, t2)
            a2 = FX.shade(10, spr, lx, ly, FX.barrier_R(g, 8) / 250.0, t2 + 1200.0)
            if not np.allclose(a1, a2, atol=1e-6):
                bad(f'shade K10 g{g}: t и t+1200 различаются (шов GameTime)')
    # раскладка: FX == keisan
    cells = FX.honeycomb(3, 5, 0.5)
    if FX.build_order(cells) != [7, 8, 10, 4, 3, 9, 6, 11, 5, 1, 13, 14, 2, 0, 12]:
        bad(f'FX.build_order {FX.build_order(cells)}')
    if FX.glow_slots(cells) != [11, 17, 24, 11, 18, 24, 5, 12, 18, 6, 12, 19, 0, 6, 13]:
        bad(f'FX.glow_slots {FX.glow_slots(cells)}')
    kp = os.path.join(REPO, 'core-python', 'scripts', 'core_magic', 'keisan.py')
    note = 'keisan.py нет — сверка раскладки пропущена'
    if os.path.exists(kp):
        import importlib.util
        spec = importlib.util.spec_from_file_location('ms_keisan', kp)
        K = importlib.util.module_from_spec(spec)
        try:
            spec.loader.exec_module(K)
            kc = [tuple(map(float, c)) for c in K.蜂巣(3, 5, 0.5)]
            if len(kc) != len(cells) or max(abs(a - b) for p, q in zip(kc, cells) for a, b in zip(p, q)) > 1e-9:
                bad(f'keisan.蜂巣 ≠ FX.honeycomb: {kc}')
            if list(K.組順(kc)) != FX.build_order(cells):
                bad(f'keisan.組順 {list(K.組順(kc))} ≠ FX {FX.build_order(cells)}')
            if list(K.輝き番号(kc)) != FX.glow_slots(cells):
                bad(f'keisan.輝き番号 {list(K.輝き番号(kc))} ≠ FX {FX.glow_slots(cells)}')
            note = 'раскладка сот, порядок сборки и фазы мерцания совпадают с core_magic/keisan.py'
        except AttributeError as e:
            bad(f'keisan.py без функций барьера: {e}')
        except Exception as e:
            bad(f'keisan.py не импортируется: {e!r}')
    if len(PROBLEMS) == n0:
        ok(f'hex_cell: классы альфы, ρ ≤ 60, обвод 8 px, блёсток {ng}; шейдер K10: альфа 0 вне шестигранника и '
           f'при L=0, L9 > L8 > L4, t ≡ t+1200; {note}')


# ------------------------------------------------------------------------------------------- (g) палка core_weapons
def check_stick():
    print('(g) палка core_weapons (K24..K33)')
    n0 = len(PROBLEMS)
    spr = FX.load_sprites()
    v = (np.arange(96) + 0.5) / 96
    lx, ly = np.meshgrid(v, v)
    # красная сфера = логика круга: те же альфы, что у K1..4, цвет — красный (R > G, R > B)
    for red, base in FX.EMBER_CIRCLE.items():
        a = FX.shade(FX.KINDS[red], spr[red], lx, ly, 0.8, 3.0)
        b = FX.shade(FX.KINDS[base], spr[base], lx, ly, 0.8, 3.0)
        if not np.allclose(a[..., 3], b[..., 3]):
            bad(f'{red}: альфа отличается от {base}')
        m = a[..., 3] > 0.5
        if m.any() and not (a[..., 0][m].mean() > a[..., 1][m].mean() + 0.2 and a[..., 0][m].mean() > a[..., 2][m].mean()):
            bad(f'{red}: не красный')

    def slash(K, age, crit=0, G=1000):
        R = G % 125 + 125 * crit
        return FX.shade(K, spr['slash_crescent' if K == 28 else 'slash_streak'], lx, ly, R / 250.0, (G + age) / 20.0)

    if slash(28, 2.5)[..., 3].max() < 0.9 or slash(28, 9.5)[..., 3].max() > 0.0 or slash(28, 40)[..., 3].max() > 0.0:
        bad('серп K28: не виден на 2.5 тика или виден после 9.5')
    if slash(29, 2.0)[..., 3].max() < 0.9 or slash(29, 7.0)[..., 3].max() > 0.0 or slash(29, 9.0, 1)[..., 3].max() > 0.0:
        bad('штрих K29: окно видимости не то')
    if slash(28, 1.0)[..., 3].sum() >= slash(28, 2.5)[..., 3].sum():
        bad('серп K28: дуга не растёт (1 тик → 2.5)')
    a = slash(28, 2.5, 0, G=1000)
    b = slash(28, 2.5, 0, G=1000 + 24000)          # шов дня: GameTime по модулю 24000 тиков
    if not np.allclose(a, b, atol=1e-4):
        bad('серп K28: шов GameTime (24000 тиков)')
    # луч «Углей»: при t и t+1200 одинаково, по краю ширины альфа ~0, на оси > 0.8
    for K, name in zip(range(30, 34), FX.EMBER_BEAMS):
        a = FX.shade(K, spr[name], lx, ly, 100 / 250.0, 7.3)
        b = FX.shade(K, spr[name], lx, ly, 100 / 250.0, 7.3 + 1200.0)
        if not np.allclose(a, b, atol=1e-3):
            bad(f'{name}: шов t/t+1200')
    a = FX.shade(30, spr['ember_beam_up'], np.array([0.5, 0.02]), np.array([0.5, 0.5]), 100 / 250.0, 1.0)
    if a[0, 3] < 0.8 or a[1, 3] > 0.25:
        bad(f'ember_beam_up: ось {a[0, 3]:.2f}, край {a[1, 3]:.2f}')
    if len(PROBLEMS) == n0:
        ok('сфера K24..27 = альфа K1..4, красная; серп K28 растёт 0..3 тика и гаснет к 9, штрих K29 — 1..6 '
           '(крит до 8), шов дня; луч K30..33: шов t/t+1200, ось ярко, край прозрачен')


# ------------------------------------------------------------------------------------------- --pack
def check_pack(path):
    print(f'(pack) {path}')
    with zipfile.ZipFile(path) as z:
        names = set(z.namelist())

        def cmp_png(rel, local):
            if rel not in names:
                bad(f'нет {rel}')
                return
            a = np.asarray(Image.open(io.BytesIO(z.read(rel))).convert('RGBA'))
            b = np.asarray(Image.open(local).convert('RGBA'))
            if a.shape != b.shape or not np.array_equal(a, b):
                bad(f'{rel}: пиксели отличаются от репозитория')

        for n in FX.KINDS:
            cmp_png(f'assets/azerus/textures/item/moon_staff_fx/{n}.png', os.path.join(FX.FX_DIR, n + '.png'))
        cmp_png('assets/azerus/textures/item/moon_staff.png', os.path.join(AZ, 'textures', 'item', 'moon_staff.png'))
        for rel in ['models/item/moon_staff.json', 'models/item/moon_staff_cast.json',
                    'models/item/moon_staff_circle.json', 'models/item/moon_staff_beam.json',
                    'models/item/moon_staff_barrier.json', 'items/moon_staff.json',
                    'models/item/stick_ember_orb.json', 'models/item/stick_ember_beam.json',
                    'models/item/stick_slash.json', 'items/stick_ember_orb.json', 'items/stick_ember_beam.json',
                    'items/stick_slash.json',
                    'items/moon_staff_circle.json', 'items/moon_staff_beam.json', 'items/moon_staff_barrier.json']:
            zr = 'assets/azerus/' + rel
            if zr not in names:
                bad(f'нет {zr}')
                continue
            if json.loads(z.read(zr)) != json.load(open(os.path.join(AZ, rel), encoding='utf-8')):
                bad(f'{zr}: отличается от репозитория')
        for f in ('moonstaff_vertex.glsl', 'moonstaff_fragment.glsl'):
            zr = 'assets/azerus/shaders/include/' + f
            if zr not in names:
                bad(f'нет {zr} (без него весь общий шейдер не соберётся!)')
            elif z.read(zr).decode() != open(os.path.join(INC, f), encoding='utf-8').read():
                bad(f'{zr}: отличается от репозитория')
        for ext, src in (('vsh', HOOK.VSH[0]), ('fsh', HOOK.FSH[0])):
            zr = f'assets/minecraft/shaders/core/rendertype_item_entity_translucent_cull.{ext}'
            if zr not in names:
                bad(f'нет {zr}')
            elif z.read(zr).decode() != open(src, encoding='utf-8', newline='').read():
                bad(f'{zr}: не равен общей копии с хуком')
    if not PROBLEMS:
        ok('пиксели, модели, include и общий шейдер в pack.zip совпадают с репозиторием')


def main(argv):
    quick = '--quick' in argv
    pack = argv[argv.index('--pack') + 1] if '--pack' in argv else None
    check_markers(quick)
    check_shared()
    check_glsl()
    check_logic()
    check_files()
    check_barrier()
    check_stick()
    if pack:
        check_pack(pack)
    print('(armor3d_check.py)')
    r = subprocess.run([sys.executable, '-I', os.path.join(HERE, 'armor3d_check.py')] + (['--pack', pack] if pack else []),
                       capture_output=True, text=True)
    last = (r.stdout.strip().split('\n') or [''])[-1]
    if r.returncode != 0:
        bad(f'armor3d_check.py: код {r.returncode}: {last}')
    else:
        ok(f'armor3d_check.py: {last}')
    print('ИТОГ:', 'ok' if not PROBLEMS else f'ОШИБОК: {len(PROBLEMS)}')
    return 0 if not PROBLEMS else 1


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
