"""Preview of the profile-selection screen: the pack textures placed exactly like ProfilesScreen.java does, sample
texts (pixel font of the minecraft-nexo-gui skill, close to the Minecraft font) and the character rendered from a
sample skin by a port of CharacterRender.java.

    python3 tools/profiles_preview.py [skin.png]     # -> tools/preview/profiles.png (x2), profiles_pick.png

Without a skin a simple generated adventurer skin is used (no third-party art in the repo).
"""
import math
import os
import sys

from PIL import Image, ImageDraw

SKILL = os.environ.get('SKILL_SCRIPTS', '/root/.claude/skills/synced/c3b8cbb8-56f6-46e0-b1a5-a4a6350f4608_'
                       '9bcc0299-39b2-4127-bbdc-0f93b08ab14a/minecraft-nexo-gui/scripts')
sys.path.insert(0, SKILL)
import pixfont  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
UI = os.path.join(ROOT, 'pack', 'assets', 'rezusmenu', 'textures', 'ui')
OUT = os.path.join(ROOT, 'tools', 'preview')

# ProfilesScreen constants
LOGO = (8, 6)
LOBBY = (10, 366)
PANEL = (472, 36)
CARD_X, CARD_Y, BTN_DY, BUY_Y = 480, (72, 196), 98, 324
ARROW = (452, 72, 296)
ROW_Y0, ROW_STEP = 72, 62
FEET = (300, 346)
CHAR_PX, CHAR_SCALE, BUST_PX = 3, 2.25, 4
BACK_X, BACK_Y = (0, 256, 512), (0, 144, 288)


def tex(name):
    return Image.open(os.path.join(UI, name + '.png')).convert('RGBA')


# ----------------------------------------------------------------- sample skin and the CharacterRender port

def sample_skin():
    s = Image.new('RGBA', (64, 64), (0, 0, 0, 0))
    d = ImageDraw.Draw(s)

    def box(u, v, w, h, dd, c, front=None):
        d.rectangle((u + dd, v, u + dd + 2 * w - 1, v + dd - 1), fill=c)               # top + bottom
        d.rectangle((u, v + dd, u + 2 * dd + 2 * w - 1, v + dd + h - 1), fill=c)       # sides, front, back
        if front:
            front(u + dd, v + dd)
    skin_c, hair, tunic, belt, pants, boots = (222, 172, 140), (92, 58, 36), (52, 96, 168), (90, 60, 30), (70, 64, 60), (60, 40, 26)

    def face(x, y):
        d.rectangle((x, y, x + 7, y + 1), fill=hair)
        d.point((x + 1, y + 4), fill=(255, 255, 255)); d.point((x + 2, y + 4), fill=(40, 70, 140))
        d.point((x + 6, y + 4), fill=(255, 255, 255)); d.point((x + 5, y + 4), fill=(40, 70, 140))
        d.rectangle((x + 3, y + 6, x + 4, y + 6), fill=(150, 90, 80))
    box(0, 0, 8, 8, 8, skin_c, face)
    d.rectangle((8, 0, 15, 7), fill=hair)                                              # top of head
    d.rectangle((0, 8, 7, 9), fill=hair); d.rectangle((16, 8, 31, 9), fill=hair); d.rectangle((24, 8, 31, 15), fill=hair)

    def tun(x, y):
        d.rectangle((x, y + 8, x + 7, y + 8), fill=belt)
        d.point((x + 3, y + 8), fill=(220, 190, 80))
    box(16, 16, 8, 12, 4, tunic, tun)
    box(40, 16, 4, 12, 4, tunic)
    d.rectangle((40, 28, 55, 31), fill=skin_c)                                          # hands
    box(32, 48, 4, 12, 4, tunic)
    d.rectangle((32, 60, 47, 63), fill=skin_c)
    box(0, 16, 4, 12, 4, pants)
    d.rectangle((0, 28, 15, 31), fill=boots)
    box(16, 48, 4, 12, 4, pants)
    d.rectangle((16, 60, 31, 63), fill=boots)
    return s


