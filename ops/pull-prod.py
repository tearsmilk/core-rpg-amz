#!/usr/bin/env python3
"""Снимок продакшена в server/: Nexo, конфиги систем Amazake, MMOCore, MythicLib, MythicHUD (исходники).
Запуск: python3 ops/pull-prod.py  (доступ к панели даёт прокси сессии; ключи в репо не пишутся).
Пропускаются кэши, собранные паки и данные игроков."""
import json, os, re, time, subprocess, sys, urllib.parse
from concurrent.futures import ThreadPoolExecutor
B = "https://mgr.hosting-minecraft.pro/api/client/servers/88635a9b"
ROOTS = ["/plugins/Nexo", "/plugins/MMOCore", "/plugins/MythicLib", "/plugins/MythicHUD", "/systems"]
SKIP_NAMES = {".assetCache", ".deobfCachedPacks", "packsquash", "pack.zip", "logs", "cache", "userdata",
              "built-pack", "lib", "players", "players.yml", "profiles", "amazakecore.mv.db", ".installed"}
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "server")

def curl(url, out=None):
    args = ["curl", "-sfS", "--retry", "4", url] + (["-o", out] if out else [])
    r = subprocess.run(args, capture_output=True, text=out is None)
    if r.returncode: raise RuntimeError(f"{url}: {r.stderr}")
    return r.stdout

def listdir(d):
    for i in range(6):  # панель иногда отвечает редиректом/лимитом — повторяем
        try: return json.loads(curl(B + "/files/list?directory=" + urllib.parse.quote(d)))["data"]
        except (ValueError, RuntimeError):
            if i == 5: raise
            time.sleep(2 * (i + 1))

def walk(d):
    for f in listdir(d):
        a = f["attributes"]; p = d.rstrip("/") + "/" + a["name"]
        if a["name"] in SKIP_NAMES: continue
        if a["is_file"]: yield p
        else: yield from walk(p)

# Секреты в конфигах (ключи API) в снимок не попадают: значение заменяется на "".
REDACT = {"/systems/core-mechanics/config.yml": re.compile(r'^(\s*key:\s*)".*"', re.M)}


def get(p):
    dst = os.path.normpath(OUT + p)
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    for i in range(5):  # панель иногда отвечает 502 — повторяем
        try:
            curl(B + "/files/contents?file=" + urllib.parse.quote(p), dst)  # сырые байты, работает и для png/zip
            break
        except RuntimeError:
            if i == 4: raise
            time.sleep(3 * (i + 1))
    if p in REDACT:
        with open(dst, encoding="utf-8") as f:
            txt = f.read()
        with open(dst, "w", encoding="utf-8") as f:
            f.write(REDACT[p].sub(r'\1""', txt))
    return p

if __name__ == "__main__":
    files = [p for r in ROOTS for p in walk(r)]
    with ThreadPoolExecutor(8) as ex:
        for p in ex.map(get, files): print(p)
    print(len(files), "файлов →", os.path.normpath(OUT), file=sys.stderr)
