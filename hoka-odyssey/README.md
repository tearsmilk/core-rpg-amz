# Модули Hoka Odyssey (Minestom 1.21.11)

Модули для сервера **Hoka Odyssey** — ядро на Minestom из репозитория
[SakuraMesd/HokaOdyssey](https://github.com/SakuraMesd/HokaOdyssey). Здесь только модули; ядро и его API мы **читаем**,
но не меняем (правила — `DEVELOPER.md` в репозитории ядра).

| Модуль | jar | Что делает |
|---|---|---|
| `respack` | `respack.jar` | Свой ресурспак вместо Nexo: собирает zip из файлов модулей (сервис `ResourcePackApi`), считает SHA-1, раздаёт встроенным HTTP и ставит игроку при входе. Отказ от пака — кик (настраивается) |
| `coreshaders` | `coreshaders.jar` | Порт плагина `coreshaders` (Paper) — «окна в другой мир»: куб или плоскость с панорамой 360° на шейдере. Эффекты — `ItemDisplay`, команды `/hocs…`. `depends: [respack]` |

## Сборка

Нужны JDK 25 и git (Gradle — через `./gradlew`).

```bash
./gradlew build
```

→ `respack/build/libs/respack.jar`, `coreshaders/build/libs/coreshaders.jar` (+ тесты).

Ядро подключается как *included build*: при первой сборке `settings.gradle.kts` сам клонирует
`SakuraMesd/HokaOdyssey` (ветка `main`) в `.hoka-core/` (в git не идёт), и модули компилируются против его `:api`.

- обновить ядро до свежего `main`: `./gradlew build -Phoka.core.update=true` (или удалить `.hoka-core/`);
- свой клон ядра: `-Phoka.core.dir=/путь/к/HokaOdyssey` или переменная `HOKA_CORE_DIR`;
- другая ветка/тег: `-Phoka.core.ref=<ref>`.

Локальный сервер с модулями: `./gradlew runServer` (папка `run/`, порт 25565, HTTP пака — 25574).
После правок: `./gradlew deployModules`, в консоли сервера `homodule reload coreshaders` (или `respack`).

## Выкладка на сервер

1. Положить `respack.jar` и `coreshaders.jar` в `module/` сервера.
2. **Открыть порт HTTP пака** (по умолчанию `25574`) — в Pterodactyl: Network → добавить allocation этого порта.
   Если порт другой — `module/respack/config.yml` → `http.port`.
3. Адрес для клиентов: по умолчанию — адрес, по которому игрок зашёл на сервер, + порт HTTP
   (`http://2.152.87.39:25574/...`). Если пак отдаётся с другого адреса — `http.public-url`.
4. В консоли: `homodule load respack.jar`, затем `homodule load coreshaders.jar`
   (или перезапуск сервера). Проверка: `/hopack` — собран ли пак, адрес, сколько файлов.

## respack

- Файлы пака: от модулей (`ResourcePackApi.putFile` / `putDirectory`) + своя папка администратора
  `module/respack/pack/` — перекрывает всё (например, `pack.png` или свой `pack.mcmeta`).
- `pack.mcmeta` собирается сам (формат 75 — Minecraft 1.21.11, берётся из Minestom).
- Zip детерминированный: одинаковое содержимое → тот же SHA-1 → клиент берёт пак из кэша, а после
  `homodule reload` пак не рассылается заново. Адрес `/pack/<sha1>.zip`; хранятся 2 последние версии.
- Вход: пак отправляется в фазе конфигурации, ядро Minestom ждёт ответа клиента — игрок появляется в мире уже с паком.
- После пересборки (новые картинки, `/hopack rebuild`) новый пак сразу уходит всем онлайн (`resend-on-rebuild`).
- `/hopack` (OP и консоль): состояние; `rebuild`; `reload` (перечитать `config.yml`, перезапустить HTTP при смене порта);
  `send [ник|all]`.

```java
// другой модуль, module.yml: depends: [respack]
ResourcePackApi pack = services().require(ResourcePackApi.class);   // брать заново после перезагрузок
pack.putDirectory(id(), dataFolder().resolve("pack"));                // папка = корень пака (assets/…)
pack.rebuild();
// onDisable: services().get(ResourcePackApi.class).ifPresent(p -> p.clear(id()));
```

## coreshaders

Всё, что было в плагине, кроме Nexo и прав Bukkit:

| Команда (только OP) | |
|---|---|
| `/hocs give [игрок] [cube\|plane] [картинка] [цвет]` | шейдерный жезл |
| `/hocs color\|image\|shape\|remove\|info` | эффект под прицелом |
| `/hocs list`, `/hocs removeall <радиус\|all>` | список / убрать |
| `/hocs cmd add <player\|console> <команда> [group]`, `cmd list\|remove\|clear\|perm <op\|off>\|msg` | команды на эффекте (порталы) |
| `/hocs trigger <enter\|click\|both\|off> [group]`, `/hocs cooldown <с> [group]`, `/hocs rim <on\|off> [group]` | |
| `/hocs upload <имя> <https-ссылка>`, `/hocs import <имя>`, `/hocs delete-image <имя>` | свои картинки и GIF → пак пересобирается и рассылается |
| `/hocs reload` | перечитать `config.yml` |

Жезл: ПКМ по блоку — поставить, ЛКМ — убрать, Shift+ПКМ — картинка, Shift+ЛКМ — форма.

Что изменилось при переносе:

- **Эффекты хранятся в `module/coreshaders/effects/<карта>.json`** — Minestom не сохраняет сущности вместе с миром.
  `ItemDisplay` и `Interaction` (хитбокс для кликов) создаются при загрузке чанка и убираются при выгрузке/выключении
  модуля. Эффекты ставятся только на картах из `maps/` (у карты есть имя — есть куда сохранить).
- Право на портал: вместо прав Bukkit — `/hocs cmd perm op` (только операторы) или `off` (все).
- Сообщения — в `config.yml`, цвета через `&` (MiniMessage в сервере нет).
- Встроенный пак (шейдер, модели, 12 панорам) лежит в jar (`src/main/resources/pack/`), respack читает его прямо
  из jar. Свои картинки — `module/coreshaders/pack/`, исходники для `/hocs import` — `module/coreshaders/images/`.
- Шейдер и номера картинок не менялись — старые свои картинки (`kinds`) совместимы.

Инструменты (`coreshaders/tools/`): `panoramas.py` (генерация панорам и файлов пака), `glsl_check.py`, `preview.py` —
те же, что в плагине, пути поправлены на `src/main/resources/pack/`.

## Проверка

- `./gradlew build` — юнит-тесты (сборка zip и SHA-1, HTTP, хранение эффектов, геометрия, GIF, панорамы, скачивание).
- `tools/e2e/run.sh` — живая проверка ботом (mineflayer) на настоящем ядре: вход с паком, жезл, куб и плоскость,
  `/hocs info`, команда на эффекте и вход в него, сохранение, `homodule reload` обоих модулей без дублей,
  `/hocs import` → новый пак онлайн-игроку, ЛКМ-удаление, кик при отказе от пака, `/hocs` недоступна не-OP.
  Ядро для теста запускается с офлайн-входом (копия `OdysseyServer` с `Auth.Offline`, только в `tools/e2e/.harness/`).
