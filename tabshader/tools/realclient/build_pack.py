import hashlib
import json
import os
import sys
import tempfile
import zipfile

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "shaderhooks"))
import mythichud_patch
import shaderhooks

PACK_FORMAT = 75


def external(path, mode):
    with zipfile.ZipFile(path) as z:
        entries = {i.filename: z.read(i.filename) for i in z.infolist() if not i.is_dir()}
    meta = json.loads(entries.pop("pack.mcmeta", b"{}").decode("utf-8"))
    if mode == "patched":
        entries, changed, problems = mythichud_patch.patch_entries(entries)
        for c in changed:
            print("extpack patched: " + c)
        for p in problems:
            print("extpack PROBLEM: " + p)
    return entries, meta.get("overlays")


def tree_files(assets):
    return {"assets/" + k: v for k, v in shaderhooks.read_tree(assets).items()}


def main():
    repo = sys.argv[1]
    dest = sys.argv[2]
    ref = sys.argv[3] if len(sys.argv) > 3 and sys.argv[3] != "worktree" else None
    if ref:
        print("--ref is not supported any more: the pack is installed by tools/shaderhooks from the worktree")
        sys.exit(2)
    plugins = [p for p in (sys.argv[7] if len(sys.argv) > 7 and sys.argv[7] else "textshader,tabshader").split(",") if p]
    assets = sys.argv[8] if len(sys.argv) > 8 else ""
    conflicts = []
    if assets:
        merged = tree_files(assets)
        source = "server " + assets
    else:
        with tempfile.TemporaryDirectory() as tmp:
            for p, written, foreign, kept in shaderhooks.install_set(tmp, plugins):
                print(f"install {p}: written {len(written)} foreign {foreign} kept {kept}")
            merged = tree_files(tmp)
        source = "shaderhooks " + ",".join(plugins)
    overlay = sys.argv[4] if len(sys.argv) > 4 else ""
    if overlay:
        for d, _, files in os.walk(overlay):
            for f in files:
                path = os.path.join(d, f)
                with open(path, "rb") as fh:
                    merged[os.path.relpath(path, overlay).replace(os.sep, "/")] = fh.read()
                print("overlay: " + os.path.relpath(path, overlay))
    extmode = sys.argv[5] if len(sys.argv) > 5 else ""
    overlays = None
    if extmode:
        extzip = sys.argv[6]
        entries, overlays = external(extzip, extmode)
        added = 0
        for name, data in entries.items():
            if name.startswith("assets/") and name in merged:
                print("extpack base asset kept ours: " + name)
                continue
            merged[name] = data
            added += 1
        print("extpack " + extmode + ": " + extzip + " files=" + str(added))
    meta = {"pack": {"description": "shader test: " + source, "pack_format": PACK_FORMAT, "min_format": PACK_FORMAT, "max_format": PACK_FORMAT}}
    if overlays:
        meta["overlays"] = overlays
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    tmp = dest + ".part"
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("pack.mcmeta", json.dumps(meta, indent=2))
        for name in sorted(merged):
            z.writestr(name, merged[name])
    os.replace(tmp, dest)
    h = hashlib.sha1(open(dest, "rb").read()).hexdigest()
    print("pack: " + dest + " files=" + str(len(merged)) + " sha1=" + h)
    print("pack source: " + source)


main()
