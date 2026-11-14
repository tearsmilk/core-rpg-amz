# Core RPG

RPG-сервер Minecraft. Хост Pterodactyl: `88635a9b` (lobby, mgr.hosting-minecraft.pro).

## Документы

- `docs/economy.html` — экономика сервера: уровни 1–71, классы, профессии, аспекты, заточка.
  Опубликовано как артефакт: https://claude.ai/artifact/TgWJZzhqawA91zKoPHTvnR

## Что где

- `core-mobs`, `core-portals`, `core-rpg`, `core-skyrift`, `core-world`, `rezusmenu` — исходники систем Amazake; `dist/` — собранные `.azs`.
- `server/` — снимок продакшена: Nexo (конфиги и пак, общие шейдеры), MMOCore, MythicLib, MythicHUD, конфиги систем. Обновить: `python3 ops/pull-prod.py`.
- `ops/` — правки и бэкапы продакшена (навыки MMOCore и т.д.).
