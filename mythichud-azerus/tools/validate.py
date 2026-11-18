#!/usr/bin/env python3
"""Checks the Azerus MythicHUD files before upload.

  python3 -I mythichud-azerus/tools/validate.py

* every .yml in mythichud-azerus parses (PyYAML);
* config.yml default layouts exist (here or in the production snapshot);
* every layout element points to an existing asset;
* every texture path (texture.path and `... texture <path>` conditions) exists in
  mythichud-azerus/source-pack or in the production snapshot source-pack;
* every function listener and every font is defined (here or in the snapshot);
* condition lines have the `<type>{...} true|false <action>` shape;
* textures: RGBA PNG, glyph <= 256x256, no semi-transparent pixels except the two skill overlays.
"""
import os
import re
import sys

import yaml
from PIL import Image

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
SNAP = os.path.normpath(os.path.join(ROOT, "..", "server/plugins/MythicHUD"))
TEX = [os.path.join(ROOT, "source-pack/assets/mythichud/textures"),
       os.path.join(SNAP, "source-pack/assets/mythichud/textures")]
BUILTIN_LISTENERS = {"health", "food", "air", "armor", "experience", "absorption"}
COND = re.compile(r"^(placeholder|effect|gamemode|progress|mythic|frozen)\{.*\}\s+(true|false)\s+(hide|texture|color|shadow)\b")
TRANSLUCENT_OK = {"skill_cooldown.png", "skill_oom.png"}

errors, notes = [], []


def load_dir(base, sub):
    out = {}
    d = os.path.join(base, sub)
    if not os.path.isdir(d):
        return out
    for f in sorted(os.listdir(d)):
        if f.endswith(".yml"):
            data = yaml.safe_load(open(os.path.join(d, f), encoding="utf-8")) or {}
            out.update(data)
    return out


def tex_exists(p):
    return any(os.path.exists(os.path.join(r, p)) for r in TEX)


# 1. parse everything
for root, _, files in os.walk(ROOT):
    for f in files:
        if f.endswith(".yml"):
            p = os.path.join(root, f)
            try:
                yaml.safe_load(open(p, encoding="utf-8"))
            except yaml.YAMLError as e:
                errors.append("YAML %s: %s" % (os.path.relpath(p, ROOT), e))

cfg = yaml.safe_load(open(os.path.join(ROOT, "config.yml"), encoding="utf-8"))
layouts = {**load_dir(SNAP, "layouts"), **load_dir(ROOT, "layouts")}
assets = {**load_dir(SNAP, "hud_assets/hud"), **load_dir(ROOT, "hud_assets/hud")}
listeners = {**load_dir(SNAP, "listeners"), **load_dir(ROOT, "listeners")}
fonts = {**load_dir(SNAP, "hud_assets/font"), **load_dir(ROOT, "hud_assets/font")}

# 2. layouts / assets
for name in cfg["layout"]["default"]:
    if name not in layouts:
        errors.append("config.yml default layout %s not defined" % name)
new_assets = load_dir(ROOT, "hud_assets/hud")
for lname, lay in load_dir(ROOT, "layouts").items():
    for ename, el in lay["elements"].items():
        if el["asset"] not in assets:
            errors.append("layout %s.%s -> unknown asset %s" % (lname, ename, el["asset"]))

# 3. layers
refs = set()
for aname, a in new_assets.items():
    for c in a.get("conditions") or []:
        if not COND.match(c.split(" #")[0].strip()):
            errors.append("%s: bad condition %r" % (aname, c))
    for lname, l in a["layers"].items():
        where = "%s.%s" % (aname, lname)
        if "texture" in l:
            refs.add(l["texture"]["path"])
        if "function" in l:
            if l["function"]["listener"] not in listeners and l["function"]["listener"] not in BUILTIN_LISTENERS:
                errors.append("%s: unknown listener %s" % (where, l["function"]["listener"]))
            img = None
            for r in TEX:
                p = os.path.join(r, l["texture"]["path"])
                if os.path.exists(p):
                    img = Image.open(p)
            if img and img.width % l["function"]["increment"]:
                notes.append("%s: width %d not divisible by increment %d" % (where, img.width, l["function"]["increment"]))
        if l.get("align", "right") not in ("left", "center", "right"):
            errors.append("%s: bad align %r" % (where, l.get("align")))
        if "text" in l and l.get("font", "default") not in fonts:
            errors.append("%s: unknown font %s" % (where, l.get("font")))
        if "head" in l and not isinstance(l["head"], dict):
            errors.append("%s: head must be a section" % where)
        for c in l.get("conditions") or []:
            cc = c.split(" #")[0].strip()
            if not COND.match(cc):
                errors.append("%s: bad condition %r" % (where, c))
            m = re.search(r"\btrue texture (\S+)|\bfalse texture (\S+)", cc)
            if m:
                refs.add(m.group(1) or m.group(2))
missing = sorted(p for p in refs if not tex_exists(p))
for p in missing:
    errors.append("missing texture " + p)

# 4. texture sanity
azdir = os.path.join(ROOT, "source-pack/assets/mythichud/textures/assets/azerus")
for f in sorted(os.listdir(azdir)):
    im = Image.open(os.path.join(azdir, f))
    if im.mode != "RGBA":
        errors.append("%s not RGBA" % f)
    if im.width > 256 or im.height > 256:
        errors.append("%s larger than 256" % f)
    if f not in TRANSLUCENT_OK:
        hist = im.getchannel("A").histogram()
        if any(hist[1:255]):
            errors.append("%s has semi-transparent pixels" % f)
unused = sorted(set(os.listdir(azdir)) - {os.path.basename(p) for p in refs if p.startswith("assets/azerus/")})
if unused:
    notes.append("textures not referenced by YAML: " + ", ".join(unused))

print("assets: %d new, %d layers; textures referenced: %d (%d azerus, %d existing prod)" % (
    len(new_assets), sum(len(a["layers"]) for a in new_assets.values()), len(refs),
    len([r for r in refs if r.startswith("assets/azerus/")]), len([r for r in refs if not r.startswith("assets/azerus/")])))
for n in notes:
    print("note:", n)
if errors:
    print("ERRORS:")
    for e in errors:
        print("  -", e)
    sys.exit(1)
print("OK")
