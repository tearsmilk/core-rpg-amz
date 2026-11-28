# Обвесы TACZ → attachments.yml модуля guns и ассеты обвесов (наш скрипт, код TACZ не используется).
# Читает пак tacz_default_gun: index/attachments, data/attachments, display/attachments, tacz_tags/attachments,
# index/slot_adapters (+ их display), lang/ru_ru.json и allow_attachments нашего оружия. Пишет:
#   OUT_YML                         — каталог обвесов (метки, планки, обвесы с модификаторами), с русскими комментариями;
#   ASSETS/models/att/<id>.bbmodel  — модель обвеса (конвертация формата Bedrock geo → .bbmodel, как geo2bbmodel.py);
#   ASSETS/models/att/<id>_lod.bbmodel — упрощённая модель (прицелы, коллиматоры, ЛЦУ), для вида от третьего лица;
#   ASSETS/textures/att/<иконка>.png — иконка слота (без изменений, копия);
#   ASSETS/sounds/<звук>.ogg        — звуки установки/снятия (без изменений, копия);
#   ASSETS/NOTICE.md                — строки таблицы обвесов между метками <!-- attachments:begin/end -->.
# Числа — как в паке; функции Lua из data (FMJ, HE) — переведены в наши именованные операции (op).
# Встроенные обвесы оружия, которого у нас нет (AUG, P90, SPAS-12 и т.п.), не берутся.
# Использование: python3 -I attachments2yml.py PACK OUT_YML ASSETS
import json, os, re, shutil, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from geo2bbmodel import convert, load as geo_load, strip_comments  # noqa: E402

OUR_GUNS = ['ak47', 'ai_awp', 'glock_17', 'm4a1', 'm16a1', 'deagle', 'm1897']
SLOTS = ['scope', 'muzzle', 'stock', 'grip', 'laser', 'extended_mag']


def L(p):
    with open(p, encoding='utf-8-sig') as f:
        return json.loads(strip_comments(f.read()))


def rid(s):
    return s.replace('tacz:', '')


def q(s):
    return '"' + str(s).replace('\\', '\\\\').replace('"', '\\"') + '"'


def num(v):
    if isinstance(v, bool):
        return 'true' if v else 'false'
    if isinstance(v, int):
        return str(v)
    if isinstance(v, float):
        r = repr(round(v, 6))
        return r[:-2] if r.endswith('.0') else r
    return q(v)


def flow(v):
    if isinstance(v, dict):
        return '{' + ', '.join(k + ': ' + flow(x) for k, x in v.items()) + '}'
    if isinstance(v, list):
        return '[' + ', '.join(flow(x) for x in v) + ']'
    return num(v)


def fn_op(s):
    """Функция Lua из data TACZ → наша операция."""
    t = re.sub(r'\s+', '', s)
    m = re.fullmatch(r'y=([-0-9.]+)', t)
    if m:
        return {'set': float(m.group(1))}
    m = re.fullmatch(r'if\(x>([-0-9.]+)\)theny=x\+([-0-9.]+)elsey=xend', t)
    if m:
        return {'gt': float(m.group(1)), 'then-add': float(m.group(2))}
    m = re.fullmatch(r'if\(x>([-0-9.]+)\)theny=x\*([-0-9.]+)elsey=x\*([-0-9.]+)end', t)
    if m:
        return {'gt': float(m.group(1)), 'then-mul': float(m.group(2)), 'else-mul': float(m.group(3))}
    print('!! функция не переведена:', s, file=sys.stderr)
    return None


def mod(v):
    """{addend, percent, multiplier, function} → {add, percent, mul, op}."""
    if not isinstance(v, dict):
        return None
    out = {}
    if 'addend' in v: out['add'] = float(v['addend'])
    if 'percent' in v: out['percent'] = float(v['percent'])
    if 'multiplier' in v: out['mul'] = float(v['multiplier'])
    if 'function' in v:
        o = fn_op(v['function'])
        if o: out['op'] = o
    return out or None


