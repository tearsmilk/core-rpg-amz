#!/usr/bin/env python3
"""textshader - compile and link the text shaders with glslangValidator, GLSL 330 and 410.

    python3 -I textshader/tools/glsl_check.py [client-1.21.11.jar]

#moj_import is expanded like the client does it: files from textshader/pack/assets first, then (for the "all" run)
the packs of the other plugins in this repo, then the hook stubs (pack/stubs), then the vanilla includes from the
client jar (each include once). The vanilla pairs are compiled too, as a control. Exclusive core files are compared
with vanilla: only hook lines may differ. gui.*, position_tex_color.* and rendertype_text.* are the shared canonical
files of tools/shaderhooks (checked by shaderhooks.py, which runs here too: every combination of plugins and every
install order compiles).
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
SIBLINGS = [os.path.join(REPO, n, "pack", "assets") for n in ("tabshader", "nameshader", "chatshader", "aurahud")
            if os.path.isdir(os.path.join(REPO, n, "pack", "assets"))]
ALONE = [ASSETS, STUBS]
ALL = [ASSETS] + SIBLINGS + [STUBS]
JAR = os.path.join(REPO, "core-npc", "tools", ".cache", "client-1.21.11.jar")
HOOK = ("ts_", "textshader:")
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "shaderhooks"))
import shaderhooks
CORES = ("rendertype_text", "rendertype_text_see_through", "rendertype_text_intensity",
         "rendertype_text_intensity_see_through")


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


def core_source(vanilla, name, ext, own):
    path = os.path.join(ASSETS, "minecraft", "shaders", "core", f"{name}.{ext}")
    if own and os.path.exists(path):
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
    for fn in sorted(os.listdir(core_dir)):
        if "minecraft/shaders/core/" + fn in shaderhooks.canonical_paths():
            continue
        ours = open(os.path.join(core_dir, fn), encoding="utf-8").read().split("\n")
        theirs = vanilla[f"assets/minecraft/shaders/core/{fn}"].split("\n")
        changed = [l for l in difflib.unified_diff(theirs, ours, lineterm="", n=0)
                   if l[:1] in "+-" and not l.startswith(("+++", "---"))]
        bad = [l for l in changed if l.strip("+- ") and not any(h in l for h in HOOK)]
        print(f"DIFF {fn}: {len(changed)} changed lines" + (" (only hooks)" if not bad else ""))
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
            for label, own, roots in (("vanilla", False, ALL), ("textshader_alone", True, ALONE), ("textshader_all", True, ALL)):
                vs = expand(core_source(vanilla, core, "vsh", own), vanilla, set(), roots)
                fs = expand(core_source(vanilla, core, "fsh", own), vanilla, set(), roots)
                for ver in ("330", "410"):
                    ok &= check(f"{label}_{core}_glsl{ver}", vs.replace("#version 330", f"#version {ver}", 1),
                                fs.replace("#version 330", f"#version {ver}", 1), tmp)
    ok &= shaderhooks.vanilla_diff(argv[:1])
    ok &= shaderhooks.combos(argv[:1])
    print("RESULT:", "compiles and links" if ok else "ERRORS")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
