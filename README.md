# Core RPG

RPG-сервер Minecraft. Хост Pterodactyl: `88635a9b` (lobby, mgr.hosting-minecraft.pro).

## Документы

- **`docs/DEVELOPER_GUIDE.md` — руководство разработчика: как всё устроено, сборка, тесты, заливка, шейдеры, системы и команды.**

- `docs/economy.html` — экономика сервера: уровни 1–71, классы, профессии, аспекты, заточка.
  Опубликовано как артефакт: https://claude.ai/artifact/TgWJZzhqawA91zKoPHTvnR

## Что где

- `core-effects`, `core-mobs`, `core-npc`, `core-portals`, `core-rpg`, `core-skyrift`, `core-vault`, `core-weapons`, `core-world`, `rezusmenu` — исходники систем Amazake; `dist/` — собранные `.azs`. `core-npc` — свои NPC (манекены со скином, модели ModelEngine, диалоги как в LuxDialogues) вместо Citizens. `core-vault` — экономика (`/money`, `/mpay`, `/ceco`, провайдер Vault или поверх встроенной экономики Vault 2.x), свои уровни (`/level`) и монеты-подсолнухи, которые при подъёме сразу идут на баланс . `core-weapons` — эффекты кастомного оружия Nexo: частицы вокруг клинка, кадры замаха, дрожь земли при крите (первое — «Багровая коса»). `core-mechanics` *(ещё не на проде)* — рэгдоллы игроков на физдвижке Jolt (`/rd`) и куклы-статуи.
- `hoka-odyssey/` — модули для сервера **Hoka Odyssey** (Minestom 1.21.11, ядро — `SakuraMesd/HokaOdyssey`, только читаем): `coreshaders` (порт `coreshaders` на `ItemDisplay`, команды `/hocs`) и `spacesky` (небо — космос, шейдер); ресурспак — через модуль `respack` с сервера, вместо Nexo. См. `hoka-odyssey/README.md`.
- `server/` — снимок продакшена: Nexo (конфиги и пак, общие шейдеры), MMOCore, MythicLib, MythicHUD, конфиги систем. Обновить: `python3 ops/pull-prod.py`.
- `ops/` — правки и бэкапы продакшена (навыки MMOCore и т.д.).

## Творческий режим и zatochka (найдено 2026-11-18)

На проде в творческом режиме не работал инвентарь: предмет из вкладки не берётся, не перекладывается, не
выбрасывается, средняя кнопка не копирует — всё «откатывается». Причина — чужая система **zatochka 1.1.5**
(`RequirementLore`, «лор требований MMOItems»): её обработчик `PlayerGameModeChangeEvent` вызывает
`context.runForPlayer(p, t -> refresh(p, true), 1L)`, а `runForPlayer(…, periodTicks)` в API Amazake — это
**повторяющаяся** задача, не задержка. Каждая смена режима игры запускает до выхода игрока ещё один
`player.updateInventory()` каждый тик (20 раз в секунду за каждую смену; у выживающих тоже — лишний трафик).
Каждый `updateInventory()` шлёт клиенту весь инвентарь с пустым курсором, а в творческом курсор живёт только у
клиента — его стирает в тот же тик. Проверено на копии прода (`/home/user/mc-prodcopy`, бот `mc-bot/pc6_verify.js`):
с zatochka как на проде — 60–100 пересылок инвентаря в секунду, без лора требований или с исправлением — 0.

- **Сразу, без пересборки**: в `systems/zatochka/sharpen.yml` добавить `requirement-lore: { enabled: false }` и
  `amazake system reload zatochka` — запущенные задачи сразу становятся пустыми (перезаход не нужен). Пропадают
  строки «Требуется ур. …/класс» в описании предметов MMOItems.
- **Исправление в zatochka** (исходников у нас нет — автору): в `RequirementLore.onGameMode` заменить
  `runForPlayer(p, t -> refresh(p, true), 1L)` на `runOnceForPlayer(p, () -> refresh(p, true))` (или отменять
  задачу: `t -> { t.cancel(); refresh(p, true); }`). Проверенная пересборка с этой правкой —
  `ops/zatochka-reqlore-fix/zatochka.azs` (из декомпиляции, дифф — `RequirementLore.decompiled.diff`).
- `/rpgadmin` (core-mechanics) — режим админа: творческий режим, и наши RPG-системы не трогают игрока; флаг
  `coremechanics:rpgadmin` в PDC (см. `core-mechanics/README.md`, «Режим админа»).
