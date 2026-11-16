#!/usr/bin/env python3
"""Компиляция и линковка core/entity.vsh + entity.fsh (armor3d + свечение + хук rezusmenu) во ВСЕХ 15 конвейерах
1.21.11, которые используют core/entity, в трёх режимах сбора квада:
  khr  — GL_KHR_shader_subgroup_shuffle (NVIDIA и др., где расширение есть при #version 330);
  arb  — GL_ARB_shader_ballot (Mesa: KHR-макросы там только с #version 430);
  none — без расширений (macOS GL 4.1, старые драйверы) → 2D-броня.

    python3 -I nexo-items/tools/armor3d_glsl_check.py [client-1.21.11.jar] [--mesa]

Нужен glslangValidator (apt install glslang-tools). --mesa — дополнительно настоящий драйвер через moderngl (EGL,
например Mesa llvmpipe или zink); режим, который драйвер выбрал сам по своим макросам, печатается.
#moj_import разворачивается как в игре: сначала include из нашего оверлея (nexo-items/entity_shaders/...), потом
ванильные include из jar. Определения вставляются сразу после #version, как это делает клиент.
"""
import os
import re
import subprocess
import sys
import tempfile
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO = os.path.dirname(ROOT)
PACK = os.path.join(ROOT, "entity_shaders", "azerus_entity_1_21_11", "assets", "minecraft", "shaders")
JAR = os.path.join(REPO, "core-npc", "tools", ".cache", "client-1.21.11.jar")

# все 15 конвейеров с core/entity (RenderPipelines 1.21.11, байткод): имя → определения
PIPELINES = {
    "entity_solid": {},
    "entity_solid_offset_forward": {},
    "entity_cutout": {"ALPHA_CUTOUT": "0.1"},
    "entity_cutout_no_cull": {"ALPHA_CUTOUT": "0.1", "PER_FACE_LIGHTING": None},
    "entity_cutout_no_cull_z_offset": {"ALPHA_CUTOUT": "0.1", "PER_FACE_LIGHTING": None},
    "entity_translucent": {"ALPHA_CUTOUT": "0.1", "PER_FACE_LIGHTING": None},
    "entity_translucent_emissive": {"ALPHA_CUTOUT": "0.1", "PER_FACE_LIGHTING": None, "EMISSIVE": None},
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


MODES = {"khr": {}, "arb": {"AZ3D_TEST_ARB": None}, "none": {"AZ3D_TEST_NO_SUBGROUP": None}}
ARMOR = ("ALPHA_CUTOUT", "NO_OVERLAY", "PER_FACE_LIGHTING")


def gather_used(src):
    """Какой сбор квада остался после препроцессора (по исходнику с определениями): khr / arb / нет."""
    r = subprocess.run(["glslangValidator", "-E", "--stdin", "-S", "vert"], input=src, capture_output=True, text=True)
    out = r.stdout
    if "subgroupShuffle" in out:
        return "khr"
    if "readInvocationARB" in out:
        return "arb"
    return "-"


def mesa_check(vanilla):
    """Настоящий драйвер (moderngl + EGL). Режим выбирает сам драйвер — по макросам, как в игре."""
    try:
        import moderngl
    except ImportError:
        print("--mesa: нет moderngl — пропуск")
        return True
    ctx = moderngl.create_context(standalone=True, require=330, backend="egl")
    print(f"--mesa: {ctx.info['GL_RENDERER']} / {ctx.info['GL_VERSION']}")
    # какой сбор выбирает сам драйвер: заголовок entity.vsh (до первого #moj_import) + #error с режимом
    head = open(os.path.join(PACK, "core", "entity.vsh"), encoding="utf-8").read().split("#moj_import")[0]
    probe = head + ("#if !defined(AZ3D_SUBGROUP)\n#error AZ3D_MODE_none\n#elif AZ3D_SUBGROUP == 1\n#error AZ3D_MODE_khr\n"
                    "#else\n#error AZ3D_MODE_arb\n#endif\nvoid main() { gl_Position = vec4(0.0); }\n")
    try:
        ctx.program(vertex_shader=probe, fragment_shader="#version 330\nout vec4 c;\nvoid main() { c = vec4(1.0); }\n")
        chosen = "?"
    except Exception as e:  # noqa: BLE001
        chosen = str(e).split("AZ3D_MODE_")[1][:4].strip() if "AZ3D_MODE_" in str(e) else "?"
    print(f"--mesa: драйвер сам выбирает сбор: {chosen}")
    ok = True
    for name, defs in PIPELINES.items():
        for mode in ("driver", "arb", "none"):
            d = dict(defs)
            d.update({} if mode == "driver" else MODES[mode])
            vs = prepare(os.path.join(PACK, "core", "entity.vsh"), d, vanilla)
            fs = prepare(os.path.join(PACK, "core", "entity.fsh"), d, vanilla)
            try:
                p = ctx.program(vertex_shader=vs, fragment_shader=fs)
                st = "OK "
                p.release()
            except Exception as e:  # noqa: BLE001 — текст ошибки драйвера печатаем как есть
                st = "ERR"
                ok = False
                print("    " + str(e)[:2000])
            used = (chosen if mode == "driver" else gather_used(vs)) if all(k in defs for k in ARMOR) else "-"
            print(f"{st} mesa {name:32s} {mode:6s} (сбор: {used})")
    return ok


def main(argv):
    mesa = "--mesa" in argv
    argv = [a for a in argv if a != "--mesa"]
    jar = argv[0] if argv else JAR
    with zipfile.ZipFile(jar) as z:
        vanilla = {n: z.read(n).decode() for n in z.namelist() if n.startswith("assets/minecraft/shaders/include/")}
    ok = True
    with tempfile.TemporaryDirectory() as tmp:
        for name, defs in PIPELINES.items():
            for mode, extra in MODES.items():
                d = dict(defs)
                d.update(extra)
                v = os.path.join(tmp, f"{name}_{mode}.vert")
                f = os.path.join(tmp, f"{name}_{mode}.frag")
                vsrc = prepare(os.path.join(PACK, "core", "entity.vsh"), d, vanilla)
                fsrc = prepare(os.path.join(PACK, "core", "entity.fsh"), d, vanilla)
                if mode == "arb":
                    # glslang объявляет встроенные ARB_shader_ballot только с #version 450: синтаксис проверяем там,
                    # а при #version 330 — настоящим драйвером (--mesa)
                    vsrc = vsrc.replace("#version 330", "#version 450", 1)
                    fsrc = fsrc.replace("#version 330", "#version 450", 1)
                open(v, "w").write(vsrc)
                open(f, "w").write(fsrc)
                r = subprocess.run(["glslangValidator", "-l", v, f], capture_output=True, text=True)
                text = (r.stdout + r.stderr).strip()
                used = gather_used(vsrc) if all(k in defs for k in ARMOR) else "-"
                if all(k in defs for k in ARMOR) and used != ("-" if mode == "none" else mode):
                    ok = False
                    print(f"ERR {name}: режим {mode}, а препроцессор оставил сбор «{used}»")
                status = "OK " if r.returncode == 0 else "ERR"
                ok &= r.returncode == 0
                print(f"{status} {name:32s} {mode:5s}{f' (3D-путь: {used})' if used != '-' else ''}"
                      f"{' [glslang @450]' if mode == 'arb' else ''}")
                if r.returncode != 0 or "WARNING" in text:
                    print("    " + text.replace("\n", "\n    ")[:3000])
    if mesa:
        ok &= mesa_check(vanilla)
    print("ИТОГ:", "все сочетания компилируются и линкуются" if ok else "ЕСТЬ ОШИБКИ")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
