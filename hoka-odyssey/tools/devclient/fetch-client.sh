#!/bin/bash
# Ванильный клиент $MC_VERSION для стенда → $MC_CLIENT_DIR (по умолчанию $HOKA_DEV_DIR/mcclient):
#   client.jar, libraries/ (только для Linux), classpath.txt, natives/ (LWJGL сам распакует туда .so),
#   assets/ + assetindex.txt.
# Ассеты: HOKA_ASSETS=empty (по умолчанию) — пустой индекс, игре хватает того, что в client.jar (английский язык,
# текстуры, шейдеры); HOKA_ASSETS=full — все объекты, кроме звуков и музыки.
# Источники — официальные piston-meta / piston-data / libraries.minecraft.net / resources.download.minecraft.net.
set -euo pipefail
. "$(dirname "$0")/env.sh"

mkdir -p "$MC_CLIENT_DIR"
python3 -I - "$MC_CLIENT_DIR" "$MC_VERSION" "${HOKA_ASSETS:-empty}" <<'PY'
import hashlib, json, os, sys, time, urllib.request

out, version, assets_mode = sys.argv[1], sys.argv[2], sys.argv[3]

def fetch(url, path=None, sha1=None):
    if path and os.path.isfile(path) and (sha1 is None or hashlib.sha1(open(path, "rb").read()).hexdigest() == sha1):
        return open(path, "rb").read()
    for attempt in range(4):
        try:
            with urllib.request.urlopen(url, timeout=60) as r:
                data = r.read()
            break
        except Exception as e:
            if attempt == 3:
                raise
            print(f"повтор {url}: {e}", file=sys.stderr)
            time.sleep(3 * (attempt + 1))
    if sha1 and hashlib.sha1(data).hexdigest() != sha1:
        raise SystemExit(f"sha1 не сходится: {url}")
    if path:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path + ".tmp", "wb") as f:
            f.write(data)
        os.replace(path + ".tmp", path)
    return data

manifest = json.loads(fetch("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"))
entry = next(v for v in manifest["versions"] if v["id"] == version)
vjson = json.loads(fetch(entry["url"], os.path.join(out, version + ".json"), entry["sha1"]))

def allowed(rules):
    if not rules:
        return True
    ok = False
    for r in rules:
        os_ok = "os" not in r or r["os"].get("name") == "linux"
        if os_ok and "features" not in r:
            ok = r["action"] == "allow"
    return ok

cp = []
for lib in vjson["libraries"]:
    if not allowed(lib.get("rules")):
        continue
    art = lib.get("downloads", {}).get("artifact")
    if not art:
        continue
    path = os.path.join(out, "libraries", art["path"])
    fetch(art["url"], path, art["sha1"])
    cp.append(os.path.abspath(path))

client = vjson["downloads"]["client"]
fetch(client["url"], os.path.join(out, "client.jar"), client["sha1"])
cp.append(os.path.abspath(os.path.join(out, "client.jar")))
with open(os.path.join(out, "classpath.txt"), "w") as f:
    f.write(":".join(cp))
os.makedirs(os.path.join(out, "natives"), exist_ok=True)

idx_dir = os.path.join(out, "assets", "indexes")
os.makedirs(idx_dir, exist_ok=True)
if assets_mode == "full":
    ai = vjson["assetIndex"]
    index = json.loads(fetch(ai["url"], os.path.join(idx_dir, ai["id"] + ".json"), ai["sha1"]))
    n = 0
    for name, o in index["objects"].items():
        if name.startswith(("minecraft/sounds/", "minecraft/music/")):
            continue
        h = o["hash"]
        fetch(f"https://resources.download.minecraft.net/{h[:2]}/{h}",
              os.path.join(out, "assets", "objects", h[:2], h), h)
        n += 1
    index_id = ai["id"]
    print(f"ассетов: {n} (без звуков)")
else:
    index_id = "hoka-empty"
    with open(os.path.join(idx_dir, index_id + ".json"), "w") as f:
        f.write('{"objects":{}}')
with open(os.path.join(out, "assetindex.txt"), "w") as f:
    f.write(index_id)
print(f"клиент {version}: {out} (библиотек {len(cp) - 1})")
PY
