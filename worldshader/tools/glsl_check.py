#!/usr/bin/env python3
"""worldshader - compile and link the worldshader core and post shaders with glslangValidator.

    python3 -I worldshader/tools/glsl_check.py [client-1.21.11.jar]

#moj_import is expanded like the client does it: our namespaces from worldshader/pack/assets first, then the vanilla
includes from the client jar (each include once). The vanilla rendertype_outline pair is compiled too, as a control.
Post passes are linked with the vanilla minecraft:core/screenquad vertex shader.
"""
import json
import os
import re
import subprocess
import sys
import tempfile
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO = os.path.dirname(ROOT)
ASSETS = os.path.join(ROOT, "pack", "assets")
JAR = os.path.join(REPO, "core-npc", "tools", ".cache", "client-1.21.11.jar")
CORE = "rendertype_outline"
CHAIN = os.path.join(ASSETS, "minecraft", "post_effect", "entity_outline.json")


def expand(src, vanilla, seen):
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
        own = os.path.join(ASSETS, ns, "shaders", "include", p)
        if os.path.exists(own):
            text = open(own, encoding="utf-8").read()
        else:
            text = vanilla[f"assets/{ns}/shaders/include/{p}"]
        text = "\n".join(l for l in text.split("\n") if not l.startswith("#version"))
        out.append(expand(text, vanilla, seen))
    return "\n".join(out)


def check(name, vsrc, fsrc, tmp):
    v = os.path.join(tmp, name + ".vert")
    f = os.path.join(tmp, name + ".frag")
    open(v, "w").write(vsrc)
    open(f, "w").write(fsrc)
    r = subprocess.run(["glslangValidator", "-l", v, f], capture_output=True, text=True)
    text = (r.stdout + r.stderr).strip()
    print(("OK  " if r.returncode == 0 else "ERR ") + name)
    if r.returncode != 0 or "WARNING" in text:
        print("    " + text.replace("\n", "\n    ")[:4000])
    return r.returncode == 0


def shader(vanilla, ident, ext):
    ns, path = ident.split(":", 1)
    own = os.path.join(ASSETS, ns, "shaders", path + "." + ext)
    if os.path.exists(own):
        return open(own, encoding="utf-8").read()
    return vanilla[f"assets/{ns}/shaders/{path}.{ext}"]


def versions(name, vs, fs, tmp):
    ok = True
    for ver in ("330", "410"):
        ok &= check(f"{name}_glsl{ver}", vs.replace("#version 330", f"#version {ver}", 1),
                    fs.replace("#version 330", f"#version {ver}", 1), tmp)
    return ok


def main(argv):
    jar = argv[0] if argv else JAR
    with zipfile.ZipFile(jar) as z:
        vanilla = {n: z.read(n).decode() for n in z.namelist() if n.startswith("assets/minecraft/shaders/")}
    ok = True
    with tempfile.TemporaryDirectory() as tmp:
        vs = expand(vanilla[f"assets/minecraft/shaders/core/{CORE}.vsh"], vanilla, set())
        fs = expand(vanilla[f"assets/minecraft/shaders/core/{CORE}.fsh"], vanilla, set())
        ok &= check(f"vanilla_{CORE}", vs, fs, tmp)
        vs = expand(shader(vanilla, "minecraft:core/" + CORE, "vsh"), vanilla, set())
        fs = expand(shader(vanilla, "minecraft:core/" + CORE, "fsh"), vanilla, set())
        ok &= versions(f"worldshader_{CORE}", vs, fs, tmp)
        chain = json.load(open(CHAIN, encoding="utf-8"))
        done = set()
        for p in chain["passes"]:
            key = (p["vertex_shader"], p["fragment_shader"])
            if key in done:
                continue
            done.add(key)
            vs = expand(shader(vanilla, p["vertex_shader"], "vsh"), vanilla, set())
            fs = expand(shader(vanilla, p["fragment_shader"], "fsh"), vanilla, set())
            for i in p.get("inputs", []):
                if f"uniform sampler2D {i['sampler_name']}Sampler;" not in fs:
                    print(f"ERR {p['fragment_shader']}: no sampler {i['sampler_name']}Sampler")
                    ok = False
            name = "post_" + p["fragment_shader"].replace(":", "_").replace("/", "_")
            ok &= versions(name, vs, fs, tmp)
    print("RESULT:", "compiles and links" if ok else "ERRORS")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
