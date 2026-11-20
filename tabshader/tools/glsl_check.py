#!/usr/bin/env python3
"""tabshader - compile and link the HUD shaders with glslangValidator, GLSL 330 and 410.

    python3 -I tabshader/tools/glsl_check.py [client-1.21.11.jar]

#moj_import is expanded like the client does it: files from tabshader/pack/assets first, then (for the "all" run)
the packs of the other plugins in this repo, then the hook stubs (pack/stubs), then the vanilla includes from the
client jar (each include once). The vanilla pairs are compiled too, as a control. gui.*, position_tex_color.* and
rendertype_text.* are the shared canonical files of tools/shaderhooks: they are checked (generated files up to date,
vanilla diff, every combination of plugins and install order compiles) by shaderhooks.py, which runs here too.
The merged MythicHUD overlay (pack/overlays/*/rendertype_text.*) is compiled with mythichud_utils.glsl taken from
the production hl-resourcepack.zip (MYTHICHUD_ZIP); its .fsh must equal ours and its .vsh may only add the
MythicHUD lines.
"""
import difflib
import os
import re
import subprocess
import sys
import tempfile
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO = os.path.dirname(ROOT)
ASSETS = os.path.join(ROOT, "pack", "assets")
STUBS = os.path.join(ROOT, "pack", "stubs")
SIBLINGS = [os.path.join(REPO, n, "pack", "assets") for n in ("textshader", "nameshader", "chatshader")
            if os.path.isdir(os.path.join(REPO, n, "pack", "assets"))]
ALONE = [ASSETS, STUBS]
ALL = [ASSETS] + SIBLINGS + [STUBS]
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "shaderhooks"))
import shaderhooks
JAR = os.path.join(REPO, "core-npc", "tools", ".cache", "client-1.21.11.jar")
CORES = ("gui", "position_tex_color", "rendertype_text")
HOOK = ("th_", "tabshader:")
MH_ZIP = os.environ.get("MYTHICHUD_ZIP", os.path.join(REPO, "server", "plugins", "Nexo", "pack", "external_packs", "hl-resourcepack.zip"))
OVERLAYS = os.path.join(ROOT, "pack", "overlays")
MH_LINES = {"#define MH_VERSION 5", "#moj_import <minecraft:mythichud_utils.glsl>", "applyCustomHud();",
            "vec4 thPosition = gl_Position;", "float thSpherical = sphericalVertexDistance;",
            "float thCylindrical = cylindricalVertexDistance;", "gl_Position = thPosition;",
            "sphericalVertexDistance = thSpherical;", "cylindricalVertexDistance = thCylindrical;", "}",
            "if (distance(gl_Position, ProjMat * ModelViewMat * vec4(Position, 1.0)) < 0.001 * max(1.0, abs(thPosition.w))) {"}


def mythichud(vanilla, tmp):
    ok = True
    if not os.path.isdir(OVERLAYS):
        return True
    if not os.path.exists(MH_ZIP):
        print("SKIP mythichud overlay: " + MH_ZIP + " not found")
        return True
    with zipfile.ZipFile(MH_ZIP) as z:
        names = set(z.namelist())
        for d in sorted(os.listdir(OVERLAYS)):
            core = os.path.join(OVERLAYS, d, "assets", "minecraft", "shaders", "core")
            utils = d + "/assets/minecraft/shaders/include/mythichud_utils.glsl"
            if utils not in names:
                print("ERR mythichud " + d + ": " + utils + " not in " + MH_ZIP)
                ok = False
                continue
            base = os.path.join(ASSETS, "minecraft", "shaders", "core")
            fs_same = open(os.path.join(core, "rendertype_text.fsh"), "rb").read() == open(os.path.join(base, "rendertype_text.fsh"), "rb").read()
            print(("SAME " if fs_same else "DIFF ") + d + " rendertype_text.fsh vs ours")
            ok &= fs_same
            ours = open(os.path.join(base, "rendertype_text.vsh"), encoding="utf-8").read().split("\n")
            merged = open(os.path.join(core, "rendertype_text.vsh"), encoding="utf-8").read().split("\n")
            changed = [l for l in difflib.unified_diff(ours, merged, lineterm="", n=0)
                       if l[:1] in "+-" and not l.startswith(("+++", "---"))]
            bad = [l for l in changed if "SHADERHOOKS_HASH" not in l
                   and (l.startswith("-") or (l[1:].strip() and l[1:].strip() not in MH_LINES))]
            print(f"DIFF {d} rendertype_text.vsh vs ours: {len(changed)} lines" + (" (only MythicHUD merge)" if not bad else " UNEXPECTED"))
            for l in bad:
                print("    " + l)
            ok &= not bad
            mh = {("minecraft", "mythichud_utils.glsl"): z.read(utils).decode("utf-8")}
            v = expand_extra(open(os.path.join(core, "rendertype_text.vsh"), encoding="utf-8").read(), vanilla, mh)
            f = expand_extra(open(os.path.join(core, "rendertype_text.fsh"), encoding="utf-8").read(), vanilla, mh)
            for ver in ("330", "410"):
                ok &= check(f"mythichud_{d}_rendertype_text_glsl{ver}", v.replace("#version 330", f"#version {ver}", 1),
                            f.replace("#version 330", f"#version {ver}", 1), tmp)
    return ok


