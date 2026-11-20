import hashlib
import json
import os
import shutil
import sys
import urllib.request

VERSION = "1.21.11"
MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"


def fetch(url, dest, sha1=None):
    if os.path.isfile(dest) and (sha1 is None or digest(dest) == sha1):
        return
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    tmp = dest + ".part"
    with urllib.request.urlopen(url, timeout=120) as r, open(tmp, "wb") as f:
        shutil.copyfileobj(r, f)
    if sha1 is not None and digest(tmp) != sha1:
        os.remove(tmp)
        raise SystemExit("sha1 mismatch: " + url)
    os.replace(tmp, dest)


def digest(path):
    h = hashlib.sha1()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def allowed(rules):
    if not rules:
        return True
    ok = False
    for rule in rules:
        os_rule = rule.get("os")
        match = os_rule is None or (os_rule.get("name") in (None, "linux") and os_rule.get("arch") in (None, "x86_64", "amd64"))
        if match:
            ok = rule["action"] == "allow"
    return ok


def main():
    root = sys.argv[1]
    client_jar = sys.argv[2]
    os.makedirs(root, exist_ok=True)
    vjson = os.path.join(root, VERSION + ".json")
    if not os.path.isfile(vjson):
        man = os.path.join(root, "manifest.json")
        fetch(MANIFEST, man)
        entry = [v for v in json.load(open(man))["versions"] if v["id"] == VERSION][0]
        fetch(entry["url"], vjson, entry["sha1"])
    meta = json.load(open(vjson))
    jar = os.path.join(root, "client.jar")
    if not os.path.isfile(jar) or digest(jar) != meta["downloads"]["client"]["sha1"]:
        if os.path.isfile(client_jar) and digest(client_jar) == meta["downloads"]["client"]["sha1"]:
            shutil.copyfile(client_jar, jar)
        else:
            d = meta["downloads"]["client"]
            fetch(d["url"], jar, d["sha1"])
    cp = []
    for lib in meta["libraries"]:
        if not allowed(lib.get("rules")):
            continue
        art = lib.get("downloads", {}).get("artifact")
        if not art:
            continue
        name = lib["name"]
        if "aarch_64" in name:
            continue
        dest = os.path.join(root, "libraries", art["path"])
        fetch(art["url"], dest, art["sha1"])
        cp.append(dest)
    cp.append(jar)
    with open(os.path.join(root, "classpath.txt"), "w") as f:
        f.write(":".join(cp))
    idx = os.path.join(root, "assets", "indexes", "realclient-empty.json")
    os.makedirs(os.path.dirname(idx), exist_ok=True)
    with open(idx, "w") as f:
        f.write('{"objects": {}}')
    with open(os.path.join(root, "assetindex.txt"), "w") as f:
        f.write("realclient-empty")
    print("client ready: " + str(len(cp)) + " jars")


main()
