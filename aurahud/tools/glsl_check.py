#!/usr/bin/env python3
"""aurahud - check the HUD shaders with glslangValidator, GLSL 330 and 410.

    python3 -I aurahud/tools/glsl_check.py [client-1.21.11.jar]

aurahud owns no core file of its own: its hooks (ah_hook_gui_*, ah_hook_sprite_*, ah_hook_text_*) live in the
shared canonical gui.*, position_tex_color.* and rendertype_text.* of tools/shaderhooks. The layout is a generated
include (aurahud/shaders/include/ah_config.glsl); the shipped one is the vanilla layout (AH_ACTIVE 0), the files in
tools/configs/ are the generator output for every built-in preset plus a stress layout (GeneratorTest keeps them in
sync with the Java generator).

This script checks that the shared files are up to date in every project (shaderhooks.py), compiles every shared
program with aurahud alone and with all other plugins, compiles every tools/configs/*.glsl layout with every subset
of the other plugins, compiles the MythicHUD rendertype_text overlay of tabshader/textshader with every layout,
compares the canonical files with vanilla and runs shaderhooks combos (every combination of plugins, every install
order).
"""
import glob
import itertools
import os
import shutil
import sys
import tempfile
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "shaderhooks"))
import shaderhooks

CONFIG = "aurahud/shaders/include/ah_config.glsl"
CONFIGS = sorted(glob.glob(os.path.join(HERE, "configs", "*.glsl")))
MH_ZIP = os.environ.get("MYTHICHUD_ZIP", os.path.join(shaderhooks.REPO, "server", "plugins", "Nexo", "pack", "external_packs", "hl-resourcepack.zip"))


def programs(vanilla):
    ok = True
    count = 0
    with tempfile.TemporaryDirectory() as tmp:
        for plugins in (["aurahud"], shaderhooks.projects()):
            d = os.path.join(tmp, "_".join(plugins))
            shaderhooks.install_set(d, plugins)
            for path in shaderhooks.canonical_paths():
                if not os.path.exists(os.path.join(d, path)):
                    print("ERR " + path + " not installed with " + ", ".join(plugins))
                    ok = False
            good, n = shaderhooks.compile_dir("aurahud_" + str(len(plugins)), d, vanilla, tmp)
            ok &= good
            count += n
    print(("OK  " if ok else "ERR ") + f"aurahud alone and with every plugin compiles ({count} program compilations)")
    return ok


def layouts(vanilla):
    ok = True
    count = 0
    if not CONFIGS:
        print("ERR no tools/configs/*.glsl, run ./gradlew test -Daurahud.golden=write")
        return False
    others = [n for n in shaderhooks.projects() if n != "aurahud"]
    with tempfile.TemporaryDirectory() as tmp:
        for r in range(len(others) + 1):
            for subset in itertools.combinations(others, r):
                plugins = list(subset) + ["aurahud"]
                base = os.path.join(tmp, "base_" + "_".join(plugins))
                shaderhooks.install_set(base, plugins)
                for cfg in CONFIGS:
                    name = os.path.basename(cfg)[:-5]
                    d = os.path.join(tmp, "layout")
                    shutil.rmtree(d, ignore_errors=True)
                    shutil.copytree(base, d)
                    shutil.copyfile(cfg, os.path.join(d, CONFIG))
                    good, n = shaderhooks.compile_dir(f"{name}_{'+'.join(plugins)}", d, vanilla, tmp)
                    ok &= good
                    count += n
    print(("OK  " if ok else "ERR ") + f"{len(CONFIGS)} layouts x {2 ** len(others)} plugin sets compile ({count} program compilations)")
    return ok


def mythichud(vanilla):
    overlay = None
    for p in shaderhooks.OVERLAY_PROJECTS:
        d = os.path.join(shaderhooks.REPO, p, "pack", "overlays", shaderhooks.OVERLAY_DIR, "assets", "minecraft", "shaders", "core")
        if os.path.isdir(d):
            overlay = d
            break
    if overlay is None:
        print("SKIP mythichud overlay: no pack/overlays in " + ", ".join(shaderhooks.OVERLAY_PROJECTS))
        return True
    if not os.path.exists(MH_ZIP):
        print("SKIP mythichud overlay: " + MH_ZIP + " not found")
        return True
    utils = shaderhooks.OVERLAY_DIR + "/assets/minecraft/shaders/include/mythichud_utils.glsl"
    with zipfile.ZipFile(MH_ZIP) as z:
        if utils not in z.namelist():
            print("ERR mythichud: " + utils + " not in " + MH_ZIP)
            return False
        merged = dict(vanilla)
        merged["assets/minecraft/shaders/include/mythichud_utils.glsl"] = z.read(utils).decode("utf-8")
    ok = True
    count = 0
    with tempfile.TemporaryDirectory() as tmp:
        base = os.path.join(tmp, "all")
        shaderhooks.install_set(base, shaderhooks.projects())
        for cfg in CONFIGS:
            shutil.copyfile(cfg, os.path.join(base, CONFIG))
            missing = []
            src = {}
            for ext in ("vsh", "fsh"):
                text = open(os.path.join(overlay, "rendertype_text." + ext), encoding="utf-8").read()
                src[ext] = shaderhooks.expand(text, merged, set(), [base], missing)
            if missing:
                print("ERR mythichud overlay: missing includes " + ", ".join(sorted(set(missing))))
                ok = False
                continue
            for ver in ("330", "410"):
                ok &= shaderhooks.compile_pair(f"mythichud_{os.path.basename(cfg)[:-5]}_{ver}",
                                               src["vsh"].replace("#version 330", f"#version {ver}", 1),
                                               src["fsh"].replace("#version 330", f"#version {ver}", 1), tmp)
                count += 1
    print(("OK  " if ok else "ERR ") + f"MythicHUD rendertype_text overlay compiles with every layout ({count} program compilations)")
    return ok


def main(argv):
    vanilla = shaderhooks.load_vanilla(argv[0] if argv else shaderhooks.JAR)
    ok = shaderhooks.check(False)
    ok &= programs(vanilla)
    ok &= layouts(vanilla)
    ok &= mythichud(vanilla)
    ok &= shaderhooks.vanilla_diff(argv[:1])
    ok &= shaderhooks.combos(argv[:1])
    print("RESULT:", "compiles and links" if ok else "ERRORS")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