def mods(d):
    out = {}
    for k in ['ads', 'aim_inaccuracy', 'inaccuracy', 'sneak_inaccuracy', 'lie_inaccuracy', 'effective_range',
              'damage', 'ammo_speed', 'rpm', 'head_shot', 'armor_ignore', 'pierce', 'knockback']:
        m = mod(d.get(k))
        if m:
            out[k.replace('_', '-')] = m
    if 'ads_addend' in d:
        a = out.setdefault('ads', {})
        a['add'] = a.get('add', 0) + float(d['ads_addend'])
    rec = d.get('recoil') or {}
    for k in ('pitch', 'yaw'):
        m = mod(rec.get(k))
        if m:
            out['recoil-' + k] = m
    if 'flash_hider' in d: out['flash-hider'] = float(d['flash_hider'])
    if 'extended_mag_level' in d: out['extended-mag-level'] = int(d['extended_mag_level'])
    if (d.get('explosion') or {}).get('explode'): out['explosion'] = True
    if (d.get('ignite') or {}).get('entity'): out['ignite'] = True
    s = d.get('silence')
    if s: out['silence'] = {'distance': float(s.get('distance_addend', 0)), 'sound': bool(s.get('use_silence_sound', True))}
    m = d.get('melee')
    if m: out['melee'] = {'damage': float(m.get('damage', 0)), 'distance': float(m.get('distance', 0))}
    if d.get('recoil_push'): out['recoil-push'] = True
    return out


def color(s):
    s = str(s)
    if s.lower().startswith('0x'): s = s[2:]
    return '#' + s.upper().rjust(6, '0')[-6:]