def expand_extra(src, vanilla, extra):
    merged = dict(vanilla)
    for (ns, p), text in extra.items():
        merged[f"assets/{ns}/shaders/include/{p}"] = text
    return expand(src, merged, set(), ALL)


def load_vanilla(jar):
    with zipfile.ZipFile(jar) as z:
        return {n: z.read(n).decode() for n in z.namelist() if n.startswith("assets/minecraft/shaders/")}


def expand(src, vanilla, seen, roots=None):
    roots = ALL if roots is None else roots
    out = []
    for line in src.split("\n"):
        m = re.match(r"^#moj_import <([a-z_]+):([^>]+)>", line)
        if not m:
            out.append(line)
            continue
        ns, p = m.groups()
        if (ns, p) in seen:
            continue
        seen.add((ns, p))
        text = None
        for base in roots:
            own = os.path.join(base, ns, "shaders", "include", p)
            if os.path.exists(own):
                text = open(own, encoding="utf-8").read()
                break
        if text is None:
            text = vanilla[f"assets/{ns}/shaders/include/{p}"]
        text = "\n".join(l for l in text.split("\n") if not l.startswith("#version"))
        out.append(expand(text, vanilla, seen, roots))
    return "\n".join(out)


def core_source(vanilla, name, ext, own, assets=ASSETS):
    path = os.path.join(assets, "minecraft", "shaders", "core", f"{name}.{ext}")
    if own and os.path.exists(path):
        return open(path, encoding="utf-8").read()
    return vanilla[f"assets/minecraft/shaders/core/{name}.{ext}"]


def program(vanilla, name, own=True, assets=ASSETS, roots=None):
    vs = expand(core_source(vanilla, name, "vsh", own, assets), vanilla, set(), roots)
    fs = expand(core_source(vanilla, name, "fsh", own, assets), vanilla, set(), roots)
    return vs, fs


def check(name, vsrc, fsrc, tmp):
    v = os.path.join(tmp, name + ".vert")
    f = os.path.join(tmp, name + ".frag")
    open(v, "w").write(vsrc)
    open(f, "w").write(fsrc)
    r = subprocess.run(["glslangValidator", "-l", v, f], capture_output=True, text=True)
    text = (r.stdout + r.stderr).strip()
    print(("OK  " if r.returncode == 0 else "ERR ") + name)
    if r.returncode != 0 or "WARNING" in text:
        print("    " + text.replace("\n", "\n    ")[:6000])
    return r.returncode == 0


def diff_vanilla(vanilla):
    ok = True
    core_dir = os.path.join(ASSETS, "minecraft", "shaders", "core")
    for fn in sorted(os.listdir(core_dir)):
        if "minecraft/shaders/core/" + fn in shaderhooks.canonical_paths():
            continue
        ours = open(os.path.join(core_dir, fn), encoding="utf-8").read().split("\n")
        theirs = vanilla[f"assets/minecraft/shaders/core/{fn}"].split("\n")
        changed = [l for l in difflib.unified_diff(theirs, ours, lineterm="", n=0)
                   if l[:1] in "+-" and not l.startswith(("+++", "---"))]
        bad = [l for l in changed if l.strip("+- ") and not any(h in l for h in HOOK)]
        print(f"DIFF {fn}: {len(changed)} changed lines" + (" (only hooks)" if not bad else " UNEXPECTED"))
        for l in changed:
            print("    " + l)
        ok &= not bad
    return ok


def main(argv):
    vanilla = load_vanilla(argv[0] if argv else JAR)
    ok = diff_vanilla(vanilla)
    ok &= shaderhooks.check(False)
    with tempfile.TemporaryDirectory() as tmp:
        for core in CORES:
            for label, own, roots in (("vanilla", False, ALL), ("tabshader_alone", True, ALONE), ("tabshader_all", True, ALL)):
                vs, fs = program(vanilla, core, own, ASSETS, roots)
                for ver in ("330", "410"):
                    ok &= check(f"{label}_{core}_glsl{ver}", vs.replace("#version 330", f"#version {ver}", 1),
                                fs.replace("#version 330", f"#version {ver}", 1), tmp)
        ok &= mythichud(vanilla, tmp)
    ok &= shaderhooks.vanilla_diff(argv[:1])
    ok &= shaderhooks.combos(argv[:1])
    print("RESULT:", "compiles and links" if ok else "ERRORS")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
