import hashlib
import json
import os
import sys
import zipfile

PACK_FORMAT = 75


def tree_files(repo, project):
    root = os.path.join(repo, project, "pack")
    out = {}
    for d, _, files in os.walk(os.path.join(root, "assets")):
        for f in files:
            p = os.path.join(d, f)
            with open(p, "rb") as fh:
                out[os.path.relpath(p, root).replace(os.sep, "/")] = fh.read()
    return out


def main():
    repo = sys.argv[1]
    dest = sys.argv[2]
    projects = [p for p in sys.argv[3].split(",") if p]
    merged = {}
    owner = {}
    conflicts = []
    for project in projects:
        for name, data in tree_files(repo, project).items():
            if name in merged and merged[name] != data:
                conflicts.append(name + " (" + owner[name] + " / " + project + ")")
            merged[name] = data
            owner[name] = project
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
    for c in conflicts:
        print("CONFLICT: " + c)
    if conflicts:
        sys.exit(2)


main()
