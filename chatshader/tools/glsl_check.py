#!/usr/bin/env python3
"""chatshader - check the chat shaders with glslangValidator, GLSL 330 and 410.

    python3 -I chatshader/tools/glsl_check.py [client-1.21.11.jar]

chatshader owns no core file of its own: its hooks (cs_hook_gui_*, cs_hook_text_*) live in the shared canonical
gui.* and rendertype_text.* of tools/shaderhooks. This script checks that the shared files are up to date in every
project (shaderhooks.py), that the chat palette in cs_common.glsl equals the tabshader palette in th_common.glsl,
that cs_config.glsl in the pack is the default (bottom-left, margin 0), compiles gui and rendertype_text for every
position and a few margins with chatshader alone and with the other plugins, and runs shaderhooks combos (every
combination of plugins, every install order).
"""
import os
import re
import shutil
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO = os.path.dirname(ROOT)
INCLUDE = os.path.join(ROOT, "pack", "assets", "chatshader", "shaders", "include")
TH_COMMON = os.path.join(REPO, "tabshader", "pack", "assets", "tabshader", "shaders", "include", "th_common.glsl")
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "shaderhooks"))
import shaderhooks

DEFAULT_CONFIG = "#version 330\n\n#define CS_POSITION 0\n#define CS_MARGIN 0.0\n"
PALETTE = re.compile(r"^const (vec3|float) [A-Z]+_([A-Z]+) = (.+);$", re.M)


def palette_ok():
    cs = dict((m.group(2), m.group(3)) for m in PALETTE.finditer(open(os.path.join(INCLUDE, "cs_common.glsl")).read()))
    if not os.path.exists(TH_COMMON):
        print("SKIP palette: no tabshader in this repo")
        return True
    th = dict((m.group(2), m.group(3)) for m in PALETTE.finditer(open(TH_COMMON).read()))
    ok = cs == th and len(cs) == 6
    print(("OK  " if ok else "ERR ") + "palette cs_common == th_common: " + ", ".join(sorted(cs)))
    flow_cs = open(os.path.join(INCLUDE, "cs_common.glsl")).read()
    flow_th = open(TH_COMMON).read()
    body = lambda s, n: s[s.index("vec3 " + n + "_flow("):].split("\n}\n")[0]
    same = body(flow_cs, "cs").replace("cs_", "").replace("CS_", "") == body(flow_th, "th").replace("th_", "").replace("TH_", "")
    print(("OK  " if same else "ERR ") + "shimmer cs_flow == th_flow")
    return ok and same


def config_ok():
    ok = open(os.path.join(INCLUDE, "cs_config.glsl")).read() == DEFAULT_CONFIG
    print(("OK  " if ok else "ERR ") + "cs_config.glsl is the default")
    return ok


def positions(vanilla):
    ok = True
    count = 0
    with tempfile.TemporaryDirectory() as tmp:
        for plugins in (["chatshader"], shaderhooks.projects()):
            for pos in range(4):
                for margin in ("0.0", "6.0"):
                    d = os.path.join(tmp, f"{'_'.join(plugins)}_{pos}_{margin}")
                    shaderhooks.install_set(d, plugins)
                    cfg = os.path.join(d, "chatshader", "shaders", "include", "cs_config.glsl")
                    with open(cfg, "w") as fh:
                        fh.write(DEFAULT_CONFIG.replace("POSITION 0", f"POSITION {pos}").replace("MARGIN 0.0", f"MARGIN {margin}"))
                    good, n = shaderhooks.compile_dir(f"pos{pos}_m{margin}_{len(plugins)}", d, vanilla, tmp)
                    ok &= good
                    count += n
                    shutil.rmtree(d)
    print(("OK  " if ok else "ERR ") + f"every position and margin compiles ({count} program compilations)")
    return ok


def main(argv):
    vanilla = shaderhooks.load_vanilla(argv[0] if argv else shaderhooks.JAR)
    ok = shaderhooks.check(False)
    ok &= palette_ok()
    ok &= config_ok()
    ok &= positions(vanilla)
    ok &= shaderhooks.vanilla_diff(argv[:1])
    ok &= shaderhooks.combos(argv[:1])
    print("RESULT:", "compiles and links" if ok else "ERRORS")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
