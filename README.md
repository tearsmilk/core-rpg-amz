# Core RPG

RPG-сервер Minecraft. Хост Pterodactyl: `88635a9b` (lobby, mgr.hosting-minecraft.pro).

## Документы

- **`docs/DEVELOPER_GUIDE.md` — руководство разработчика: как всё устроено, сборка, тесты, заливка, шейдеры, системы и команды.**

- `docs/economy.html` — экономика сервера: уровни 1–71, классы, профессии, аспекты, заточка.
  Опубликовано как артефакт: https://claude.ai/artifact/TgWJZzhqawA91zKoPHTvnR

## Что где

- `core-effects`, `core-mobs`, `core-npc`, `core-portals`, `core-rpg`, `core-skyrift`, `core-vault`, `core-weapons`, `core-world`, `rezusmenu` — исходники систем Amazake; `dist/` — собранные `.azs`. `core-npc` — свои NPC (манекены со скином, модели ModelEngine, диалоги как в LuxDialogues) вместо Citizens. `core-vault` — экономика (`/money`, `/mpay`, `/ceco`, провайдер Vault или поверх встроенной экономики Vault 2.x), свои уровни (`/level`) и монеты-подсолнухи, которые при подъёме сразу идут на баланс . `core-weapons` — эффекты кастомного оружия Nexo: частицы вокруг клинка, кадры замаха, дрожь земли при крите (первое — «Багровая коса»). `core-mechanics` *(ещё не на проде)* — рэгдоллы игроков на физдвижке Jolt (`/rd`) и куклы-статуи.
- `server/` — снимок продакшена: Nexo (конфиги и пак, общие шейдеры), MMOCore, MythicLib, MythicHUD, конфиги систем. Обновить: `python3 ops/pull-prod.py`.
- `ops/` — правки и бэкапы продакшена (навыки MMOCore и т.д.).
