import hashlib
import json
import os
import sys
import zipfile

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "shaderhooks"))
import shaderhooks

PACK_FORMAT = 75


def main():
    dest, assets = sys.argv[1], sys.argv[2]
    merged = {"assets/" + k: v for k, v in shaderhooks.read_tree(assets).items()}
    meta = {"pack": {"description": "chatshader test: " + assets, "pack_format": PACK_FORMAT,
                     "min_format": PACK_FORMAT, "max_format": PACK_FORMAT}}
    tmp = dest + ".part"
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("pack.mcmeta", json.dumps(meta, indent=2))
        for name in sorted(merged):
            z.writestr(name, merged[name])
    os.replace(tmp, dest)
    cfg = merged.get("assets/chatshader/shaders/include/cs_config.glsl", b"").decode().replace("\n", " ")
    print("pack: " + dest + " files=" + str(len(merged)) + " sha1=" + hashlib.sha1(open(dest, "rb").read()).hexdigest() + " cs_config: " + cfg)


main()
