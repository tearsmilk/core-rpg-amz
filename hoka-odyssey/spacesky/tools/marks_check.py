# Проверка МЕТОК профилей неба spacesky против ванильных цветов тумана и неба 1.21.11.
#
#   unzip client.jar 'data/minecraft/*' -d /tmp/vdata
#   python3 marks_check.py /tmp/vdata
#
# Выборка ванильных цветов — по данным client.jar (dimension_type, worldgen/biome, timeline/day) и байткоду клиента
# (FogRenderer: смешение тумана с небом по дальности, закат; погода: дождь/гроза; вода, лава, снег; затемнение экрана):
# смеси биомов, сутки, закат, погода — в байтах, как у клиента. Для каждой метки печатается расстояние до ближайшего
# ванильного направления цвета и наименьшее у ванили отношение красного к зелёному (у меток красный = 0).
import json, glob, os, sys, itertools
V = os.path.join(sys.argv[1] if len(sys.argv) > 1 else '.', 'data', 'minecraft')

def hx(s):
    s = s.lstrip('#'); v = int(s, 16)
    if len(s) == 8: return ((v >> 16) & 255, (v >> 8) & 255, v & 255, (v >> 24) & 255)
    return ((v >> 16) & 255, (v >> 8) & 255, v & 255, 255)

def q(c): return tuple(int(round(max(0, min(255, x)))) for x in c[:3])   # как ARGB-int (байты)
def lerp(a, b, t): return tuple(a[i] + (b[i] - a[i]) * t for i in range(3))
def mul(a, m): return tuple(a[i] * m[i] / 255.0 for i in range(3))
def gray(c, t, k):  # blend_to_gray(brightness k, factor t): к серому той же яркости * k
    l = (0.3 * c[0] + 0.59 * c[1] + 0.11 * c[2]) * k
    return lerp(c, (l, l, l), t)

def attr_colors(kind, attr):
    out = set()
    for f in glob.glob(os.path.join(V, kind, '*.json')):
        a = json.load(open(f)).get('attributes', {})
        v = a.get('minecraft:visual/' + attr)
        if isinstance(v, str): out.add(hx(v)[:3])
    return out

def biome_set(attr, default):
    s = attr_colors('worldgen/biome', attr) | {default}
    pts = set(s)
    for a, b in itertools.combinations(s, 2):   # смешение биомов на границе
        for k in range(1, 16): pts.add(q(lerp(a, b, k / 16)))
    return pts

def samples():
    T = json.load(open(os.path.join(V, 'timeline', 'day.json')))['tracks']
    sunrise = [hx(k['value']) for k in T['minecraft:visual/sunrise_sunset_color']['keyframes']]
    fogs = biome_set('fog_color', (0xc0, 0xd8, 0xff))
    skies = biome_set('sky_color', (0x78, 0xa7, 0xff))
    night_fog = (0x0f, 0x0f, 0x16)
    out = set()
    days = [k / 12 for k in range(13)]
    for f in fogs:
        for t in days:
            fm = q(mul(f, lerp((255, 255, 255), night_fog, t)))
            out.add(fm)
            for s in sunrise:
                for dt in (0.25, 0.5, 0.75, 1.0):
                    out.add(q(lerp(fm, s, dt * s[3] / 255.0)))
            for r in (0.5, 1.0):
                out.add(q(mul(fm, lerp((255, 255, 255), (127, 127, 153), r))))
                out.add(q(mul(fm, lerp((255, 255, 255), (64, 64, 76), r))))
    sky_t = set()
    for s in skies:
        for t in days:
            sm = q(lerp(s, (0, 0, 0), t))
            sky_t.add(sm)
            for r in (0.5, 1.0):
                sky_t.add(q(gray(sm, 0.75 * r, 0.6)))
                sky_t.add(q(gray(sm, 0.94 * r, 0.24)))
            sky_t.add(q(lerp(sm, (204, 204, 255), 0.5)))
    fog_only = set(out)
    for f in list(fog_only)[::7]:
        for s in list(sky_t)[::5]:
            for m in (0.1, 0.2, 0.29):
                out.add(q(lerp(f, s, m)))
    out |= sky_t
    # вода, лава, снег, Незер/Энд (fog.glsl действует везде)
    out |= biome_set('water_fog_color', (5, 5, 51))
    out |= {(153, 26, 0), (159, 186, 204)}
    for f in attr_colors('dimension_type', 'fog_color') | attr_colors('worldgen/biome', 'fog_color'):
        out.add(f)
    # затемнение (BossBar/эффект): r*(1-0.3t), g,b*(1-0.4t)
    extra = set()
    for c in list(out)[::3]:
        for t in (0.5, 1.0):
            extra.add((c[0] * (1 - 0.3 * t), c[1] * (1 - 0.4 * t), c[2] * (1 - 0.4 * t)))
    out |= extra
    return [c for c in out if max(c) > 0]

def norm(c):
    l = sum(x * x for x in c) ** 0.5
    return tuple(x / l for x in c)

def dist(a, b):
    a, b = norm(a), norm(b)
    return sum((a[i] - b[i]) ** 2 for i in range(3)) ** 0.5

if __name__ == '__main__':
    s = samples()
    n = [norm(c) for c in s]
    print('ванильных цветов:', len(s))
    print('наименьшее r/g у ванили: %.3f (метки: r = 0, шейдер требует r/g < 0.02)' % min(c[0] / c[1] for c in s if c[1] > 0))
    for i in range(16):
        for name, m in (('небо', (0, 24, i)), ('вода', (0, 24, 16 + i))):
            mn = norm(m)
            d = min(sum((a[k] - mn[k]) ** 2 for k in range(3)) ** 0.5 for a in n[::7])
            print('профиль %2d %s %-13s ближайший ванильный: %.3f' % (i, name, str(m), d))
