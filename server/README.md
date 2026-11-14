# Снимок продакшена (сервер 88635a9b)

Точная копия файлов с сервера на момент снимка — чтобы всё, что мы настраивали, лежало в репозитории
и откатывалось через git.

| Папка | Что |
|---|---|
| `plugins/Nexo/` | конфиги Nexo, глифы, предметы, `pack/assets/` (ассеты rezusmenu, core-portals, core-skyrift и общий шейдер `minecraft/shaders/core/rendertype_item_entity_translucent_cull.*`), `pack/external_packs/` |
| `plugins/MMOCore/`, `plugins/MythicLib/` | классы, навыки, скрипты, GUI — с правками навыков (история правок: `ops/mmocore-skills-2026-11-14/`) |
| `plugins/MythicHUD/` | конфиги и `source-pack` (собранный `built-pack` не храним — плагин собирает его сам) |
| `systems/` | `.azs` всех систем Amazake (в т.ч. `seal-magic`, `zatochka`, `AmazakeCore`, `AmazakeTab`, исходников которых тут нет) и их конфиги |

Не храним: кэши, собранный `pack.zip`, данные игроков (`players/`, `players.yml`, `userdata/`, база AmazakeCore).

Исходники наших систем — в корневых папках (`core-*`, `rezusmenu`), их собранные `.azs` — в `dist/`.

## Обновить снимок

```bash
python3 ops/pull-prod.py
```

Скрипт качает файлы через API панели (доступ даёт прокси сессии, ключи нигде не пишутся). После него — `git diff`,
чтобы увидеть, что поменялось на сервере.
