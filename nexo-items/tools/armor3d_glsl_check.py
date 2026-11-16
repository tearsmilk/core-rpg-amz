#!/usr/bin/env python3
"""Компиляция и линковка core/entity.vsh + entity.fsh (armor3d + свечение + хук rezusmenu) во ВСЕХ конвейерах
1.21.11, которые используют core/entity, с subgroup и без (как на macOS GL 4.1 / старых драйверах).

    python3 -I nexo-items/tools/armor3d_glsl_check.py [client-1.21.11.jar]

Нужен glslangValidator (apt install glslang-tools). #moj_import разворачивается как в игре: сначала
pack/assets/minecraft/shaders/include (наш пак), потом ванильные include из jar. Определения вставляются сразу после
#version, как это делает клиент (ShaderManager/GlslPreprocessor).
"""
import os
import re
import subprocess
import sys
import tempfile
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO = os.path.dirname(ROOT)
PACK = os.path.join(ROOT, "pack", "assets", "minecraft", "shaders")
JAR = os.path.join(REPO, "core-npc", "tools", ".cache", "client-1.21.11.jar")

# конвейеры с core/entity (RenderPipelines 1.21.11): имя → определения
PIPELINES = {
    "entity_solid": {},
    "entity_cutout": {"ALPHA_CUTOUT": "0.1"},
    "entity_cutout_no_cull": {"ALPHA_CUTOUT": "0.1", "PER_FACE_LIGHTING": None},
    "entity_translucent": {"ALPHA_CUTOUT": "0.1", "PER_FACE_LIGHTING": None},
    "entity_smooth_cutout": {"ALPHA_CUTOUT": "0.1"},
    "entity_no_outline": {"NO_OVERLAY": None, "PER_FACE_LIGHTING": None},
    "armor_cutout_no_cull": {"ALPHA_CUTOUT": "0.1", "NO_OVERLAY": None, "PER_FACE_LIGHTING": None},
    "armor_decal_cutout_no_cull": {"ALPHA_CUTOUT": "0.1", "NO_OVERLAY": None, "PER_FACE_LIGHTING": None},
    "armor_translucent": {"ALPHA_CUTOUT": "0.1", "NO_OVERLAY": None, "PER_FACE_LIGHTING": None},
    "breeze_wind": {"ALPHA_CUTOUT": "0.1", "APPLY_TEXTURE_MATRIX": None, "NO_OVERLAY": None, "NO_CARDINAL_LIGHTING": None},
    "energy_swirl": {"ALPHA_CUTOUT": "0.1", "EMISSIVE": None, "NO_OVERLAY": None, "NO_CARDINAL_LIGHTING": None,
                     "APPLY_TEXTURE_MATRIX": None},
    "eyes": {"EMISSIVE": None, "NO_OVERLAY": None, "NO_CARDINAL_LIGHTING": None},
}


def expand(src, vanilla, seen):
    out = []
    for line in src.split("\n"):
        m = re.match(r"^#moj_import <([a-z_]+):([^>]+)>", line)
        if m:
            ns, p = m.groups()
            if (ns, p) in seen:
                continue
            seen.add((ns, p))
            own = os.path.join(PACK, "include", p) if ns == "minecraft" else None
            if own and os.path.exists(own):
                text = open(own, encoding="utf-8").read()
            else:
                text = vanilla[f"assets/{ns}/shaders/include/{p}"]
            text = "\n".join(l for l in text.split("\n") if not l.startswith("#version"))
            out.append(expand(text, vanilla, seen))
        else:
            out.append(line)
    return "\n".join(out)


def prepare(path, defines, vanilla):
    src = expand(open(path, encoding="utf-8").read(), vanilla, set())
    lines = src.split("\n")
    assert lines[0].startswith("#version"), path
    inj = [f"#define {k} {v}" if v is not None else f"#define {k}" for k, v in defines.items()]
    return "\n".join([lines[0]] + inj + lines[1:])


def main(argv):
    jar = argv[0] if argv else JAR
    with zipfile.ZipFile(jar) as z:
        vanilla = {n: z.read(n).decode() for n in z.namelist() if n.startswith("assets/minecraft/shaders/include/")}
    ok = True
    with tempfile.TemporaryDirectory() as tmp:
        for name, defs in PIPELINES.items():
            for mode in ("subgroup", "no-subgroup"):
                d = dict(defs)
                if mode == "no-subgroup":
                    d["AZ3D_TEST_NO_SUBGROUP"] = None
                v = os.path.join(tmp, f"{name}_{mode}.vert")
                f = os.path.join(tmp, f"{name}_{mode}.frag")
                open(v, "w").write(prepare(os.path.join(PACK, "core", "entity.vsh"), d, vanilla))
                open(f, "w").write(prepare(os.path.join(PACK, "core", "entity.fsh"), d, vanilla))
                r = subprocess.run(["glslangValidator", "-l", v, f], capture_output=True, text=True)
                text = (r.stdout + r.stderr).strip()
                used = "subgroupQuadBroadcast" in open(v).read() and mode == "subgroup" and "ALPHA_CUTOUT" in defs \
                    and "NO_OVERLAY" in defs and "PER_FACE_LIGHTING" in defs
                status = "OK " if r.returncode == 0 else "ERR"
                ok &= r.returncode == 0
                print(f"{status} {name:28s} {mode:12s}{' (3D-путь включён)' if used else ''}")
                if r.returncode != 0 or "WARNING" in text:
                    print("    " + text.replace("\n", "\n    ")[:3000])
    print("ИТОГ:", "все сочетания компилируются и линкуются" if ok else "ЕСТЬ ОШИБКИ")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
