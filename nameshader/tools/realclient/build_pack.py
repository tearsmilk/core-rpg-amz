import hashlib
import json
import os
import sys
import tempfile
import zipfile

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "shaderhooks"))
import shaderhooks

PACK_FORMAT = 75


def main():
    dest = sys.argv[2]
    projects = [p for p in sys.argv[3].split(",") if p]
    with tempfile.TemporaryDirectory() as tmp:
        for p, written, foreign, kept, conflict in shaderhooks.install_set(tmp, projects):
            print(f"install {p}: written {len(written)} foreign {foreign} kept {kept}")
        merged = {"assets/" + k: v for k, v in shaderhooks.read_tree(tmp).items()}
    meta = {"pack": {"description": "nameshader test: " + ",".join(projects), "pack_format": PACK_FORMAT,
                     "min_format": PACK_FORMAT, "max_format": PACK_FORMAT}}
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    tmp = dest + ".part"
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("pack.mcmeta", json.dumps(meta, indent=2))
        for name in sorted(merged):
            z.writestr(name, merged[name])
    os.replace(tmp, dest)
    h = hashlib.sha1(open(dest, "rb").read()).hexdigest()
    print("pack: " + dest + " order=" + ",".join(projects) + " files=" + str(len(merged)) + " sha1=" + h)
    shaders = hashlib.sha1()
    for name in sorted(merged):
        shaders.update(name.encode() + b"\0" + merged[name])
    print("content sha1: " + shaders.hexdigest())


main()
