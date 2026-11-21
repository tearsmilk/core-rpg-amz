#!/usr/bin/env python3
"""worldshader - build a standalone resource pack zip from worldshader/pack for a real 1.21.11 client test.

    python3 -I worldshader/tools/test_pack.py OUT.zip [ASSETS_DIR]

ASSETS_DIR defaults to worldshader/pack/assets (it can also be a Nexo pack assets dir written by the plugin).
pack.mcmeta uses the 1.21.11 resource pack format 75 (pack_format plus min_format and max_format).
"""
import json
import os
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PACK_FORMAT = 75


def main(argv):
    if not argv:
        print(__doc__.strip())
        return 2
    dest = os.path.abspath(argv[0])
    assets = os.path.abspath(argv[1]) if len(argv) > 1 else os.path.join(ROOT, "pack", "assets")
    meta = {"pack": {"description": "worldshader test pack", "pack_format": PACK_FORMAT,
                     "min_format": PACK_FORMAT, "max_format": PACK_FORMAT}}
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    tmp = dest + ".part"
    n = 0
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("pack.mcmeta", json.dumps(meta, indent=2) + "\n")
        for base, dirs, files in os.walk(assets):
            dirs.sort()
            for f in sorted(files):
                if f.endswith(".shaderhooks.tmp"):
                    continue
                p = os.path.join(base, f)
                z.write(p, "assets/" + os.path.relpath(p, assets).replace(os.sep, "/"))
                n += 1
    os.replace(tmp, dest)
    print("pack:", dest, "files:", n)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
