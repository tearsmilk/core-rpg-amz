#!/usr/bin/env python3
"""Внешний пак шейдеров сущностей AZERUS (entity.vsh / entity.fsh / include/armor3d.glsl) — только для 1.21.11.

    python3 -I nexo-items/tools/entity_shaders_pack.py build [out.zip]     # → nexo-items/build/azerus-entity-shaders.zip
    python3 -I nexo-items/tools/entity_shaders_pack.py verify <pack.zip>   # проверка собранного Nexo pack.zip

Зачем отдельный пак: сервер 1.21.11, но через ViaVersion заходят и клиенты 26.x, а у них core/entity другой
(26.1/26.2: sample_lightmap, DISSOLVE; 26.3: #include, layout(location), GLINT). Наш вариант 1.21.11 в базовом
assets/ сломал бы им все сущности. Поэтому файлы лежат в оверлее `azerus_entity_1_21_11` с форматом ровно 75
(1.21.11), как оверлеи MythicHUD в external_packs/hl-resourcepack.zip; остальные версии видят ванильные шейдеры
(без свечения/3D-брони/хука меню — 2D-броня у них и так есть).

Исходник — nexo-items/entity_shaders/ (pack.mcmeta + оверлей). Zip кладётся в /plugins/Nexo/pack/external_packs/;
Nexo вливает его в общий pack.zip. verify проверяет, что Nexo сохранил оверлей: запись в pack.mcmeta, файлы
байт-в-байт, в базовом assets/minecraft/shaders НЕТ core/entity.* и include/armor3d.glsl, оверлеи MythicHUD на месте.
"""
import hashlib
import io
import json
import os
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "entity_shaders")
OVERLAY = "azerus_entity_1_21_11"
FORMAT = 75
OUT = os.path.join(ROOT, "build", "azerus-entity-shaders.zip")
FILES = ["assets/minecraft/shaders/core/entity.vsh", "assets/minecraft/shaders/core/entity.fsh",
         "assets/minecraft/shaders/include/armor3d.glsl"]
EPOCH = (2026, 1, 1, 0, 0, 0)


def source_files():
    out = {"pack.mcmeta": open(os.path.join(SRC, "pack.mcmeta"), "rb").read()}
    for rel in FILES:
        out[f"{OVERLAY}/{rel}"] = open(os.path.join(SRC, OVERLAY, rel), "rb").read()
    return out


def build(out=OUT):
    files = source_files()
    meta = json.loads(files["pack.mcmeta"])
    entries = meta["overlays"]["entries"]
    assert [e["directory"] for e in entries] == [OVERLAY], entries
    for root, _, names in os.walk(SRC):
        for n in names:
            rel = os.path.relpath(os.path.join(root, n), SRC).replace("\\", "/")
            if rel not in files:
                raise SystemExit(f"лишний файл в {SRC}: {rel} (добавьте его в FILES или удалите)")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:   # детерминированно: порядок и время фиксированы
        for name in sorted(files):
            info = zipfile.ZipInfo(name, EPOCH)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            z.writestr(info, files[name])
    open(out, "wb").write(buf.getvalue())
    print(f"{out}: {len(files)} файлов, sha256 {hashlib.sha256(buf.getvalue()).hexdigest()}")
    return out


def _fmt_ok(e):
    """Запись оверлея действует ровно на формат 75 (оба способа записи: formats и min/max_format)."""
    f = e.get("formats")
    if isinstance(f, int):
        f = [f, f]
    elif isinstance(f, dict):
        f = [f.get("min_inclusive"), f.get("max_inclusive")]
    mn, mx = e.get("min_format"), e.get("max_format")
    mn = mn[0] if isinstance(mn, list) else mn
    mx = mx[0] if isinstance(mx, list) else mx
    return f == [FORMAT, FORMAT] and mn == FORMAT and mx == FORMAT


def verify(pack_zip, report=print):
    """Проблемы (список строк) в собранном паке Nexo. Пустой список — всё на месте."""
    problems = []
    src = source_files()
    with zipfile.ZipFile(pack_zip) as z:
        names = set(z.namelist())
        try:
            meta = json.loads(z.read("pack.mcmeta"))
        except KeyError:
            return ["в паке нет pack.mcmeta"]
        entries = (meta.get("overlays") or {}).get("entries") or []
        ours = [e for e in entries if e.get("directory") == OVERLAY]
        if not ours:
            problems.append(f"pack.mcmeta: нет оверлея {OVERLAY} (Nexo его выкинул?)")
        for e in ours:
            if not _fmt_ok(e):
                problems.append(f"pack.mcmeta: оверлей {OVERLAY} не ограничен форматом {FORMAT}: {e}")
        hud = sorted({e.get("directory") for e in entries if str(e.get("directory", "")).startswith("mythichud")})
        report(f"   оверлеи в pack.mcmeta: {len(entries)}; наш: {'да' if ours else 'НЕТ'}; MythicHUD: {', '.join(hud) or 'НЕТ'}")
        if not hud:
            problems.append("pack.mcmeta: пропали оверлеи MythicHUD (hl-resourcepack) — слияние mcmeta сломалось")
        for rel in FILES:
            name = f"{OVERLAY}/{rel}"
            if name not in names:
                problems.append(f"нет {name}")
            elif z.read(name) != src[name]:
                problems.append(f"{name} отличается от репозитория (Nexo изменил файл или залит старый zip)")
            if rel in names:
                problems.append(f"{rel} лежит в базовом assets — его получат и клиенты 26.x; удалите "
                                f"/plugins/Nexo/pack/{rel}")
        for n in sorted(names):
            if n.endswith(("/shaders/core/entity.vsh", "/shaders/core/entity.fsh")) and not n.startswith(OVERLAY + "/") \
                    and n not in FILES:
                report(f"   ! чужой оверлей тоже меняет core/entity: {n}")
    report(f"   файлы оверлея: {'ok' if not problems else 'см. ошибки'}")
    return problems


def main(argv):
    if not argv or argv[0] not in ("build", "verify"):
        print(__doc__)
        return 2
    if argv[0] == "build":
        build(argv[1] if len(argv) > 1 else OUT)
        return 0
    if len(argv) < 2:
        print("укажите pack.zip")
        return 2
    problems = verify(argv[1])
    for p in problems:
        print("   ✗", p)
    print("ИТОГ:", "ok" if not problems else f"ПРОБЛЕМ: {len(problems)}")
    return 0 if not problems else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
