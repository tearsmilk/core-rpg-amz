#!/usr/bin/env python3
"""Full-screen HUD mock: renders MythicHUD layouts straight from the YAML (a small emulator of
MythicHUD's layout rules) over a pixel landscape with the vanilla hotbar, item name, action bar and
chat, at 1920x1080 / GUI scale 4 = 480x270 GUI px, saved x3 (1440x810).

  python3 -I mythichud-azerus/tools/preview.py            -> preview/new_hud.png, preview/old_hud.png,
                                                          preview/new_hud_casting.png, preview/compare.png

Emulated rules (see gen_yaml.py header): element position %/offset, layer offset (y up), first layer
on top, conditions (placeholder/effect/gamemode; hide/texture/color), functional texture layers
(horizontal increments), text layers with the plugin's bitmap fonts (align left/center/right,
scale), head layer (8x8 face x size). Text cell top = layer y. Things the emulator cannot know
exactly (sub-pixel text scale 0.7 of the old HUD) are approximated with nearest-neighbour scaling.
"""
import math
import os
import re
import sys
import zipfile
import io

import yaml
from PIL import Image

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
REPO = os.path.normpath(os.path.join(ROOT, ".."))
SNAP = os.path.join(REPO, "server/plugins/MythicHUD")
HLZIP = os.path.join(REPO, "server/plugins/Nexo/pack/external_packs/hl-resourcepack.zip")
W, H, SCALE = 480, 270, 3

TEX_ROOTS = [os.path.join(ROOT, "source-pack/assets/mythichud/textures"),
             os.path.join(SNAP, "source-pack/assets/mythichud/textures")]

NAMED = {"gold": "#ffaa00", "green": "#55ff55", "white": "#ffffff", "red": "#ff5555", "blue": "#5555ff",
         "yellow": "#ffff55", "gray": "#aaaaaa", "dark_gray": "#555555", "aqua": "#55ffff"}


def hexc(s):
    s = s.lstrip("#")
    return tuple(int(s[i:i + 2], 16) for i in (0, 2, 4))


def load_tex(path):
    for r in TEX_ROOTS:
        p = os.path.join(r, path)
        if os.path.exists(p):
            return Image.open(p).convert("RGBA")
    raise FileNotFoundError(path)


# ---------------------------------------------------------------------------------------------- fonts
class Font:
    def __init__(self, providers):
        self.glyphs = {}
        self.spaces = {}
        for pname, p in providers.items():
            if p.get("type") == "space":
                self.spaces.update(p.get("advances", {}))
            elif p.get("type") == "bitmap":
                try:
                    img = load_tex(p["texture"])
                except FileNotFoundError:
                    continue
                rows = p["chars"]
                ncol = len(rows[0])
                cw, ch = img.width // ncol, img.height // len(rows)
                height = p.get("height", 8)
                asc = p.get("ascent")
                for ry, row in enumerate(rows):
                    for rx, c in enumerate(row):
                        if c == "\u0000" or c in self.glyphs:
                            continue
                        cell = img.crop((rx * cw, ry * ch, rx * cw + cw, ry * ch + ch))
                        bb = cell.getbbox()
                        if bb is None:
                            wpx = 0
                        else:
                            wpx = bb[2]
                        f = height / ch
                        if f != 1:
                            cell = cell.resize((max(1, round(cw * f)), max(1, round(ch * f))), Image.NEAREST)
                        dy = 0 if asc is None else (7 - asc) - (height - 8)
                        self.glyphs[c] = (cell, round(wpx * f) + 1, dy)

    def render(self, segments):
        """segments: [(text, rgb)] -> RGBA image (cell height 8 + room for tall glyphs)."""
        out = Image.new("RGBA", (2000, 24), (0, 0, 0, 0))
        x = 0
        for txt, col in segments:
            for c in txt:
                if c in self.spaces:
                    x += self.spaces[c]
                    continue
                g = self.glyphs.get(c)
                if g is None:
                    x += 4
                    continue
                cell, adv, dy = g
                tinted = tint_glyph(cell, col)
                out.alpha_composite(tinted, (x, 8 + dy))
                x += adv
        bb = out.getbbox()
        return out.crop((0, 0, max(1, x - 1), 24)), x - 1


