#!/usr/bin/env python3
"""tabshader - merge our text shaders into the MythicHUD overlay of an external resource pack.

    python3 -I tabshader/tools/mythichud_patch.py IN.zip OUT.zip   # write a patched copy (IN is not changed)
    python3 -I tabshader/tools/mythichud_patch.py --check IN.zip   # only report what would change

MythicHUD packs (hl-resourcepack.zip) ship minecraft/shaders/core/rendertype_text.vsh/.fsh inside overlay
directories (mythichud-1_21_6 for formats 63-83, 1.21.11 is format 75). Overlays win over base assets, so
without this patch the client uses MythicHUD's text shader and tabshader/textshader text hooks are lost (moved
tab text, the board, textshader effects). The merged files in tabshader/pack/overlays/<dir>/ call both our hooks
and MythicHUD's applyCustomHud(); this tool puts them into the zip in place of MythicHUD's copies.
"""
import os
import re
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OVERLAYS = os.path.join(ROOT, "pack", "overlays")
FILES = ("rendertype_text.vsh", "rendertype_text.fsh")
MH_VERSION = "5"


def merged_files():
    out = {}
    if not os.path.isdir(OVERLAYS):
        return out
    for d in sorted(os.listdir(OVERLAYS)):
        core = os.path.join(OVERLAYS, d, "assets", "minecraft", "shaders", "core")
        for f in FILES:
            p = os.path.join(core, f)
            if os.path.exists(p):
                with open(p, "rb") as fh:
                    out[d + "/assets/minecraft/shaders/core/" + f] = fh.read()
    return out


def mh_version(data):
    m = re.search(r"#define MH_VERSION (\d+)", data.decode("utf-8", "replace"))
    return m.group(1) if m else None


def plan(names, read):
    replace = {}
    problems = []
    for path, data in merged_files().items():
        overlay = path.split("/", 1)[0]
        if path not in names:
            continue
        utils = overlay + "/assets/minecraft/shaders/include/mythichud_utils.glsl"
        if utils not in names:
            problems.append("missing " + utils)
            continue
        v = mh_version(read(utils))
        if v != MH_VERSION:
            problems.append(utils + ": MH_VERSION " + str(v) + ", merged files expect " + MH_VERSION)
            continue
        replace[path] = data
    return replace, problems


def patch_entries(entries):
    replace, problems = plan(set(entries), lambda n: entries[n])
    out = dict(entries)
    out.update(replace)
    return out, sorted(replace), problems


def main(argv):
    check = "--check" in argv
    args = [a for a in argv if a != "--check"]
    if len(args) != (1 if check else 2):
        print(__doc__)
        return 2
    with zipfile.ZipFile(args[0]) as z:
        entries = {i.filename: z.read(i.filename) for i in z.infolist() if not i.is_dir()}
    out, changed, problems = patch_entries(entries)
    for p in problems:
        print("PROBLEM " + p)
    for c in changed:
        print(("SAME    " if entries[c] == out[c] else "REPLACE ") + c)
    if not changed:
        print("nothing to replace: no MythicHUD text shader overlay found")
    if problems:
        return 1
    if check:
        return 0
    if os.path.abspath(args[0]) == os.path.abspath(args[1]):
        print("OUT must differ from IN")
        return 2
    tmp = args[1] + ".part"
    with zipfile.ZipFile(args[0]) as src, zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as dst:
        for info in src.infolist():
            if info.is_dir():
                dst.writestr(info, b"")
                continue
            dst.writestr(info, out[info.filename], zipfile.ZIP_DEFLATED)
    os.replace(tmp, args[1])
    print("written " + args[1])
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