def main():
    P, out_yml, assets = sys.argv[1], sys.argv[2], sys.argv[3]
    D, A = P + '/data/tacz', P + '/assets/tacz'
    lang = L(A + '/lang/ru_ru.json')
    def name(key, fallback):
        return re.sub('§.', '', lang.get(key, fallback)).strip()

    tags = {}
    for root, _, files in os.walk(D + '/tacz_tags/attachments'):
        for f in files:
            if f.endswith('.json'):
                k = os.path.relpath(os.path.join(root, f), D + '/tacz_tags/attachments')[:-5]
                tags[k] = [('#' + rid(x[1:])) if x.startswith('#') else rid(x) for x in L(os.path.join(root, f))]
    allow = {g: tags.pop('allow_attachments/' + g) for g in OUR_GUNS}
    for k in list(tags):
        if k.startswith('allow_attachments/'):
            del tags[k]

    gun_builtins = set()
    gun_blocks = {}
    for g in OUR_GUNS:
        d = L(D + '/data/guns/' + g + '_data.json')
        bi = d.get('builtin_attachments') or {}
        blk = {'slots': d.get('allow_attachment_types', []), 'allow': allow[g],
               'builtin': {t: [rid(x) for x in v.get('attachments', [])] for t, v in bi.items()},
               'default': {t: rid(v['default_attached']) for t, v in bi.items() if v.get('default_attached')}}
        for v in blk['builtin'].values():
            gun_builtins.update(v)
        if d.get('extended_mag_ammo_amount'): blk['extended-mag'] = d['extended_mag_ammo_amount']
        sp = d.get('script_param') or {}
        if 'reload_xmag_1_feed' in sp:
            blk['reload-xmag'] = {'tactical': {'feed': sp['reload_xmag_1_feed'], 'total': sp['reload_xmag_1_cooldown']},
                                  'empty': {'feed': sp['empty_xmag_1_feed'], 'total': sp['empty_xmag_1_cooldown']}}
        ex = (d.get('bullet') or {}).get('explosion')
        if ex: blk['explosion'] = {'damage': ex.get('damage', 0), 'radius': ex.get('radius', 1), 'knockback': bool(ex.get('knockback', False))}
        gun_blocks[g] = blk

    os.makedirs(assets + '/models/att', exist_ok=True)
    os.makedirs(assets + '/textures/att', exist_ok=True)
    notice = []
    sounds = set()

    def conv(geo, png, out, label):
        res, _ = convert(geo_load(geo), open(png, 'rb').read(), None, [], set(), label, None, 'scope')
        with open(out, 'w', encoding='utf-8') as f:
            json.dump(res, f, ensure_ascii=False, separators=(',', ':'))

    def snd(path):
        n = rid(path).split('/')[-1]
        src = A + '/tacz_sounds/' + rid(path) + '.ogg'
        if os.path.exists(src):
            shutil.copyfile(src, assets + '/sounds/' + n + '.ogg')
            sounds.add(n)
            return 'gun_att_' + n
        return ''

    # ---- обвесы
    atts = []
    for f in sorted(os.listdir(D + '/index/attachments')):
        aid = f[:-5]
        idx = L(D + '/index/attachments/' + f)
        if idx.get('builtin_attachment') and aid not in gun_builtins:
            continue
        dp = D + '/data/attachments/' + rid(idx['data']) + '.json'
        data = L(dp) if os.path.exists(dp) else {}
        dis = L(A + '/display/attachments/' + rid(idx['display']) + '.json')
        a = {'id': aid, 'type': idx['type'], 'name': name(idx['name'], aid), 'sort': idx.get('sort', 0)}
        if idx.get('hidden'): a['hidden'] = True
        if idx.get('builtin_attachment'): a['builtin'] = True
        a['weight'] = float(data.get('weight', 0))
        files = []
        if dis.get('slot'):
            icon = rid(dis['slot']).split('/')[-1]
            src = A + '/textures/' + rid(dis['slot']) + '.png'
            if os.path.exists(src):
                shutil.copyfile(src, assets + '/textures/att/' + icon + '.png')
                a['icon'] = 'tacz/att/' + icon + '.png'
                files.append(('textures/att/' + icon + '.png', 'textures/' + rid(dis['slot']) + '.png', 'без изменений'))
        if dis.get('model'):
            geo = A + '/geo_models/' + rid(dis['model']) + '.json'
            png = A + '/textures/' + rid(dis['texture']) + '.png'
            if os.path.exists(geo) and os.path.exists(png):
                conv(geo, png, assets + '/models/att/' + aid + '.bbmodel', aid)
                a['model'] = 'guns/att/' + aid + '.bbmodel'
                if rid(dis['texture']).startswith('gun/'):
                    a['texture-from-gun'] = True
                files.append(('models/att/' + aid + '.bbmodel', 'geo_models/' + rid(dis['model']) + '.json, textures/' + rid(dis['texture']) + '.png', 'конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть'))
        lod = dis.get('lod')
        if lod:
            geo = A + '/geo_models/' + rid(lod['model']) + '.json'
            png = A + '/textures/' + rid(lod['texture']) + '.png'
            if os.path.exists(geo) and os.path.exists(png):
                conv(geo, png, assets + '/models/att/' + aid + '_lod.bbmodel', aid + '_lod')
                a['lod'] = 'guns/att/' + aid + '_lod.bbmodel'
                files.append(('models/att/' + aid + '_lod.bbmodel', 'geo_models/' + rid(lod['model']) + '.json, textures/' + rid(lod['texture']) + '.png', 'конвертация формата'))
        ss = dis.get('sounds') or {}
        a['sounds'] = {k: snd(ss[k]) for k in ('install', 'uninstall') if ss.get(k)}
        dd = {}
        if 'zoom' in dis: dd['zoom'] = [float(x) for x in dis['zoom']]
        if 'views' in dis: dd['views'] = dis['views']
        if 'views_fov' in dis: dd['views-fov'] = [float(x) for x in dis['views_fov']]
        if 'fov' in dis: dd['fov'] = float(dis['fov'])
        if dis.get('scope'): dd['scope'] = True
        if dis.get('sight'): dd['sight'] = True
        if 'idle_scope_height_adjust' in dis: dd['height-adjust'] = float(dis['idle_scope_height_adjust'])
        if dis.get('adapter'): dd['adapter'] = dis['adapter']
        if 'show_muzzle' in dis: dd['show-muzzle'] = bool(dis['show_muzzle'])
        if 'show_mount' in dis: dd['show-mount'] = bool(dis['show_mount'])
        if 'muzzle_flash_offset' in dis: dd['flash-offset'] = float(dis['muzzle_flash_offset'])
        if dis.get('laser'):
            l = dis['laser']
            dd['laser'] = {'color': color(l.get('default_color', '0xFF0000')), 'editable': bool(l.get('can_edit', False)),
                           'length': float(l.get('length', 75)), 'width': float(l.get('width', 0.008))}
        if dis.get('scope_lens'):
            sl = dis['scope_lens']
            dd['lens'] = {'tint': sl.get('tint', '#FFFFFF'), 'tint-strength': float(sl.get('tint_strength', 0)),
                          'vision': sl.get('vision_mode', 'NORMAL')}
        a['display'] = dd
        m = mods(data)
        if aid in tags.get('intrinsic/slug', []) or any(t.startswith('#') and aid in tags.get(t[1:], []) for t in tags.get('intrinsic/slug', [])):
            m['single-projectile'] = True
        a['mods'] = m
        a['_files'] = files
        atts.append(a)

    # ---- планки (slot adapters)
    adapters = []
    for f in sorted(os.listdir(D + '/index/slot_adapters')):
        sid = f[:-5]
        idx = L(D + '/index/slot_adapters/' + f)
        dis = L(A + '/display/slot_adapters/' + rid(idx['display']) + '.json')
        ad = {'id': sid, 'name': name(idx['name'], sid), 'slot': idx.get('slot', 'scope'), 'sort': idx.get('sort', 0),
              'allow': [('#' + rid(x[1:])) if x.startswith('#') else rid(x) for x in idx.get('allowed_attachments', [])],
              'mount-offset': [float(x) for x in dis.get('mount_offset', [0, 0, 0])]}
        files = []
        if dis.get('model'):
            geo = A + '/geo_models/' + rid(dis['model']) + '.json'
            png = A + '/textures/' + rid(dis['texture']) + '.png'
            if os.path.exists(geo) and os.path.exists(png):
                conv(geo, png, assets + '/models/att/' + sid + '.bbmodel', sid)
                ad['model'] = 'guns/att/' + sid + '.bbmodel'
                files.append(('models/att/' + sid + '.bbmodel', 'geo_models/' + rid(dis['model']) + '.json, textures/' + rid(dis['texture']) + '.png', 'конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть'))
        ad['_files'] = files
        adapters.append(ad)

    # ---- attachments.yml
    o = []
    o.append('# Обвесы оружия (модуль guns) — весь стандартный пак TACZ tacz_default_gun: названия (ru_ru), вес, вид и')
    o.append('# модификаторы характеристик. Сделан скриптом tools/tacz2bb/attachments2yml.py из файлов пака (числа как есть).')
    o.append('# Модели, иконки и звуки обвесов — TACZ, CC BY-NC-ND 4.0 (без изменений, см. NOTICE.md в jar, папка tacz/).')
    o.append('# Свой файл без пересборки — module/guns/attachments.yml (тогда этот из jar не читается).')
    o.append('#')
    o.append('# Модификатор (как в TACZ): значение = (база + сумма add) × max(0, 1 + сумма percent) × произведение mul,')
    o.append('# потом op: set — заменить числом; gt/then-add — «если больше gt, прибавить»; gt/then-mul/else-mul — «если больше')
    o.append('# gt, умножить на then-mul, иначе на else-mul». Ключи: ads — время прицеливания; inaccuracy — разброс от бедра')
    o.append('# (стоя и в движении); aim-/sneak-/lie-inaccuracy — в прицеле, присев, лёжа; recoil-pitch/yaw — отдача;')
    o.append('# effective-range — дальности полос урона; damage, ammo-speed, rpm, head-shot, armor-ignore, pierce, knockback;')
    o.append('# flash-hider — размер вспышки; extended-mag-level — какой из extended-mag оружия (1–3); silence — глушитель')
    o.append('# (distance — на сколько блоков тише, sound — звук «с глушителем»); explosion — фугасный (взрыв оружия);')
    o.append('# ignite — поджигает; single-projectile — одна пуля вместо дроби (пуля «slug»); melee — штык/приклад (пока')
    o.append('# только хранится, ближнего боя нет); recoil-push — пока не используется.')
    o.append('')
    o.append('# Метки (#имя в списках allow): какие обвесы входят. Внутри могут быть другие метки.')
    o.append('tags:')
    for k in sorted(tags):
        o.append('  ' + q(k) + ': ' + flow(tags[k]))
    o.append('')
    o.append('# Повышающие планки (TACZ slot adapters): ставятся в слот (scope), разрешают ещё обвесы (allow) и поднимают')
    o.append('# их на mount-offset (пикселей модели). Своих характеристик нет.')
    o.append('adapters:')
    for ad in adapters:
        o.append('  ' + ad['id'] + ':')
        for k in ('name', 'slot', 'sort', 'allow', 'model', 'mount-offset'):
            if k in ad:
                o.append('    ' + k + ': ' + flow(ad[k]))
    o.append('')
    o.append('# Обвесы. type — слот (scope, muzzle, stock, grip, laser, extended_mag); hidden — не показывать в списках')
    o.append('# (только если разрешён оружию); builtin — встроенный (часть оружия: ставится без предмета, при снятии')
    o.append('# ничего не возвращается); item — предмет hoitems; display — вид (zoom — кратности прицела, views — какая')
    o.append('# точка взгляда scope_view для каждой кратности, views-fov / fov — угол обзора модели в прицеле, scope — оптика,')
    o.append('# sight — коллиматор, height-adjust — подъём оружия у глаза, adapter — какую кость оружия показать,')
    o.append('# show-muzzle — не прятать дульный срез, flash-offset — вспышка дальше на столько пикселей, laser — луч ЛЦУ).')
    o.append('attachments:')
    for a in atts:
        o.append('  ' + a['id'] + ':')
        o.append('    type: ' + a['type'])
        o.append('    name: ' + q(a['name']))
        o.append('    sort: ' + num(a['sort']))
        if a.get('hidden'): o.append('    hidden: true')
        if a.get('builtin'): o.append('    builtin: true')
        o.append('    weight: ' + num(a['weight']))
        if not a.get('builtin'): o.append('    item: att_' + a['id'])
        for k in ('icon', 'model', 'lod'):
            if k in a: o.append('    ' + k + ': ' + a[k])
        if a.get('texture-from-gun'): o.append('    texture-from-gun: true')
        if a['sounds']: o.append('    sounds: ' + flow(a['sounds']))
        if a['display']: o.append('    display: ' + flow(a['display']))
        if a['mods']:
            o.append('    mods:')
            for k, v in a['mods'].items():
                o.append('      ' + k + ': ' + flow(v))
    with open(out_yml, 'w', encoding='utf-8') as f:
        f.write('\n'.join(o) + '\n')

    # ---- NOTICE.md
    rows = ['<!-- attachments:begin (строки сделаны tools/tacz2bb/attachments2yml.py) -->', '',
            '### Обвесы (все из `tacz_default_gun`, кроме встроенных обвесов оружия, которого у нас нет)', '',
            '| Файл здесь | Оригинал в паке (`assets/tacz/…`) | Что сделано |', '|---|---|---|']
    for a in atts + adapters:
        for here, orig, what in a['_files']:
            rows.append('| `' + here + '` | `' + orig + '` | ' + what + ' |')
    for n in sorted(sounds):
        rows.append('| `sounds/' + n + '.ogg` | `tacz_sounds/attachments/' + n + '.ogg` | без изменений (установка/снятие обвеса) |')
    rows += ['', '<!-- attachments:end -->']
    np = assets + '/NOTICE.md'
    t = open(np, encoding='utf-8').read()
    block = '\n'.join(rows)
    if '<!-- attachments:begin' in t:
        t = re.sub(r'<!-- attachments:begin.*?<!-- attachments:end -->', lambda _: block, t, flags=re.S)
    else:
        t = t.rstrip('\n') + '\n\n' + block + '\n'
    open(np, 'w', encoding='utf-8').write(t)

    # ---- разделы attachments оружия (для config.yml) — в stdout
    for g, blk in gun_blocks.items():
        print('## ' + g)
        for k, v in blk.items():
            print('    ' + k + ': ' + flow(v))
    print('обвесов:', len(atts), 'планок:', len(adapters), 'звуков:', len(sounds), file=sys.stderr)


if __name__ == '__main__':
    main()
