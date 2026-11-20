import hashlib
import json
import os
import subprocess
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import mythichud_patch

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


def tree_files(repo, ref, project):
    base = project + "/pack/"
    out = {}
    if ref:
        names = subprocess.run(["git", "-C", repo, "ls-tree", "-r", "--name-only", ref, base], check=True, capture_output=True, text=True).stdout.split("\n")
        for name in names:
            if name.startswith(base + "assets/"):
                data = subprocess.run(["git", "-C", repo, "show", ref + ":" + name], check=True, capture_output=True).stdout
                out[name[len(base):]] = data
    else:
        root = os.path.join(repo, base)
        for d, _, files in os.walk(os.path.join(root, "assets")):
            for f in files:
                p = os.path.join(d, f)
                with open(p, "rb") as fh:
                    out[os.path.relpath(p, root).replace(os.sep, "/")] = fh.read()
    return out


def main():
    repo = sys.argv[1]
    dest = sys.argv[2]
    ref = sys.argv[3] if len(sys.argv) > 3 and sys.argv[3] != "worktree" else None
    merged = {}
    conflicts = []
    for project in ("textshader", "tabshader"):
        for name, data in tree_files(repo, ref, project).items():
            if name in merged and merged[name] != data:
                conflicts.append(name)
            merged[name] = data
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
    meta = {"pack": {"description": "tabshader + textshader test (" + (ref or "worktree") + ")", "pack_format": PACK_FORMAT, "min_format": PACK_FORMAT, "max_format": PACK_FORMAT}}
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
    for c in conflicts:
        print("CONFLICT (tabshader wins): " + c)
    if conflicts:
        sys.exit(2)


main()