def tint_glyph(cell, col):
    px = cell.load()
    im = cell.copy()
    q = im.load()
    for y in range(im.height):
        for x in range(im.width):
            r, g, b, a = px[x, y]
            if a:
                q[x, y] = (r * col[0] // 255, g * col[1] // 255, b * col[2] // 255, a)
    return im


def load_fonts():
    fonts = {}
    for d in (os.path.join(SNAP, "hud_assets/font"),):
        for f in sorted(os.listdir(d)):
            data = yaml.safe_load(open(os.path.join(d, f), encoding="utf-8")) or {}
            for name, spec in data.items():
                fonts[name] = Font(spec.get("providers", {}))
    return fonts


def parse_mm(s):
    segs, col = [], (255, 255, 255)
    for part in re.split(r"(<[^>]+>)", s):
        if not part:
            continue
        m = re.fullmatch(r"<(#?[0-9a-zA-Z_]+)>", part)
        if m:
            t = m.group(1)
            if t.startswith("#"):
                col = hexc(t)
            elif t in NAMED:
                col = hexc(NAMED[t])
            continue
        segs.append((part, col))
    return segs


# ---------------------------------------------------------------------------------------------- state
class State:
    def __init__(self, ph, listeners, effects=(), gamemode="survival", head=None):
        self.ph = ph
        self.listeners = listeners
        self.effects = set(effects)
        self.gamemode = gamemode
        self.head = head

    def resolve(self, s):
        def rep(m):
            k = m.group(0)
            return str(self.ph.get(k, k))
        prev = None
        while prev != s:
            prev = s
            s = re.sub(r"%[^%\s]+%", rep, s, count=0)
            if s == prev:
                break
        return s


def num(s):
    try:
        return float(s)
    except (TypeError, ValueError):
        return None


def eval_cond(line, st):
    line = line.split(" #")[0].strip()
    m = re.match(r"(\w+)\{(.*)\}\s+(true|false)\s+(\w+)\s*(.*)$", line)
    if not m:
        raise ValueError("bad condition: " + line)
    typ, args, want, action, arg = m.groups()
    kv = {}
    for a in args.split(";"):
        if "=" in a:
            k, v = a.split("=", 1)
            kv[k.strip()] = v.strip()
    if typ == "placeholder":
        a = st.resolve(kv.get("ph", kv.get("1", "")))
        b = st.resolve(kv.get("v", kv.get("2", "")))
        op = kv.get("o", "=")
        na, nb = num(a), num(b)
        if op in ("=", "=="):
            res = (na == nb) if (na is not None and nb is not None) else (a == b)
        elif na is None or nb is None:
            res = False
        else:
            res = {">": na > nb, ">=": na >= nb, "=>": na >= nb, "<": na < nb, "<=": na <= nb, "=<": na <= nb}[op]
    elif typ == "effect":
        res = kv["e"] in st.effects
    elif typ == "gamemode":
        res = st.gamemode in kv["gm"].split(",")
    elif typ == "progress":
        res = True
    else:
        res = True
    ok = res == (want == "true")
    return action, ok, arg.strip()


def layer_state(layer, st):
    hidden, texture, color = False, None, None
    for c in reversed(layer.get("conditions") or []):
        action, ok, arg = eval_cond(c, st)
        if action == "hide" and not ok:
            hidden = True
        elif action == "texture" and ok:
            texture = arg
        elif action == "color" and ok:
            color = arg
    return hidden, texture, color


# ---------------------------------------------------------------------------------------------- render
def face_img(size):
    face = [
        "HHHHHHHH",
        "HHHHHHHH",
        "HSSSSSSH",
        "SSSSSSSS",
        "SWBSSBWS",
        "SSSNNSSS",
        "SSMMMMSS",
        "SSMSSMSS",
    ]
    pal = {"H": (58, 40, 28), "S": (196, 140, 104), "W": (240, 240, 240), "B": (52, 72, 140),
           "N": (150, 96, 70), "M": (120, 70, 50)}
    im = Image.new("RGBA", (8, 8))
    for y, r in enumerate(face):
        for x, ch in enumerate(r):
            im.putpixel((x, y), pal[ch] + (255,))
    # a bit of shading on the hair edge
    return im.resize((8 * size, 8 * size), Image.NEAREST)


def tint(img, col):
    c = hexc(col)
    return tint_glyph(img, c)


def draw_layout(canvas, layout, assets, fonts, st, log):
    for ename, el in layout["elements"].items():
        a = assets[el["asset"]]
        hidden = False
        for c in a.get("conditions") or []:
            action, ok, _ = eval_cond(c, st)
            if action == "hide" and not ok:
                hidden = True
        if hidden:
            continue
        pos, off = el.get("position", {}), el.get("offset", {})
        ax = W * float(pos.get("x", 0)) / 100 + off.get("x", 0)
        ay = H - H * float(pos.get("y", 0)) / 100 - off.get("y", 0)
        layers = a["layers"]
        # listener of the element (first functional layer) for %fill-value%/%fill-max%
        fv = fm = None
        for l in layers.values():
            if isinstance(l, dict) and "function" in l:
                fv, fm = st.listeners[l["function"]["listener"]]
                break
        # element align (old HUD uses it): shift by the texture bbox
        shift = 0
        ealign = el.get("align")
        if ealign in ("center", "left"):
            xs = []
            for l in layers.values():
                if "texture" in l:
                    try:
                        w = load_tex(l["texture"]["path"]).width
                    except FileNotFoundError:
                        continue
                    lx = (l.get("offset") or {}).get("x", 0)
                    if l.get("align") == "left":
                        xs += [lx - w, lx]
                    else:
                        xs += [lx, lx + w]
            if xs:
                bw = max(xs) - min(xs)
                shift = -(min(xs) + bw / 2) if ealign == "center" else -(max(xs))
        for lname, l in reversed(list(layers.items())):
            hidden, tex_over, col_over = layer_state(l, st)
            if hidden:
                continue
            o = l.get("offset") or {}
            x = ax + shift + o.get("x", 0)
            y = ay - o.get("y", 0)
            if "head" in l:
                img = st.head if st.head is not None else face_img(l["head"].get("size", 4))
                canvas.alpha_composite(img, (round(x), round(y)))
                log.append((ename, lname, round(x), round(y), img.width, img.height))
                continue
            if "texture" in l:
                path = tex_over or l["texture"]["path"]
                img = load_tex(path)
                col = col_over or l.get("color")
                if col:
                    img = tint(img, col if col.startswith("#") else NAMED.get(col, "#ffffff"))
                if "function" in l:
                    v, m = st.listeners[l["function"]["listener"]]
                    inc = l["function"]["increment"]
                    ratio = 0 if not m else max(0.0, min(1.0, v / m))
                    steps = math.ceil(ratio * inc) if ratio > 0 else 0
                    wv = round(img.width * steps / inc)
                    if wv <= 0:
                        continue
                    img = img.crop((0, 0, wv, img.height))
                al = l.get("align")
                if al == "left":
                    x -= img.width
                elif al == "center":
                    x -= img.width / 2
                canvas.alpha_composite(img, (round(x), round(y)))
                log.append((ename, lname, round(x), round(y), img.width, img.height))
            elif "text" in l:
                txt = l["text"]
                if fv is not None:
                    txt = txt.replace("%fill-value%", fmt(fv)).replace("%fill-max%", fmt(fm))
                if "changeoutput_equals_input" in txt:      # skill-name chain -> sample value
                    txt = st.ph.get("__skillname__", "")
                txt = st.resolve(txt)
                font = fonts.get(l.get("font", "default"), fonts["default"])
                segs = parse_mm(txt)
                img, wv = font.render(segs)
                shc = l.get("shadow")
                if shc:
                    shimg, _ = font.render([(t, hexc(shc[:7])) for t, _c in segs])
                    sh_canvas = Image.new("RGBA", (img.width + 1, img.height + 1), (0, 0, 0, 0))
                    sh_canvas.alpha_composite(shimg, (1, 1))
                    sh_canvas.alpha_composite(img, (0, 0))
                    img = sh_canvas
                sc = l.get("scale", 1)
                if isinstance(sc, (int, float)) and sc != 1:
                    img = img.resize((max(1, round(img.width * sc)), max(1, round(img.height * sc))), Image.NEAREST)
                    wv = round(wv * sc)
                    y0 = 8 * sc
                else:
                    y0 = 8
                al = l.get("align", "right")
                if al == "left":
                    x -= wv
                elif al == "center":
                    x -= wv / 2
                canvas.alpha_composite(img, (round(x), round(y - y0)))
                log.append((ename, lname, round(x), round(y), wv, 8))


def fmt(v):
    return ("%.2f" % v).rstrip("0").rstrip(".")


# ---------------------------------------------------------------------------------------------- scene
def scene():
    im = Image.new("RGBA", (W, H))
    sky = [(118, 167, 230), (130, 178, 236), (144, 189, 240), (160, 200, 243), (176, 211, 246)]
    for y in range(H):
        im.paste(sky[min(4, y * 5 // 150)] + (255,), (0, y, W, y + 1))
    # far hills
    for x in range(W):
        h1 = int(128 + 10 * math.sin(x / 37) + 6 * math.sin(x / 13 + 1))
        for y in range(h1, H):
            im.putpixel((x, y), (96, 132, 120, 255))
        h2 = int(150 + 8 * math.sin(x / 23 + 2) + 4 * math.sin(x / 7))
        for y in range(h2, H):
            g = (70, 125, 58) if (x // 4 + y // 4) % 2 else (76, 132, 62)
            im.putpixel((x, y), g + (255,))
    # ground blocks
    for y in range(176, H):
        for x in range(W):
            bx, by = x // 16, (y - 176) // 16
            if y - 176 < 4 + (bx % 3):
                c = (92, 160, 64) if (x * 7 + y * 3) % 5 else (80, 146, 56)
            else:
                c = (122, 88, 56) if (x * 5 + y * 11 + bx) % 7 else (104, 74, 46)
            im.putpixel((x, y), c + (255,))
    # a tree
    for y in range(120, 178):
        for x in range(392, 398):
            im.putpixel((x, y), (96, 70, 40, 255) if x % 3 else (84, 60, 34, 255))
    for y in range(92, 132):
        for x in range(370, 422):
            if (x - 396) ** 2 / 26 ** 2 + (y - 112) ** 2 / 20 ** 2 < 1:
                im.putpixel((x, y), (48, 110, 40, 255) if (x * 3 + y) % 5 else (60, 128, 48, 255))
    # crosshair
    for i in range(-4, 5):
        im.putpixel((240 + i, 135), (230, 230, 230, 255))
        im.putpixel((240, 135 + i), (230, 230, 230, 255))
    return im


def zip_sprite(name):
    with zipfile.ZipFile(HLZIP) as z:
        return Image.open(io.BytesIO(z.read("assets/minecraft/textures/gui/sprites/hud/" + name))).convert("RGBA")


def item_icons():
    base = os.path.join(REPO, "nexo-items/pack/assets/azerus/textures/item")
    names = ["reforged/netherite_chestplate.png", "armor/guren_helmet.png", "reforged/diamond_boots.png",
             "reforged/golden_chestplate.png", "armor/morikashi_leggings.png"]
    out = []
    for n in names:
        p = os.path.join(base, n)
        if os.path.exists(p):
            out.append(Image.open(p).convert("RGBA"))
    sk = os.path.join(SNAP, "source-pack/assets/mythichud/textures/assets/skill_casting_bar/skills")
    for n in ("firebolt.png", "heal.png"):
        out.append(load_tex("assets/skill_casting_bar/skills/" + n))
    return out


def draw_vanilla(canvas, fonts, hotbar_img, sel_img, selected=2, item_name="Багровая коса",
                 actionbar="+3 монеты", chat=True):
    hx, hy = W // 2 - 91, H - 22
    canvas.alpha_composite(hotbar_img, (hx, hy))
    canvas.alpha_composite(sel_img, (hx - 1 + 20 * selected, hy - 1))
    for i, ic in enumerate(item_icons()):
        canvas.alpha_composite(ic.resize((16, 16), Image.NEAREST), (hx + 3 + 20 * i, hy + 3))
    f = fonts["default"]
    # stack count on slot 6
    img, wv = f.render([("16", (255, 255, 255))])
    shadow, _ = f.render([("16", (63, 63, 63))])
    sx = hx + 3 + 20 * 5 + 17 - wv - 1
    canvas.alpha_composite(shadow, (sx + 1, hy + 3 + 9 - 8 + 1))
    canvas.alpha_composite(img, (sx, hy + 3 + 9 - 8))
    if item_name:
        img, wv = f.render([(item_name, (255, 85, 85))])
        canvas.alpha_composite(img, (W // 2 - wv // 2, H - 59 - 8))
    if actionbar:
        img, wv = f.render([(actionbar, (255, 210, 90))])
        canvas.alpha_composite(img, (W // 2 - wv // 2, H - 68 - 4 - 8))
    if chat:
        # vanilla chat: 320 px wide (chat width 1.0), last line ends at H-40, 9 px lines, fades after 10 s
        lines = ["<Mia> гоу в данж на 20 ур", "[Сервер] Разлом откроется через 5 мин"]
        y = H - 40 - 9 * len(lines)
        for ln in lines:
            bg = Image.new("RGBA", (322, 9), (0, 0, 0, 110))
            canvas.alpha_composite(bg, (0, y))
            img, wv = f.render([(ln, (240, 240, 240))])
            canvas.alpha_composite(img, (2, y + 1 - 8))
            y += 9


def load_assets(files):
    out = {}
    for f in files:
        out.update(yaml.safe_load(open(f, encoding="utf-8")) or {})
    return out


def sample_state(casting=False, held=0, absorption=0):
    ph = {
        "%player_health_rounded%": "56", "%player_max_health_rounded%": "80", "%player_absorption%": str(absorption),
        "%math_0_{player_health_rounded}+{player_absorption}%": str(56 + absorption),
        "%vault_eco_balance_fixed%": "12450", "%vault_eco_balance_formatted%": "12 450 монет", "%corevault_balance_short%": "12\u00a0450",
        "%deluxemenus_meta_currency_BOOLEAN%": "false", "%deluxemenus_meta_foodicon_BOOLEAN%": "false",
        "%azerus_gems%": "320", "__skillname__": "<gold>Огненная стрела" if casting else "",
        "%mmocore_level%": "27", "%math_1_{mmocore_experience}*100/{mmocore_next_level}%": "45.3",
        "%hl_level%": "27", "%mmocore_mana_name%": "Мана", "%mmocore_mana%": "120",
        "%mmocore_class_id%": "MAGE", "%mmocore_is_casting%": "true" if casting else "false",
        "%player_held_item_slot%": str(held), "%player_world%": "azerus",
        "%player_x%": "-1284", "%player_y%": "72", "%player_z%": "533",
        "%player_remaining_air%": "300", "%player_max_air%": "300",
        "%player-j17_is_burning%": "no", "%player-j17_is_freezing%": "no",
    }
    skills = ["FIREBOLT", "HEAL", "FREEZE", "BLINK", "STARFALL", "MAGICAL_SHIELD"]
    cds = ["0", "4", "0", "12", "0", "noskill"]
    for i in range(1, 7):
        ph["%%mmocore_id_bound_%d%%" % i] = skills[i - 1] if cds[i - 1] != "noskill" else ""
        ph["%%mmocore_cooldown_bound_%d%%" % i] = cds[i - 1]
        ph["%%math_0:_{utils_shortcut_noskillcd_check:%d}+1%%" % i] = str(int(cds[i - 1]) + 1) if cds[i - 1] not in ("0", "noskill") else "1"
        ph["%%utils_shortcut_mana_check:%d%%" % i] = "200" if i == 5 else "10"
    sn = ""
    ph_names = re.compile(r".*")
    listeners = {"health": (56, 80), "food": (15, 20), "armor": (12, 20), "armor40": (14, 40), "air": (300, 300),
                 "mana": (120, 150), "mmoexp": (453, 1000), "rqexp": (620, 1000), "absorption": (absorption, 80),
                 "experience": (0.4, 1)}
    return State(ph, listeners)


def resolve_skillnames(st):
    # the long changeoutput chain -> pretend nothing was cast just now (empty)
    class D(dict):
        pass
    return st


def render(kind, casting=False, held=0):
    fonts = load_fonts()
    st = sample_state(casting, held)
    canvas = scene()
    log = []
    if kind == "new":
        assets = load_assets([os.path.join(ROOT, "hud_assets/hud/azerus-hud.yml"),
                              os.path.join(ROOT, "hud_assets/hud/azerus-skillbar.yml"),
                              os.path.join(SNAP, "hud_assets/hud/partyhud-mmo.yml")])
        hb = Image.open(os.path.join(ROOT, "source-pack/assets/minecraft/textures/gui/sprites/hud/hotbar.png")).convert("RGBA")
        sel = Image.open(os.path.join(ROOT, "source-pack/assets/minecraft/textures/gui/sprites/hud/hotbar_selection.png")).convert("RGBA")
        draw_vanilla(canvas, fonts, hb, sel, selected=held)
        for lf in ("layouts/azerus-layout.yml", "layouts/azerus-skillbar-layout.yml"):
            lay = yaml.safe_load(open(os.path.join(ROOT, lf), encoding="utf-8"))
            for name, l in lay.items():
                draw_layout(canvas, l, assets, fonts, st, log)
    else:
        assets = load_assets([os.path.join(SNAP, "hud_assets/hud/mmohud.yml"),
                              os.path.join(SNAP, "hud_assets/hud/skill_casting_bar-hud.yml")])
        draw_vanilla(canvas, fonts, zip_sprite("hotbar.png"), zip_sprite("hotbar_selection.png"), selected=held)
        for lf in ("layouts/mmohud-layout.yml", "layouts/skill_casting_bar-layout.yml"):
            lay = yaml.safe_load(open(os.path.join(SNAP, lf), encoding="utf-8"))
            for name, l in lay.items():
                draw_layout(canvas, l, assets, fonts, st, log)
    return canvas, log


def main():
    out = os.path.join(ROOT, "preview")
    os.makedirs(out, exist_ok=True)
    shots = {}
    for kind, casting, held, fname in (("new", False, 2, "new_hud.png"), ("new", True, 2, "new_hud_casting.png"),
                                       ("old", False, 2, "old_hud.png"), ("old", True, 2, "old_hud_casting.png")):
        img, log = render(kind, casting, held)
        big = img.resize((W * SCALE, H * SCALE), Image.NEAREST)
        big.convert("RGB").save(os.path.join(out, fname))
        shots[fname] = big
        if "-v" in sys.argv:
            for r in log:
                print(fname, *r)
    # side by side comparison (old | new), downscaled to fit
    cmp_ = Image.new("RGB", (W * 2 * 2 + 8, H * 2), (16, 22, 29))
    cmp_.paste(shots["old_hud_casting.png"].resize((W * 2, H * 2), Image.NEAREST), (0, 0))
    cmp_.paste(shots["new_hud_casting.png"].resize((W * 2, H * 2), Image.NEAREST), (W * 2 + 8, 0))
    cmp_.save(os.path.join(out, "compare_old_vs_new.png"))
    # zoomed crops of the new HUD (x6) for pixel inspection
    img, _ = render("new", True, 2)
    for name, box in (("zoom_topleft.png", (0, 0, 200, 48)), ("zoom_bottom.png", (140, 168, 340, 270)),
                      ("zoom_top.png", (150, 0, 330, 22))):
        c = img.crop(box)
        c.resize((c.width * 6, c.height * 6), Image.NEAREST).convert("RGB").save(os.path.join(out, name))
    print("previews ->", os.path.relpath(out, REPO))


if __name__ == "__main__":
    main()
