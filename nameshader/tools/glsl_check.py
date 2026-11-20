#!/usr/bin/env python3
"""nameshader - compile and link the nametag shaders with glslangValidator, GLSL 330 and 410.

    python3 -I nameshader/tools/glsl_check.py [client-1.21.11.jar]

#moj_import is expanded like the client does it (each include once): nameshader/pack/assets first, then the packs of
the other plugins in this repo, then the hook stubs (pack/stubs), then the vanilla includes from the client jar.
Every exclusive program is compiled with the vanilla pair as a control, with our pair, and
rendertype_text_see_through also with the textshader vertex shader (textshader/pack), because that .vsh belongs to
textshader and is paired with our .fsh when both are installed. Exclusive core files are compared with vanilla:
only hook lines (ns_, nameshader:) may differ, and no other pack may ship them. gui.*, position_tex_color.* and
rendertype_text.* are the shared canonical files of tools/shaderhooks (checked by shaderhooks.py, which runs here
too: every combination of plugins and every install order compiles).
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
OTHERS = {"textshader": os.path.join(REPO, "textshader", "pack", "assets"),
          "tabshader": os.path.join(REPO, "tabshader", "pack", "assets")}
STUBS = os.path.join(ROOT, "pack", "stubs")
JAR = os.path.join(REPO, "core-npc", "tools", ".cache", "client-1.21.11.jar")
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "shaderhooks"))
import shaderhooks
CORES = ("rendertype_text_see_through", "rendertype_text_background", "rendertype_text_background_see_through")
HOOK = ("ns_", "nameshader:")


def load_vanilla(jar):
    with zipfile.ZipFile(jar) as z:
        return {n: z.read(n).decode() for n in z.namelist() if n.startswith("assets/minecraft/shaders/")}


def expand(src, vanilla, seen, roots):
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


def source(vanilla, base, name, ext):
    if base:
        path = os.path.join(base, "minecraft", "shaders", "core", f"{name}.{ext}")
        if os.path.exists(path):
            return open(path, encoding="utf-8").read()
    return vanilla[f"assets/minecraft/shaders/core/{name}.{ext}"]


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
    names = sorted(n for n in os.listdir(core_dir) if "minecraft/shaders/core/" + n not in shaderhooks.canonical_paths())
    want = sorted(["rendertype_text_see_through.fsh"] + [f"{c}.{e}" for c in CORES[1:] for e in ("vsh", "fsh")])
    if names != want:
        print("ERR core files: " + ", ".join(names))
        ok = False
    for fn in names:
        ours = open(os.path.join(core_dir, fn), encoding="utf-8").read().split("\n")
        theirs = vanilla[f"assets/minecraft/shaders/core/{fn}"].split("\n")
        changed = [l for l in difflib.unified_diff(theirs, ours, lineterm="", n=0)
                   if l[:1] in "+-" and not l.startswith(("+++", "---"))]
        bad = [l for l in changed if l.strip("+- ") and (l.startswith("-") or not any(h in l for h in HOOK))]
        print(f"DIFF {fn}: {len(changed)} changed lines" + (" (only hooks)" if not bad else " UNEXPECTED"))
        for l in changed:
            print("    " + l)
        ok &= not bad
        for other, base in OTHERS.items():
            if os.path.exists(os.path.join(base, "minecraft", "shaders", "core", fn)):
                print(f"ERR {fn} is also shipped by {other}")
                ok = False
    return ok


def main(argv):
    vanilla = load_vanilla(argv[0] if argv else JAR)
    ok = diff_vanilla(vanilla)
    ok &= shaderhooks.check(False)
    roots = [ASSETS, OTHERS["textshader"], OTHERS["tabshader"], STUBS]
    with tempfile.TemporaryDirectory() as tmp:
        for core in CORES:
            variants = [("vanilla", None, None), ("nameshader", ASSETS, ASSETS)]
            if core == "rendertype_text_see_through" and os.path.isdir(OTHERS["textshader"]):
                variants.append(("textshader_vsh", OTHERS["textshader"], ASSETS))
            for label, vbase, fbase in variants:
                vs = expand(source(vanilla, vbase, core, "vsh"), vanilla, set(), roots)
                fs = expand(source(vanilla, fbase, core, "fsh"), vanilla, set(), roots)
                for ver in ("330", "410"):
                    ok &= check(f"{label}_{core}_glsl{ver}", vs.replace("#version 330", f"#version {ver}", 1),
                                fs.replace("#version 330", f"#version {ver}", 1), tmp)
    ok &= shaderhooks.vanilla_diff(argv[:1])
    ok &= shaderhooks.combos(argv[:1])
    print("RESULT:", "compiles and links" if ok else "ERRORS")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
