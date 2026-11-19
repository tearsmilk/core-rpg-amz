#!/usr/bin/env python3
"""coreshaders - compile and link rendertype_item_entity_translucent_cull.{vsh,fsh} with glslangValidator.

    python3 -I coreshaders/tools/glsl_check.py [client-1.21.11.jar]

#moj_import is expanded like the client does it: our namespaces from coreshaders/pack/assets first, then the vanilla
includes from the client jar (each include once). The vanilla pair is compiled too, as a control.
"""
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
CORE = "rendertype_item_entity_translucent_cull"


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


def main(argv):
    jar = argv[0] if argv else JAR
    with zipfile.ZipFile(jar) as z:
        vanilla = {n: z.read(n).decode() for n in z.namelist() if n.startswith("assets/minecraft/shaders/")}
    ok = True
    with tempfile.TemporaryDirectory() as tmp:
        for label, getter in (("vanilla", lambda ext: vanilla[f"assets/minecraft/shaders/core/{CORE}.{ext}"]),
                              ("coreshaders", lambda ext: open(os.path.join(ASSETS, "minecraft", "shaders", "core",
                                                                            f"{CORE}.{ext}"), encoding="utf-8").read())):
            vs = expand(getter("vsh"), vanilla, set())
            fs = expand(getter("fsh"), vanilla, set())
            ok &= check(f"{label}_{CORE}", vs, fs, tmp)
            for ver in ("330", "410"):
                if ver != "330":
                    ok &= check(f"{label}_{CORE}_glsl{ver}", vs.replace("#version 330", f"#version {ver}", 1),
                                fs.replace("#version 330", f"#version {ver}", 1), tmp)
    print("RESULT:", "compiles and links" if ok else "ERRORS")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
