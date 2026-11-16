# AZERUS / Volya — руководство разработчика

Всё, что нужно второму разработчику (или ИИ-помощнику), чтобы понять, как устроен сервер, где что лежит, как
собирать, тестировать и заливать. Актуально на 2026-11-16. Подробности по каждой системе — в её `README.md`.

---

## 1. Общая картина

- **Сервер:** Minecraft **1.21.11** на ядре **Amazake** — наш форк Paper
  ([tearsmilk/amazake](https://github.com/tearsmilk/amazake)). Сборка на проде: `Amazake 1.21.11-23-main@3a88fd5`.
- **Хостинг:** панель Pterodactyl `mgr.hosting-minecraft.pro`, сервер `88635a9b`.
- **Свой код** пишется не плагинами, а **системами Amazake** — файлы `.azs` в папке `/systems/` сервера. Это
  jar-подобные архивы, которые ядро грузит, включает, выключает и перезагружает **без рестарта сервера**.
- **Ресурспак** собирает плагин **Nexo**: всё наше (картинки меню, модели, шрифты, шейдеры) кладётся в
  `/plugins/Nexo/pack/assets/<namespace>/`, затем `nexo reload pack` (или `nexo reload all`, если менялись предметы).
- **Репозиторий** [tearsmilk/core-rpg-amz](https://github.com/tearsmilk/core-rpg-amz), ветка `main` — исходники
  всех систем, собранные `.azs`, снимок продакшена и бэкапы.

### Плагины на проде (не наши)

| Плагин | Зачем |
|---|---|
| Nexo | предметы, глифы, сборка и раздача ресурспака |
| ModelEngine R4.2.0 | 3D-модели мобов/NPC (`/plugins/ModelEngine/blueprints/*.bbmodel`, `meg reload`) |
| MMOCore + MythicLib | классы-«оболочки», навыки (skill bar), мана/энергия; навыки — `MythicLib/skill`, `MythicLib/script` |
| MMOItems | RPG-предметы |
| RoyalQuestClasses 1.0.5 | классы и профессии (Мечник/Стрелок/Маг/Вор + 8 профессий), уровни; синхронизирует скрытые классы MMOCore; исходников нет |
| MythicHUD | HUD (полоски, иконки) |
| PlaceholderAPI | плейсхолдеры (`%hl_…%` от RQ, `%mmocore_…%`, наши `%coreeffects_…%`, `%corevault_…%`) |
| Vault 2.1.0 (форк) | **встроенная экономика** со своими балансами (`plugins/Vault/balances.yml`), команды `/balance /pay /eco` |
| DeluxeMenus | старые меню-магазины (списывают деньги `eco take`) |
| SkinsRestorer, ViaVersion, ProtocolLib, packetevents, Multiverse-Core, FastAsyncWorldEdit, Carpentry, MazeGenerator | обычные служебные |

Выключены (файлы переименованы в `.jar1`): MythicMobs, Zatochka, MagicSeals, PlugManX, WorldGuard.
**Citizens удалён**: его сборка 2.0.44 не поддерживает 1.21.11 (нет NMS-модуля `v1_21_R7`). NPC — наша система core-npc.

### Системы на проде (`/systems/systems.yml`)

| Система | Исходники | Что делает |
|---|---|---|
| AmazakeCore | репо `tearsmilk/amazake` (`amazake-core-system`, обфусцирован) | essentials (дома, варпы, tpa, gamemode), группы и права, голограммы, чат. NPC-модуль **удалён** |
| AmazakeTab | репо amazake | таб-лист |
| core-effects | `core-effects/` | кастомные эффекты: Кровотечение, Паника, Тепло, Проклятье |
| core-mobs | `core-mobs/` | свои мобы (31 вариант), лут, регионы спавна |
| core-npc | `core-npc/` | NPC (манекены/мобы), модели ModelEngine, диалоги как в LuxDialogues, команды по клику |
| core-portals | `core-portals/` | порталы (красный/смоляной, изумрудная чёрная дыра, лазуритовый разлом) |
| core-skyrift | `core-skyrift/` | событие «разлом в небе» (незеритовый слиток → ад в небе, падают мобы core-mobs) |
| core-vault | `core-vault/` | экономика поверх Vault, свои уровни, монеты-подсолнухи |
| core-weapons | `core-weapons/` | эффекты кастомного оружия Nexo (замах кадрами, дрожь при крите и т.п.) |
| core-world | `core-world/` | правила мира: без голода, без летучих мышей, без дождя, взрывы не ломают блоки |
| rezusmenu | `rezusmenu/` | меню `/menuses`: RPG-инвентарь на дисплеях и меню профилей (персонажей) |
| seal-magic, zatochka | исходников нет (только `.azs`) | печати и заточка |

`core-rpg/` — большая RPG-система (прогрессия, аспекты, заточка, печати, бой), призвана заменить RQ/Zatochka/MagicSeals,
но **на проде выключена** (её нет в `systems.yml`).

---

## 2. Структура репозитория

```
core-*/ , rezusmenu/        исходники систем (Gradle, Java 21), у каждой README.md на русском
  ├─ src/main/java/...      код
  ├─ src/main/resources/    config.yml (с комментариями), system.yml, прочие yml
  ├─ src/test/java/...      юнит-тесты
  ├─ pack/assets/<ns>/      файлы ресурспака системы (если есть)
  ├─ tools/                 генераторы картинок/шрифтов (Python)
  └─ libs/                  jar API систем Amazake (в .gitignore — скопировать из соседней системы)
dist/                       собранные .azs (то, что лежит на проде)
nexo-items/                 наши предметы Nexo: items/*.yml + pack/assets/azerus/ + исходники моделей + конвертеры
models/                     модели ModelEngine (janet, vexa_npc), конвертер BDEngine → .bbmodel
server/                     СНИМОК ПРОДАКШЕНА (Nexo, MMOCore, MythicLib, MythicHUD, systems/*) — обновляется скриптом
ops/                        бэкапы конфигов перед заливками, правки навыков MMOCore, pull-prod.py
docs/                       документы (economy.html, это руководство)
```

---

## 3. Как пишется система Amazake

Шаблон — любая из `core-effects`, `core-npc`, `core-vault`. Основа:

- Класс системы реализует API `core.amazake.systems.api` (`enable(SystemContext)`, `disable()`, `reload()`).
- `system.yml` — имя, версия, главный класс, команды, права, `conflicts`.
- `config.yml` копируется в `/systems/<имя>/` при первом включении, **если его нет**. Обновить дефолт на проде —
  только заменой файла (см. раздел 5).
- Команды регистрируются системой. **Плагины регистрируют команды раньше систем и побеждают в конфликтах**
  (`/balance`, `/pay`, `/eco` заняты Vault, поэтому у core-vault `/money`, `/mpay`, `/ceco`).

### Правила потоков (ОБЯЗАТЕЛЬНО)

Amazake многопоточный (регионы, как Folia):

1. **Игрока трогаем только в его потоке**: `context.runForPlayer(player, task, periodTicks)` (повторяющаяся задача,
   отменяется сама при выходе) или `context.runOnceForPlayer(player, runnable)`.
2. **Сущности и блоки — только в потоке их региона**: `Bukkit.getRegionScheduler().execute(plugin, world, cx, cz, …)`;
   проверка `Bukkit.isOwnedByCurrentRegion(...)`.
3. **Глобальное состояние** (погода, время, мир целиком) — `Bukkit.getGlobalRegionScheduler()`.
4. **Не использовать** `Bukkit.getScheduler()` и entity-scheduler. Файлы и сеть — вне тиков (`runAsync…`),
   запись файлов атомарно (временный файл + rename).
5. **На disable всё убрать**: свои сущности (NPC, дисплеи), модификаторы атрибутов, эффекты, задачи, слушатели,
   сервисы (Vault-провайдер, PAPI-расширение). Иначе после перезагрузки системы останутся «хвосты».
6. Свои сущности — `setPersistent(false)` и метка в PDC (`<система>:…`), чтобы чистить остатки при следующем enable.

### Сборка

```bash
cd core-npc                      # любая система
cp ../core-effects/libs/*.jar libs/   # если libs/ пустой (jar API систем)
./gradlew test azs               # тесты + build/libs/<имя>.azs
cp build/libs/core-npc.azs ../dist/
```

---

## 4. Тестирование

- **Юнит-тесты** — `./gradlew test` в каждой системе (логика без сервера: парсинг конфигов, формулы, раскладки).
- **Тестовый сервер** `/home/user/mc-test` (в облачной среде Claude): тот же jar Amazake, порт **25599**,
  консоль через FIFO `console.in`.
  ```bash
  cd /home/user/mc-test && rm -f console.in && mkfifo console.in && \
    (nohup bash -c 'tail -f console.in | java -Xmx2G -jar amazake.jar --nogui > server.log 2>&1' >/dev/null 2>&1 &)
  echo "amazake system enable core-npc" > console.in     # команда в консоль
  echo stop > console.in
  ```
- **Боты** — mineflayer в `/home/user/mc-bot` (`lib.js`: `mkBot`, `con`, `conRead`); скрипты тестов `*.js` рядом.
  Боты не видят картинку — внешний вид (пак, шейдеры, модели) проверяется только в настоящем клиенте.
- `/home/user/mc-skilltest` (порт 25601) — копия стека MMOCore/MythicLib/PAPI для проверки урона навыков.
- Для параллельных тестов можно поднять временную копию сервера на другом порту (так тестировались мобы).

---

## 5. Заливка на прод (деплой)

**Правило команды: на прод заливаем только по явному «заливай» владельца.** Всё остальное — коммит в `main`.

Доступ — клиентский API панели Pterodactyl (`https://mgr.hosting-minecraft.pro/api/client/servers/88635a9b`) с
ключом API владельца (заголовок `Authorization: Bearer <ключ>`; **ключи в репо не храним**). Основные вызовы:

| Что | Вызов |
|---|---|
| список файлов | `GET files/list?directory=/systems` |
| прочитать файл | `GET files/contents?file=/path` (файлы > ~5 МБ не отдаёт) |
| записать файл | `POST files/write?file=/path` с телом `application/octet-stream` |
| удалить / переименовать | `POST files/delete`, `PUT files/rename` (JSON с `root` и `files`) |
| команда в консоль | `POST command` `{"command": "..."}` |
| старт/стоп | `POST power` `{"signal": "start|stop|restart"}`, статус — `GET resources` |
| лог (8+ МБ) | `POST files/compress` → переименовать архив в `.zip` → `files/contents` → удалить архив |

### Порядок заливки системы (без рестарта)

1. **Бэкап**: скачать текущий конфиг с прода в `ops/<система>-config.prod-before-<что>.yml`.
2. Загрузить новый `.azs` **рядом**: `/systems/<имя>.azs.new`, сверить sha256 (`files/contents` → sha).
   Нельзя перезаписывать живой `.azs` на месте — бывают ошибки загрузки классов.
3. `amazake system disable <имя>` → удалить старый `.azs` → переименовать `.new` в `.azs` →
   (если нужно, записать новый конфиг) → `amazake system enable <имя>`.
   Новая система: загрузить `.azs` и `amazake system enable <имя>` (сам допишет её в `systems.yml`).
4. **Конфиг**: если серверный конфиг совпадает с прошлым стандартным (сравнить с `git show <коммит>:…/config.yml`) —
   заменить новым; если владелец его правил — вносить только новые ключи руками.
5. Если менялся пак: загрузить файлы в `/plugins/Nexo/pack/assets/<ns>/`, затем `nexo reload pack`
   (`nexo reload all`, если менялись `plugins/Nexo/items/*.yml`). Игрокам — перезайти.
6. Проверить лог (строки «система … включена», нет `ERROR`/`Exception`, Nexo «Finished generating resourcepack»).
7. Обновить снимок и закоммитить: `python3 ops/pull-prod.py` → `git add -A server ops` → commit → push.

Перезагрузка сервера целиком (`power stop` → файлы → `power start`) нужна только для замены AmazakeCore/ядра и
плагинов.

### Снимок продакшена

`python3 ops/pull-prod.py` скачивает Nexo (конфиги + пак), MMOCore, MythicLib, MythicHUD (исходный пак), все `.azs` и
конфиги систем в `server/` (без кэшей, собранных паков и данных игроков). После — `git diff server/`, чтобы видеть,
что поменяли на сервере руками.

---

## 6. Ресурспак, шрифты, шейдеры

- Пространства имён: `rezusmenu` (меню), `coreportals`, `coreskyrift`, `coreeffects`, `corenpc`, `azerus`
  (предметы и оружие), `modelengine` (генерирует ModelEngine).
- **Картинки интерфейса = глифы шрифтов** (bitmap-провайдеры в `font/*.json`). Правила клиента 1.21.11:
  - глиф **не больше 256×256** (атлас шрифта) — широкие картинки режутся на части и склеиваются отрицательными
    пробелами;
  - `ascent ≤ height`, иначе клиент **выкидывает весь файл шрифта** («Unable to load font»);
  - последний столбец глифа не должен быть полностью прозрачным (ставим пиксель с альфой 1), иначе клиент сузит глиф.
- Меню/HUD на экране: rezusmenu — сущности-дисплеи «в глазах» игрока + курсор шейдером; core-npc — окно диалога в
  actionbar; core-effects — накладки в title.

### ОБЩИЙ ШЕЙДЕР — осторожно

`minecraft/shaders/core/rendertype_item_entity_translucent_cull.vsh/.fsh` — **одна копия на весь пак**, лежит
одинаковой в `rezusmenu/pack`, `core-portals/pack`, `core-skyrift/pack` (и на проде). В него встроены хуки трёх систем,
каждая узнаёт «свои» предметы по **точному маркеру цвета**:

| Система | Маркер |
|---|---|
| rezusmenu (курсор, предметы меню без света) | R = B = 254 ровно, G 1..253 (= размер курсора × 1000), только при UV2 = (240, 224) |
| core-portals | R = 255, G 64..95 (лазурит 80–83, B ≤ 250) |
| core-skyrift | R = 255, G 96 туман / 97 разлом / 98 вспышка, UV2 = (240, 240), B ≤ 250 |

Однажды проверка маркера с допуском совпала с белым цветом (255) — **все предметы на сервере превратились в курсор**.
Поэтому: новые эффекты по возможности делаем **без** шейдера (кадры моделей, анимированные текстуры, дисплеи,
частицы). Если шейдер всё-таки нужен — уникальный точный маркер, правка во **всех** копиях байт-в-байт, проверка
валидатором GLSL и доказательство, что обычные предметы не затронуты.

---

## 7. Системы — кратко и команды

Подробно — в README каждой системы.

**core-effects** (`/effects`, право `coreeffects.admin`): Кровотечение (капли, обморок, лечится бинтом), Паника
(тьма, фантомы, WASD путается, +урон −2 сердца), Тепло (скрытый реген, смерть без молока/хилки), Проклятье (вещи
выпадают на землю). `/effects give|remove|clear|list|bandage|reload`. Иконки в actionbar, плейсхолдеры `%coreeffects_…%`.

**core-mobs** (`/mobs`, `mobs.admin`): моб = запись в `systems/core-mobs/mobs.yml` (тип, имя, здоровье, урон, броня,
снаряжение, эффекты, `size`, `no-fire`…). Регионы спавна рисуются палочкой (`/mobs region …`), лут — `/mobs drops save`.
`/mobs spawn <id> <кол-во>`, `/mobs egg`. Мобы региона не сохраняются в мир, превращения (зомби→утопленник,
пиглин→зомби, деление слизней) отменяются, огненные шары не поджигают блоки.

**core-npc** (`/npc` = `/cnpc`, `corenpc.admin`; консольные команды на NPC — `corenpc.admin.console`):
`/npc create|remove|list|tp|tphere|rename|skin|type|model|dialog|command|look|set|talk|open|close|reload`.
NPC хранятся в `systems/core-npc/npcs.yml`, диалоги — `systems/core-npc/dialogs/*.yml` (пример `example.yml`:
страницы, ответы, условия, действия). Окно диалога — actionbar (режим `hud`) или нативное окно (`native`).
Модель ModelEngine: `/npc model <id> janet`. В диалоге чат чистится и чужие сообщения скрываются.

**core-portals** (`/portals`, `coreportals.admin`): `/portals give [игрок] [кол-во] [resin|emerald|lapis]` — предмет,
ставит портал (визуал шейдером, телепорт по `warp`). **core-skyrift** (`/skyrift`, `skyrift.admin`):
`/skyrift give|start|stop|list|reload`.

**core-vault** (`corevault.admin`): `/money`, `/mpay`, `/moneytop`, `/ceco give|take|set|reset|coin|drop|import|info|reload`,
`/level [ник]`, `/level give|take|set <ник> exp|levels <n>`. Режим `economy.backend: auto` — **балансы остаются в Vault**
(общие с магазинами, MMOCore, MMOItems). Монеты-подсолнухи падают с мобов (`coins.drops`) и при подборе сразу идут
на баланс.

**core-weapons** (`/cweapons give|list|reload`, `coreweapons.admin`): поведение оружия по `nexo:id` из `config.yml`.
Сейчас — «Багровая коса»: кадры замаха (сервер ставит `custom_model_data` `cw_swing_1..3`, пак выбирает модель),
огненный след, дрожь земли при ванильном крите (копии блоков-дисплеи, мир не меняется). Кольцо угольков — в самой
модели (анимированная текстура).

**core-world** (`/coreworld status|reload`): модули `hunger`, `spawn` (запрет летучих мышей), `weather` (без дождя),
`explosions` (ТНТ/криперы/дракон не ломают блоки) — каждый включается в `config.yml`.

**rezusmenu** (`/menuses`, `rezusmenu.admin`): панель с кнопками «RPG-инвентарь» и «Профили».
- RPG-меню: экран из дисплеев, игрок на время меню — наблюдатель в небе; курсор рисует шейдер; F открывает меню в
  выживании; бижутерия и хранилище в PDC игрока; `/menuses rows add <ник>` — ряды хранилища за донат.
- Меню профилей (`/profiles`): до 4 персонажей, у каждого свой класс/уровень (через API RoyalQuestClasses +
  снимки MMOCore), инвентарь, место, эффекты; деньги общие. Слоты: 2 бесплатно, покупка за Vault или
  `/menuses slots add <ник>`. «В лобби» и выбор класса → `player: warp 123`. Данные — `systems/rezusmenu/profiles/<uuid>.yml`.

**MMOCore/MythicLib навыки**: правки в `ops/mmocore-skills-2026-11-14/` (`after2/` — текущее на проде; отчёт с
таблицей урона `after2/REPORT.md`, `DAMAGE_TEST_FINAL.md`). Скрипты наших навыков — `MythicLib/script/royalquest_skills.yml`
(`rq_*`). После правки: `mythiclib reload`, затем `mmocore reload`.

---

## 8. Предметы и модели

- **Предметы Nexo** — `nexo-items/items/*.yml` (на проде `plugins/Nexo/items/`), модели и текстуры —
  `nexo-items/pack/assets/azerus/`. Выдача: `/nexo give <ник> <id>` (`azerus_moonwarden`, `azerus_crimson_scythe`,
  щиты `azerus_sun_bastion` и т.д., полный список в `nexo-items/README.md`).
- **Модель из Blockbench (Java Block/Item) → предмет**:
  `python3 nexo-items/tools/bbjava2json.py <файл.bbmodel> nexo-items/pack/assets azerus <id>` (UV → 0..16, повороты,
  display, `light_emission`). Повороты кубов — одна ось, ±22.5/±45; координаты −16..32. Щиты — `material: SHIELD` +
  `blocking_model`.
- **ModelEngine** (мобы/NPC): `.bbmodel` формата Generic → `/plugins/ModelEngine/blueprints/`, `meg reload` (дождаться
  «models loaded», только потом `nexo reload pack` — Nexo импортирует пак ModelEngine). Перед ModelEngine считается −Z.
  Модель BDEngine → `.bbmodel`: `python3 models/tools/bdengine2bbmodel.py` (центрирует в ноль, головы → кости).
- Превью моделей рендерятся скриптами (`nexo-items/tools/render_model.py`, `models/janet/preview.png`) — это не скрин
  из игры.

---

## 9. Доска задач (Trello)

Доска «ruruka»: столбцы **задачи → готово → заливай → залито**. Карточку без меток не трогаем; с зелёной меткой
«делать» — берём в работу (комментарий «Взял в работу»), по завершении комментарий с итогом и перенос в «готово».
Перенос владельцем в «заливай» = команда залить; после заливки — в «залито».

---

## 10. Частые грабли

- **Конфиг не обновился после заливки** — системы не перезаписывают существующий `config.yml`; заменить файл вручную.
- **`files/contents` вернул HTML-редирект** — файл слишком большой или временная ошибка панели; повторить или
  читать лог через архив.
- **ModelEngine не увидел модель** — `meg reload` прочитал папку, пока файл ещё записывался; дождаться совпадения sha
  и повторить.
- **Глифы/шрифт пропали в клиенте** — проверить `ascent ≤ height` и ширину глифа ≤ 256 во всём файле шрифта.
- **Команда системы не работает** — её перехватил плагин с тем же именем; использовать алиас.
- **Сущности остались после выключения системы** — не убраны на disable; они помечены PDC, следующий enable должен
  их подчистить.
- **Скин NPC по нику**: при ошибке Mojang в логе «Skin URL is null» — ставить скин через `/npc skin <id> value …`.
