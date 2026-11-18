#!/usr/bin/env python3
"""Rebuild Nexo/pack/external_packs/hl-resourcepack.zip from MythicHUD's freshly generated built-pack.

Why: players get the MythicHUD fonts/textures through Nexo's external pack hl-resourcepack.zip
(MythicHUD copy-pack is off). After /mythichud reload regenerates /plugins/MythicHUD/built-pack,
the zip must be rebuilt from it, otherwise the new HUD elements have no glyphs on the client.

  python3 -I tools/build_hl_pack.py fetch  [--out DIR]       # GET-only download of /plugins/MythicHUD/built-pack
  python3 -I tools/build_hl_pack.py build  --built DIR --base OLD.zip --out NEW.zip
  python3 -I tools/build_hl_pack.py check  NEW.zip           # sanity checks (fonts -> textures, overlays, azerus)

build: keeps every entry of the old zip EXCEPT assets/mythichud/** and the mythichud-* overlay folders
(those come 1:1 from built-pack), then adds all built-pack files (built-pack wins on conflicts, e.g.
the Azerus hotbar sprites in assets/minecraft/textures/gui/sprites/hud/). pack.mcmeta: the old one
(it already lists the MythicHUD overlays incl. mythichud-hide_vanilla_hud) plus any overlay entry
built-pack has and the old one lacks. Output is deterministic (sorted, fixed timestamps); prints sha256.
"""
import argparse
import hashlib
import io
import json
import os
import re
import subprocess
import sys
import time
import urllib.parse
import zipfile

B = "https://mgr.hosting-minecraft.pro/api/client/servers/88635a9b"
BUILT = "/plugins/MythicHUD/built-pack"
DROP = re.compile(r"^(assets/mythichud/|mythichud-[^/]+/)")


def curl(url, out=None):
    args = ["curl", "-sfS", "--retry", "4", url] + (["-o", out] if out else [])
    r = subprocess.run(args, capture_output=True, text=out is None)
    if r.returncode:
        raise RuntimeError(f"{url}: {r.stderr}")
    return r.stdout


def listdir(d):
    for i in range(6):
        try:
            return json.loads(curl(B + "/files/list?directory=" + urllib.parse.quote(d)))["data"]
        except (ValueError, RuntimeError):
            if i == 5:
                raise
            time.sleep(2 * (i + 1))


def walk(d):
    for f in listdir(d):
        a = f["attributes"]
        p = d.rstrip("/") + "/" + a["name"]
        if a["is_file"]:
            yield p
        else:
            yield from walk(p)


def fetch(out):
    from concurrent.futures import ThreadPoolExecutor
    files = list(walk(BUILT))

    def get(p):
        dst = os.path.join(out, os.path.relpath(p, BUILT))
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        for i in range(5):
            try:
                curl(B + "/files/contents?file=" + urllib.parse.quote(p), dst)
                return p
            except RuntimeError:
                if i == 4:
                    raise
                time.sleep(3 * (i + 1))

    with ThreadPoolExecutor(8) as ex:
        for _ in ex.map(get, files):
            pass
    print(len(files), "files ->", out)


def merge_mcmeta(old, new):
    o = json.loads(old)
    n = json.loads(new)
    have = {json.dumps(e, sort_keys=True) for e in o.get("overlays", {}).get("entries", [])}
    for e in n.get("overlays", {}).get("entries", []):
        if json.dumps(e, sort_keys=True) not in have:
            o.setdefault("overlays", {}).setdefault("entries", []).append(e)
    return json.dumps(o, indent=2, ensure_ascii=False).encode("utf-8")


def build(built, base, out):
    entries = {}
    with zipfile.ZipFile(base) as z:
        for n in z.namelist():
            if n.endswith("/") or DROP.match(n):
                continue
            entries[n] = z.read(n)
    old_meta = entries.get("pack.mcmeta")
    for root, _, files in os.walk(built):
        for f in files:
            full = os.path.join(root, f)
            rel = os.path.relpath(full, built).replace(os.sep, "/")
            with open(full, "rb") as fh:
                data = fh.read()
            if rel == "pack.mcmeta":
                entries[rel] = merge_mcmeta(old_meta, data) if old_meta else data
            else:
                entries[rel] = data
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for n in sorted(entries):
            zi = zipfile.ZipInfo(n, (2026, 1, 1, 0, 0, 0))
            zi.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(zi, entries[n])
    print(out, len(entries), "entries, sha256", hashlib.sha256(open(out, "rb").read()).hexdigest())


def check(path):
    problems = []
    with zipfile.ZipFile(path) as z:
        names = set(z.namelist())
        meta = json.loads(z.read("pack.mcmeta"))
        dirs = {e["directory"] for e in meta.get("overlays", {}).get("entries", [])}
        for d in ("mythichud-1_21_6", "mythichud-hide_vanilla_hud"):
            if d not in dirs:
                problems.append("pack.mcmeta: overlay %s missing" % d)
            if not any(n.startswith(d + "/") for n in names):
                problems.append("overlay folder %s empty" % d)
        fonts = [n for n in names if n.startswith("assets/") and "/font/" in n and n.endswith(".json")]
        for n in fonts:
            try:
                js = json.loads(z.read(n))
            except ValueError:
                problems.append("bad json " + n)
                continue
            for p in js.get("providers", []):
                f = p.get("file")
                if f:
                    ns, _, rest = f.partition(":") if ":" in f else ("minecraft", "", f)
                    tp = "assets/%s/textures/%s" % (ns, rest)
                    if tp not in names:
                        problems.append("%s -> missing %s" % (n, tp))
        az = [n for n in names if "azerus-layout" in n]
        if not az:
            problems.append("no azerus-layout fonts (was /mythichud reload done with the new config before fetch?)")
        for s in ("hotbar.png", "hotbar_selection.png"):
            if "assets/minecraft/textures/gui/sprites/hud/" + s not in names:
                problems.append("hotbar sprite missing: " + s)
        tex = [n for n in names if n.startswith("assets/mythichud/textures/assets/azerus/")]
        print("entries:", len(names), "| font jsons:", len(fonts), "| azerus-layout files:", len(az),
              "| azerus textures:", len(tex))
    if problems:
        print("PROBLEMS:")
        for p in problems[:50]:
            print("  -", p)
        sys.exit(1)
    print("OK")


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    f = sub.add_parser("fetch")
    f.add_argument("--out", default="built-pack")
    b = sub.add_parser("build")
    b.add_argument("--built", required=True)
    b.add_argument("--base", required=True)
    b.add_argument("--out", required=True)
    c = sub.add_parser("check")
    c.add_argument("zip")
    a = ap.parse_args()
    if a.cmd == "fetch":
        fetch(a.out)
    elif a.cmd == "build":
        build(a.built, a.base, a.out)
    else:
        check(a.zip)


if __name__ == "__main__":
    main()
