#!/usr/bin/env python3
"""tabshader - compile and link the HUD shaders with glslangValidator, GLSL 330 and 410.

    python3 -I tabshader/tools/glsl_check.py [client-1.21.11.jar]

#moj_import is expanded like the client does it: files from tabshader/pack/assets first, then the vanilla includes
from the client jar (each include once). The vanilla pairs are compiled too, as a control. The core files are
compared with vanilla: only hook lines (ts_, th_, textshader:, tabshader:) may differ, and in gui.* and
position_tex_color.* the inline uniform blocks are replaced by the vanilla includes. The merged text shaders and
the textshader includes must be byte-identical to the copies in textshader/pack.
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
TEXTSHADER = os.path.join(REPO, "textshader", "pack", "assets")
JAR = os.path.join(REPO, "core-npc", "tools", ".cache", "client-1.21.11.jar")
CORES = ("gui", "position_tex_color", "rendertype_text")
HEADER_SWAP = ("gui.vsh", "gui.fsh", "position_tex_color.vsh", "position_tex_color.fsh")
SHARED = ("minecraft/shaders/core/rendertype_text.vsh", "minecraft/shaders/core/rendertype_text.fsh")
HOOK = ("ts_", "th_", "textshader:", "tabshader:")


def load_vanilla(jar):
    with zipfile.ZipFile(jar) as z:
        return {n: z.read(n).decode() for n in z.namelist() if n.startswith("assets/minecraft/shaders/")}


def expand(src, vanilla, seen, assets=ASSETS):
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
        own = os.path.join(assets, ns, "shaders", "include", p)
        if os.path.exists(own):
            text = open(own, encoding="utf-8").read()
        else:
            text = vanilla[f"assets/{ns}/shaders/include/{p}"]
        text = "\n".join(l for l in text.split("\n") if not l.startswith("#version"))
        out.append(expand(text, vanilla, seen, assets))
    return "\n".join(out)


def core_source(vanilla, name, ext, own, assets=ASSETS):
    path = os.path.join(assets, "minecraft", "shaders", "core", f"{name}.{ext}")
    if own and os.path.exists(path):
        return open(path, encoding="utf-8").read()
    return vanilla[f"assets/minecraft/shaders/core/{name}.{ext}"]


def program(vanilla, name, own=True, assets=ASSETS):
    vs = expand(core_source(vanilla, name, "vsh", own, assets), vanilla, set(), assets)
    fs = expand(core_source(vanilla, name, "fsh", own, assets), vanilla, set(), assets)
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


def header_line(line):
    s = line.strip()
    return (s.startswith("//") or s.startswith("layout(std140)") or s in ("};", "")
            or re.match(r"^(mat4|vec4|vec3) [A-Za-z]+;$", s) is not None
            or s.startswith("#moj_import <minecraft:"))


def diff_vanilla(vanilla):
    ok = True
    core_dir = os.path.join(ASSETS, "minecraft", "shaders", "core")
    for fn in sorted(os.listdir(core_dir)):
        ours = open(os.path.join(core_dir, fn), encoding="utf-8").read().split("\n")
        theirs = vanilla[f"assets/minecraft/shaders/core/{fn}"].split("\n")
        changed = [l for l in difflib.unified_diff(theirs, ours, lineterm="", n=0)
                   if l[:1] in "+-" and not l.startswith(("+++", "---"))]
        wrapped = {re.sub(r"th_[a-z_]+\((\w+)\)", r"\1", l[1:]) for l in changed if l.startswith("+") and "th_" in l}
        bad = [l for l in changed if l.strip("+- ") and not any(h in l for h in HOOK)
               and not (fn in HEADER_SWAP and header_line(l[1:]))
               and not (l.startswith("-") and l[1:] in wrapped)]
        print(f"DIFF {fn}: {len(changed)} changed lines" + (" (only hooks)" if not bad else " UNEXPECTED"))
        for l in changed:
            print("    " + l)
        ok &= not bad
    return ok


def shared_copies():
    ok = True
    pairs = [(os.path.join(ASSETS, p), os.path.join(TEXTSHADER, p)) for p in SHARED]
    for d in (os.path.join("textshader", "shaders", "include"), os.path.join("tabshader", "shaders", "include")):
        names = set()
        for base in (ASSETS, TEXTSHADER):
            full = os.path.join(base, d)
            if os.path.isdir(full):
                names |= {n for n in os.listdir(full) if n.endswith(".glsl")}
        for n in sorted(names):
            if d.startswith("tabshader") and not n.startswith(("th_text", "th_common", "th_layout")):
                continue
            pairs.append((os.path.join(ASSETS, d, n), os.path.join(TEXTSHADER, d, n)))
    if not os.path.isdir(TEXTSHADER):
        print("SKIP shared copies: textshader/pack not found")
        return True
    for a, b in pairs:
        same = os.path.exists(a) and os.path.exists(b) and open(a, "rb").read() == open(b, "rb").read()
        print(("SAME " if same else "DIFF ") + os.path.relpath(a, ASSETS))
        ok &= same
    return ok


def main(argv):
    vanilla = load_vanilla(argv[0] if argv else JAR)
    ok = diff_vanilla(vanilla)
    ok &= shared_copies()
    with tempfile.TemporaryDirectory() as tmp:
        for core in CORES:
            for label, own in (("vanilla", False), ("tabshader", True)):
                vs, fs = program(vanilla, core, own)
                for ver in ("330", "410"):
                    ok &= check(f"{label}_{core}_glsl{ver}", vs.replace("#version 330", f"#version {ver}", 1),
                                fs.replace("#version 330", f"#version {ver}", 1), tmp)
    print("RESULT:", "compiles and links" if ok else "ERRORS")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