PARTS = [  # u, v, w, h, d, x0, y0, z0, inflate, overlay   (CharacterRender.parts, arm width a)
    (0, 0, 8, 8, 8, -4, 24, -4, 0, False), (16, 16, 8, 12, 4, -4, 12, -2, 0, False),
    (40, 16, 'a', 12, 4, '-4-a', 12, -2, 0, False), (32, 48, 'a', 12, 4, 4, 12, -2, 0, False),
    (0, 16, 4, 12, 4, -4, 0, -2, 0, False), (16, 48, 4, 12, 4, 0, 0, -2, 0, False),
    (32, 0, 8, 8, 8, -4, 24, -4, 0.5, True), (16, 32, 8, 12, 4, -4, 12, -2, 0.25, True),
    (40, 32, 'a', 12, 4, '-4-a', 12, -2, 0.25, True), (48, 48, 'a', 12, 4, 4, 12, -2, 0.25, True),
    (0, 32, 4, 12, 4, -4, 0, -2, 0.25, True), (0, 48, 4, 12, 4, 0, 0, -2, 0.25, True)]
OUTLINE = (0x14, 0x12, 0x18, 255)


def render(skin, slim=False, yaw=28, pitch=10, scale=CHAR_SCALE, sub=3):
    a = 3 if slim else 4
    parts = []
    for p in PARTS:
        p = list(p)
        p[2] = a if p[2] == 'a' else p[2]
        p[5] = -4 - a if p[5] == '-4-a' else p[5]
        parts.append(p)
    cy, sy = math.cos(math.radians(yaw)), math.sin(math.radians(yaw))
    cp, sp = math.cos(math.radians(pitch)), math.sin(math.radians(pitch))

    def view(x, y, z):
        rx = x * cy + z * sy
        rz = -x * sy + z * cy
        return rx, y * cp - rz * sp, rz * cp + y * sp
    xs, ys = [], []
    for u, v, w, h, dd, x0, y0, z0, inf, ov in parts:
        for c in range(8):
            px = view(x0 - inf + (0 if c & 1 == 0 else w + 2 * inf), y0 - inf + (0 if c & 2 == 0 else h + 2 * inf),
                      z0 - inf + (0 if c & 4 == 0 else dd + 2 * inf))
            xs.append(px[0]); ys.append(px[1])
    pad = 1
    W = math.ceil((max(xs) - min(xs)) * scale - 1e-9) + 2 * pad
    H = math.ceil((max(ys) - min(ys)) * scale - 1e-9) + 2 * pad
    ox, oy = -min(xs) * scale + pad, max(ys) * scale + pad
    out = [[None] * W for _ in range(H)]
    depth = [[-1e9] * W for _ in range(H)]
    L = (-0.55, 0.55, 0.65)
    n = math.sqrt(sum(c * c for c in L)); L = tuple(c / n for c in L)
    px = skin.load()
    normals = [(0, 0, 1), (0, 0, -1), (-1, 0, 0), (1, 0, 0), (0, 1, 0)]
    for u, v, w, h, dd, x0, y0, z0, inf, ov in parts:
        bx, by, bz, bw, bh, bd = x0 - inf, y0 - inf, z0 - inf, w + 2 * inf, h + 2 * inf, dd + 2 * inf
        for face in range(5):
            nv = view(*normals[face])
            if nv[2] <= 1e-6:
                continue
            lit = (0.9, 0.62, 0.72, 0.72, 1.0)[face]
            u0, v0, tw, th = [(u + dd, v + dd, w, h), (u + 2 * dd + w, v + dd, w, h), (u, v + dd, dd, h),
                              (u + dd + w, v + dd, dd, h), (u + dd, v, w, dd)][face]
            bias = 0.01 if ov else 0
            for j in range(th):
                for i in range(tw):
                    r, g, b, al = px[u0 + i, v0 + j]
                    if ov and al < 128:
                        continue
                    col = (min(255, round(r * lit)), min(255, round(g * lit)), min(255, round(b * lit)), 255)
                    for sj in range(sub):
                        for si in range(sub):
                            fa = (i + (si + 0.5) / sub) / tw
                            fb = (j + (sj + 0.5) / sub) / th
                            x, y, z = [(bx + fa * bw, by + bh - fb * bh, bz + bd), (bx + bw - fa * bw, by + bh - fb * bh, bz),
                                       (bx, by + bh - fb * bh, bz + fa * bd), (bx + bw, by + bh - fb * bh, bz + bd - fa * bd),
                                       (bx + fa * bw, by + bh, bz + fb * bd)][face]
                            vx, vy, vz = view(x, y, z)
                            qx, qy = math.floor(ox + vx * scale), math.floor(oy - vy * scale)
                            if 0 <= qx < W and 0 <= qy < H and vz + bias > depth[qy][qx]:
                                depth[qy][qx] = vz + bias
                                out[qy][qx] = col
    ring = []
    for y in range(H):
        for x in range(W):
            if out[y][x] is None and any(0 <= x + dx < W and 0 <= y + dy < H and out[y + dy][x + dx] not in (None, OUTLINE)
                                         for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                ring.append((x, y))
    for x, y in ring:
        out[y][x] = OUTLINE
    img = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    for y in range(H):
        for x in range(W):
            if out[y][x]:
                img.putpixel((x, y), out[y][x])
    fx, fy, _ = view(0, 0, 0)
    return img, round(ox + fx * scale), round(oy - fy * scale)


def front(skin):
    """SkinFront.compose for a 64x64 skin: 16 x 32 front view."""
    out = Image.new('RGBA', (16, 32), (0, 0, 0, 0))
    for (sx, sy, w, h, dx, dy) in ((8, 8, 8, 8, 4, 0), (20, 20, 8, 12, 4, 8), (44, 20, 4, 12, 0, 8), (36, 52, 4, 12, 12, 8),
                                   (4, 20, 4, 12, 4, 20), (20, 52, 4, 12, 8, 20)):
        out.paste(skin.crop((sx, sy, sx + w, sy + h)), (dx, dy))
    for (sx, sy, w, h, dx, dy) in ((40, 8, 8, 8, 4, 0), (20, 36, 8, 12, 4, 8), (44, 36, 4, 12, 0, 8), (52, 52, 4, 12, 12, 8),
                                   (4, 36, 4, 12, 4, 20), (4, 52, 4, 12, 8, 20)):
        out.alpha_composite(skin.crop((sx, sy, sx + w, sy + h)), (dx, dy))
    return out


# ----------------------------------------------------------------- text

def text(img, x, y, s, color, h=12, lh=8, align='left', right=None, cx=None):
    """Like Screen.label / Screen.text: lh = canvas px per line (Minecraft line = 10 font px, cap ~7)."""
    k = lh / 10 * 1.0
    tmp = Image.new('RGBA', (max(1, pixfont.text_width(s) + 2), 12), (0, 0, 0, 0))
    pixfont.draw_text(tmp, 0, 1, s, color + (255,), shadow=(int(color[0] * .25), int(color[1] * .25), int(color[2] * .25), 255))
    tmp = tmp.resize((max(1, round(tmp.width * k * 1.2)), max(1, round(tmp.height * k * 1.2))), Image.NEAREST)
    if cx is not None:
        x = cx - tmp.width / 2
    elif right is not None:
        x = right - tmp.width
    img.alpha_composite(tmp, (int(x), int(y + (h - tmp.height) / 2)))


WHITE, GOLD, YELLOW, GREEN, GRAY = (255, 255, 255), (255, 170, 0), (255, 255, 85), (85, 255, 85), (170, 170, 170)


def item_icon(img, cx, cy, size, color):
    d = ImageDraw.Draw(img)
    r = size / 2
    d.rectangle((cx - r * .7, cy - r * .7, cx + r * .7, cy + r * .7), fill=color + (255,), outline=(20, 20, 20, 255))


def compose(mode='list'):
    W, H = 704, 400
    img = Image.new('RGBA', (W, H), (8, 8, 12, 255))
    cols = (256, 256, 192)
    for r in range(3):
        for c in range(3):
            img.alpha_composite(tex(f'pf_bg_{r}_{c}'), (BACK_X[c], BACK_Y[r]))
    img.alpha_composite(tex('pf_logo'), LOGO)
    img.alpha_composite(tex('pf_lobby'), LOBBY)
    text(img, 0, LOBBY[1], 'В лобби', WHITE, h=20, lh=9, cx=LOBBY[0] + 40)
    img.alpha_composite(tex('pf_panel_0'), PANEL)
    img.alpha_composite(tex('pf_panel_1'), (PANEL[0], PANEL[1] + 160))
    skin = Image.open(sys.argv[1]).convert('RGBA') if len(sys.argv) > 1 else sample_skin()
    spr, fx, fy = render(skin)
    spr = spr.resize((spr.width * CHAR_PX, spr.height * CHAR_PX), Image.NEAREST)
    img.alpha_composite(spr, (FEET[0] - fx * CHAR_PX, FEET[1] - fy * CHAR_PX))
    bust = front(skin).crop((0, 0, 16, 20)).resize((64, 80), Image.NEAREST)
    if mode == 'list':
        text(img, PANEL[0] + 10, PANEL[1] + 4, 'Ваши профили', WHITE, h=22, lh=11)
        text(img, 0, PANEL[1] + 4, '2 из 4', WHITE, h=22, lh=11, right=PANEL[0] + 214)
        cards = [('Мечник', (200, 200, 220), '31', 'Зиркония', ('Сила', '22'), ('Ловкость', '0'), ('Выносливость', '8'), True),
                 ('Маг', (230, 120, 40), '10', 'Начальная деревня', ('Интеллект', '10'), ('Ловкость', '0'), ('Выносливость', '0'), False)]
        for k, (cls, icol, lvl, loc, s1, s2, s3, active) in enumerate(cards):
            x, y = CARD_X, CARD_Y[k]
            img.alpha_composite(tex('pf_card'), (x, y))
            img.alpha_composite(bust, (x + 4, y + 12))
            item_icon(img, x + 80, y + 10, 10, icol)
            text(img, x + 88, y + 4, cls, GOLD)
            text(img, x + 88, y + 16, 'Уровень:', WHITE)
            text(img, 0, y + 16, lvl, YELLOW, right=x + 201)
            text(img, x + 88, y + 30, loc, WHITE)
            for i, (n, v) in enumerate((s1, s2, s3)):
                text(img, x + 88, y + 52 + 14 * i, n + ':', WHITE)
                text(img, 0, y + 52 + 14 * i, v, GREEN, right=x + 201)
            img.alpha_composite(tex('pf_btn_green' if active else 'pf_btn'), (x, y + BTN_DY))
            text(img, 0, y + BTN_DY, 'Продолжить' if active else 'Выбрать профиль', WHITE, h=18, lh=9, cx=x + 104)
        img.alpha_composite(tex('pf_btn_hover'), (CARD_X, CARD_Y[1] + BTN_DY))      # hover example
        img.alpha_composite(tex('pf_arrow_down'), (ARROW[0], ARROW[2]))
        img.alpha_composite(tex('pf_buy'), (CARD_X, BUY_Y))
        text(img, 0, BUY_Y + 2, '+ 649', WHITE, h=20, lh=10, cx=CARD_X + 98)
        item_icon(img, CARD_X + 124, BUY_Y + 12, 11, (90, 220, 230))
    else:
        text(img, PANEL[0] + 10, PANEL[1] + 4, 'Новый профиль: класс', WHITE, h=22, lh=11)
        rows = [('Мечник', (255, 85, 85), ('Ближний бой: щит и доспехи,', 'крепкий и защищённый.'), (200, 200, 220)),
                ('Стрелок', (85, 255, 85), ('Дальний бой: лук и арбалет,', 'бьёт точно издалека.'), (150, 110, 60)),
                ('Маг', (85, 255, 255), ('Стихии и заклинания,', 'урон и контроль.'), (230, 160, 40)),
                ('Вор', (255, 255, 85), ('Скрытный и ловкий боец', 'ближнего боя.'), (240, 200, 60))]
        for i, (n, c, desc, ic) in enumerate(rows):
            y = ROW_Y0 + ROW_STEP * i
            img.alpha_composite(tex('pf_row'), (CARD_X, y))
            item_icon(img, CARD_X + 26, y + 28, 28, ic)
            text(img, CARD_X + 54, y + 7, n, c, h=14, lh=10)
            for j, line in enumerate(desc):
                text(img, CARD_X + 54, y + 24 + 11 * j, line, GRAY, h=10, lh=7)
        img.alpha_composite(tex('pf_row_hover'), (CARD_X, ROW_Y0 + ROW_STEP))
        img.alpha_composite(tex('pf_buy'), (CARD_X, BUY_Y))
        text(img, 0, BUY_Y, 'Назад', WHITE, h=22, lh=9, cx=CARD_X + 104)
    # cursor arrow at a button
    return img


if __name__ == '__main__':
    os.makedirs(OUT, exist_ok=True)
    for mode, name in (('list', 'profiles.png'), ('pick', 'profiles_pick.png')):
        im = compose(mode)
        im.convert('RGB').resize((im.width * 2, im.height * 2), Image.NEAREST).save(os.path.join(OUT, name))
        print(os.path.join(OUT, name))
