#!/usr/bin/env python3
"""tabshader - keep the shared text shader files identical in tabshader/pack and textshader/pack.

    python3 -I tabshader/tools/sync_textshader.py          # check only, exit 1 on a difference
    python3 -I tabshader/tools/sync_textshader.py --write  # copy

Shared: minecraft/shaders/core/rendertype_text.vsh and .fsh (both hooks: ts_vertex/ts_fragment and
th_text_vertex/th_text_fragment), the tabshader text includes (th_common, th_text_vertex, th_text_fragment) and
the textshader includes. The tabshader text hook files are owned here and copied to textshader/pack; the
textshader includes are owned by textshader and copied here. Both packs can then be deployed alone or together
in any order: the files are byte-identical.
"""
import os
import shutil
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO = os.path.dirname(ROOT)
OURS = os.path.join(ROOT, "pack", "assets")
THEIRS = os.path.join(REPO, "textshader", "pack", "assets")
FROM_US = ["minecraft/shaders/core/rendertype_text.vsh", "minecraft/shaders/core/rendertype_text.fsh",
           "tabshader/shaders/include/th_common.glsl", "tabshader/shaders/include/th_text_vertex.glsl",
           "tabshader/shaders/include/th_text_fragment.glsl",
           "tabshader/shaders/include/th_layout.glsl"]


def from_them():
    d = os.path.join(THEIRS, "textshader", "shaders", "include")
    return ["textshader/shaders/include/" + n for n in sorted(os.listdir(d)) if n.endswith(".glsl")]


def same(a, b):
    return os.path.exists(a) and os.path.exists(b) and open(a, "rb").read() == open(b, "rb").read()


def main(argv):
    write = "--write" in argv
    ok = True
    pairs = [(os.path.join(OURS, r), os.path.join(THEIRS, r), r) for r in FROM_US]
    pairs += [(os.path.join(THEIRS, r), os.path.join(OURS, r), r) for r in from_them()]
    for src, dst, rel in pairs:
        if same(src, dst):
            print("SAME", rel)
            continue
        if write:
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copyfile(src, dst)
            print("COPY", rel, "->", os.path.relpath(dst, REPO))
        else:
            print("DIFF", rel)
            ok = False
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
