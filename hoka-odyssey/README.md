# Модули Hoka Odyssey (Minestom 1.21.11)

Модули для сервера **Hoka Odyssey** — ядро на Minestom из репозитория
[SakuraMesd/HokaOdyssey](https://github.com/SakuraMesd/HokaOdyssey). Здесь только модули; ядро и его API мы **читаем**,
но не меняем (правила — `DEVELOPER.md` в репозитории ядра).

| Модуль | jar | Что делает |
|---|---|---|
| `coreshaders` | `coreshaders.jar` | Порт плагина `coreshaders` (Paper) — «окна в другой мир»: куб или плоскость с панорамой 360° на шейдере. Эффекты — `ItemDisplay`, команды `/hocs…`. `depends: [respack]` |
| `spacesky` | `spacesky.jar` | Небо обычного мира — космос: шейдер неба (звёзды, туманности, Млечный путь), даль мира уходит в темноту, солнце/луна/облака убраны. Только файлы в ресурспаке, на сервере ничего не крутится. `depends: [respack]` |

Ресурспак собирает и раздаёт модуль **`respack`** (автор — Takuyamo, уже стоит на сервере, от него зависит и `ships`).
Его исходников здесь нет: `respack-api/` — только интерфейс `ResourcePackApi`, снятый с `module/respack.jar`, чтобы
`coreshaders` компилировался; в выкладку он не идёт (класс берётся из настоящего `respack.jar`). Если в respack
поменяют интерфейс — обновить `respack-api/src/main/java/ink/hoka/odyssey/respack/ResourcePackApi.java`.

## Сборка

Нужны JDK 25 и git (Gradle — через `./gradlew`).

```bash
./gradlew build
```

→ `coreshaders/build/libs/coreshaders.jar` (+ тесты).

Ядро подключается как *included build*: при первой сборке `settings.gradle.kts` сам клонирует
`SakuraMesd/HokaOdyssey` (ветка `main`) в `.hoka-core/` (в git не идёт), и модули компилируются против его `:api`.

- обновить ядро до свежего `main`: `./gradlew build -Phoka.core.update=true` (или удалить `.hoka-core/`);
- свой клон ядра: `-Phoka.core.dir=/путь/к/HokaOdyssey` или переменная `HOKA_CORE_DIR`;
- другая ветка/тег: `-Phoka.core.ref=<ref>`.

Локальный сервер с модулями: `./gradlew runServer` (папка `run/`, порт 25565, HTTP пака — 25574).
После правок: `./gradlew deployModules`, в консоли сервера `homodule reload coreshaders`. Для `runServer` положите `respack.jar` (Takuyamo) в `run/module/`.

## Выкладка на сервер

1. На сервере должен стоять модуль `respack` (Takuyamo) — он раздаёт пак (порт `25574`, `module/respack/config.yml`).
2. Положить `coreshaders.jar` в `module/` и в консоли: `homodule load coreshaders.jar`
   (обновление — заменить jar и `homodule reload coreshaders`).
3. `coreshaders` при включении отдаёт respack шейдер, модели и панорамы (`put`) и просит пересобрать пак;
   respack рассылает его игрокам.

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
- Встроенный пак (шейдер, модели, 12 панорам) лежит в jar (`src/main/resources/pack/`); при включении и после
  `/hocs import|upload|delete-image` модуль читает его и свои картинки (`module/coreshaders/pack/`) в async и отдаёт
  в respack через `put`, затем `rebuild`. Исходники для `/hocs import` — `module/coreshaders/images/`.
- Шейдер и номера картинок не менялись — старые свои картинки (`kinds`) совместимы.

Инструменты (`coreshaders/tools/`): `panoramas.py` (генерация панорам и файлов пака), `glsl_check.py`, `preview.py` —
те же, что в плагине, пути поправлены на `src/main/resources/pack/`.

## spacesky

- `sky.vsh`/`sky.fsh` — в 1.21.11 у неба обычного мира нет текстуры (это один цвет из шейдера `sky`), поэтому космос
  рисуется в нём же: шум и три слоя звёзд по ячейкам, без текстур и лишних проходов. Край диска неба опущен ниже
  горизонта — нет голубой полосы.
- `include/fog.glsl` — туман дальности прорисовки уходит в цвет космоса (вода, лава, слепота — как в ванили).
- `stars.fsh` — ванильные звёзды выключены (свои уже в небе).
- Солнце, луна, облака — прозрачные текстуры (`hide-sun`, `hide-moon`, `hide-clouds` в `config.yml`).
- Действует во всех мирах с обычным небом (Энд и Незер не трогает). Выключить: `homodule unload spacesky` — файлы
  уходят из пака, после пересборки у игроков обычное небо.

## Проверка

- `./gradlew build` — юнит-тесты (хранение эффектов, файлы пака, геометрия, GIF, панорамы, скачивание, луч).
- `RESPACK_JAR=/путь/к/respack.jar tools/e2e/run.sh` — живая проверка ботом (mineflayer) на настоящем ядре с модулем
  respack с сервера: пак с файлами coreshaders, жезл, куб и плоскость, `/hocs info`, команда на эффекте и вход в него,
  сохранение, `homodule reload` coreshaders и respack без дублей, `/hocs import` → картинка в новом паке, ЛКМ-удаление,
  `/hocs` недоступна не-OP. Ядро для теста запускается с офлайн-входом (копия `OdysseyServer` с `Auth.Offline`,
  только в `tools/e2e/.harness/`).
