# nexo-items — свои предметы Nexo

- `items/*.yml` → `plugins/Nexo/items/`, `pack/assets/<ns>/` → `plugins/Nexo/pack/assets/<ns>/`, затем `nexo reload all`.
- `source/` — исходные модели Blockbench; `tools/bbjava2json.py` переводит «Java Block/Item» .bbmodel в JSON-модель
  и текстуру: `python3 tools/bbjava2json.py <model.bbmodel> pack/assets <namespace> <id>`.

## azerus_weapons.yml — тестовое оружие (10 шт.)

| id | Название | Основа |
|---|---|---|
| azerus_iron_vow | Стандартный меч | железный меч |
| azerus_moonwarden | Лунный страж | алмазный меч |
| azerus_tidekeeper | Страж прилива | алмазный меч |
| azerus_ashen_oath | Изумрудная клятва | алмазный меч |
| azerus_ember_dawn | Угольный рассвет | алмазный меч |
| azerus_blood_raven | Кровавый ворон | алмазный меч |
| azerus_sunleaf | Солнечный лист | алмазный меч |
| azerus_night_watch | Ночной дозор (кинжал) | железный меч |
| azerus_frost_needle | Ледяной шип (кинжал) | железный меч |
| azerus_thundermaul | Громобой (молот) | алмазный меч |
| azerus_crimson_scythe | Багровая коса (объёмная, эффекты core-weapons) | незеритовый меч |

Выдать: `/nexo give <ник> <id>`.

## Багровая коса (`azerus_crimson_scythe`)

Модель объёмная: 83 куба (в руках ещё 12 угольков), все с поворотом по одной оси на 0/±22.5/±45°, координаты в пределах −16..32.
- **Лезвие** — дуга из отрезков через 11.25°. В каждом отрезке три слоя по радиусу: обух 1.5 px толщиной, середина
  1 px, режущая кромка 0.5 px. Получается скос к кромке. Кромка и искры светятся (`light_emission` 15, без затенения
  граней), середина — `light_emission` 4. После отзыва из игры лезвие сделано тоньше и уже (ширина ×0.7).
- **Древко** — ломаная из отрезков 1.5×1.5 с узлами на изломах, внизу комель с корешком. Через голову проходит светлая
  ветка с сучком, ниже — красная обмотка с перевязками.
- **Текстура** (128×128) раскрашивается кодом: цвет каждого текселя берётся из точки модели, куда он попадает с
  учётом поворота куба, поэтому на перекрытиях отрезков цвета совпадают. Палитра снята с референса, пять тонов со
  сдвигом оттенка, свет сверху слева.
- **Вид в руке** считается, а не подбирается на глаз (`aim` в скрипте). От третьего лица коса стоит почти вертикально
  (масштаб 0.66), лезвие над головой и смотрит вперёд, хват ниже обмотки. От первого лица она наклонена вверх-влево,
  масштаб 0.36, сдвинута правее и ниже, чтобы не закрывать экран.
- **Кольцо угольков** — сами кубики модели, частиц нет. 12 кубиков 1.1/0.8 px по наклонённому кольцу вокруг
  головы (`light_emission` 15, без затенения) есть только в `crimson_scythe_held` (в руках). У каждого своя клетка 2×2
  в анимированной текстуре `crimson_scythe_fx.png` (12 кадров, `frametime` 2, `interpolate`). По кадрам яркая
  голова с хвостом переходит от клетки к клетке, остальные клетки прозрачные, поэтому две искры бегут по кругу,
  оборот за 24 тика. В слоте, на земле и в рамке кольца нет. В слоте и рамке
  модель вписана в 16×16 с наклоном 10°.
- **Кадры замаха** `crimson_scythe_swing_1..3`: та же модель с кольцом (`parent` — `crimson_scythe_held`), меняется только `display` в руках —
  поворот на −40° (замах назад), +55° (удар) и +105° (проводка) вокруг оси плеч. Определение предмета
  `items/crimson_scythe.json`: в GUI, на земле, в рамке и на голове показывается основа, в руках — `crimson_scythe_held` и `select` по
  строке `custom_model_data` (`cw_swing_N` ставит система core-weapons). `hand_animation_on_swap: false`.

Файлы:
- `source/Багровая-коса.bbmodel` — проект Blockbench (java_block), его можно править.
- `tools/crimson_scythe.py` — собирает .bbmodel, JSON (через `bbjava2json.py`, теперь он переносит
  `light_emission` и `shade`), PNG, кадры и определение предмета.
- `tools/render_model.py` — программный рендер JSON-модели для проверки:
  `python3 tools/render_model.py pack/assets azerus:item/crimson_scythe preview crimson_scythe`.
- `preview/`: `crimson_scythe_{front,angles,gui,night}` — основа (слот и земля); `crimson_scythe_held_{front,angles,
  night,firstperson,thirdperson}` — в руках, с кольцом; `crimson_scythe_fx_frames` — 12 кадров анимации угольков;
  `crimson_scythe_swing_frames` — кадры замаха от первого и третьего лица.
- В .bbmodel кольца нет: его дописывает `crimson_scythe.py` в `crimson_scythe_held.json`.

После правки .bbmodel в Blockbench: `python3 tools/bbjava2json.py source/Багровая-коса.bbmodel pack/assets azerus crimson_scythe`.
Учтите, что `crimson_scythe.py` при запуске перезаписывает .bbmodel.

Деплой: `pack/assets/azerus/` → `plugins/Nexo/pack/assets/azerus/` (models/item/crimson_scythe*.json,
textures/item/crimson_scythe.png, crimson_scythe_fx.png + .mcmeta, items/crimson_scythe.json), `items/azerus_weapons.yml` → `plugins/Nexo/items/`,
затем `nexo reload all`. Эффекты сервера (кадры замаха, огненный след, дрожь) даёт система `core-weapons` (`dist/core-weapons.azs` → `systems/`).

## azerus_shields.yml — щиты (7 шт.)

Основа — ванильный щит. У каждого есть `<id>_blocking` — поза при блоке (сдвиг как у ванильного shield_blocking).

| id | Название |
|---|---|
| azerus_north_wall | Северная стена |
| azerus_oak_warden | Страж дубравы |
| azerus_crimson_bulwark | Багровый оплот |
| azerus_raven_wing | Крыло ворона |
| azerus_moon_shard | Лунный осколок |
| azerus_tide_seal | Печать прилива |
| azerus_sun_bastion | Солнечный бастион |

## azerus_armor.yml — кастомная броня (3 комплекта × 4 части)

Предметы Python-системы `custom_armor` (`core-python/scripts/custom_armor/`, там README и `sets.yml` с
характеристиками и бонусами). id — `azerus_<комплект>_<часть>`: комплекты `guren` (Багровый, незерит),
`tsukikori` (Лунный лёд, алмаз), `morikashi` (Дубрава, железо); части `helmet`, `chestplate`, `leggings`, `boots`.
Выдавать лучше `/carmor give <комплект> all [ник]` (сразу с характеристиками); `/nexo give` тоже работает —
характеристики система повесит при надевании.

- Формат — Nexo `CustomArmor`, как `nexo_defaults/nexo_armor.yml`: у нагрудника `layer1`/`layer2`, у всех частей
  `CustomArmor.id` и `Components.equippable` с `asset_id: azerus:<комплект>`; equipment-модель Nexo собирает сам.
- Текстуры (`pack/assets/azerus/textures/item/armor/`): `<комплект>_armor_layer_1.png` / `_layer_2.png` (64x32,
  раскладка ванильной брони) и иконки `<комплект>_<часть>.png` (16x16). Рисуются кодом:
  `python3 tools/armor_textures.py`; превью на модели игрока — `python3 tools/armor_preview.py` →
  `preview/armor/` (`all_sets.png`, `<комплект>_views.png`: спереди, сзади, 3/4, ночью со свечением;
  `<комплект>_glow.gif` — анимация свечения, посчитанная той же формулой, что в шейдере; `icons.png`).
- Свечение — пиксели с альфой ровно 251 + `pack/assets/minecraft/shaders/core/entity.fsh` (только конвейеры брони).
  Проверка, что маркер не задевает чужое: `python3 tools/armor_shader_check.py <client-1.21.11.jar> <паки…>`.

## reforged_armor.yml — «перекованная» 3D-броня на шейдере (7 комплектов × 4 части)

Комплекты `leather`, `chainmail`, `copper`, `iron`, `golden`, `diamond`, `netherite`; id —
`azerus_reforged_<комплект>_<часть>` (`helmet`, `chestplate`, `leggings`, `boots`), имена вида «Перекованный незеритовый
нагрудник», основа — ванильная броня того же материала (медная — `COPPER_*`, она есть с 1.21.9), без блеска
(`enchantment_glint_override: false`). Выдать: `/nexo give <ник> azerus_reforged_netherite_chestplate`.
Характеристики — ванильные от основы (свои можно добавить в `core-python/scripts/custom_armor/sets.yml`).

**Как это работает (MythicArmors-подобно, своя реализация по открытой технике objmc, MIT):**
- **Шлем** — обычная 3D-модель предмета (`models/item/reforged/<комплект>_helmet.json`, повороты кубов по трём осям,
  как разрешает 1.21.11; `display.head` scale 1.6 — модель стоит на голове ровно как в Blockbench). У шлема
  `equippable` со слотом `HEAD` **без** `asset_id` (иначе клиент рисует броню, а не предмет — как `forest_helmet` у Nexo).
  Шейдер для шлема не нужен — виден всем.
- **Нагрудник, поножи, ботинки** — `equippable.asset_id: azerus:reforged_<комплект>` и своё описание
  `pack/assets/azerus/equipment/reforged_<комплект>.json` (без `CustomArmor`: Nexo генерирует equipment только для
  `CustomArmor.id`, наш файл кладётся в пак как есть). В нём слои `humanoid` (нагрудник + ботинки) и
  `humanoid_leggings` (поножи):
  - слой `00` — обычная 2D-броня 64x32 (раскладка ванильной брони; спроецирована из 3D-модели) + магический пиксель
    (0,0) `A3 3D 7E` с альфой 16. Её видят клиенты без шейдера (Iris с шейдерпаком, macOS, старые драйверы);
  - слои `01..NN` — **данные**: ряды 0..127 — цветовой атлас (текстура модели, непрозрачное = альфа 20), ряд 128 —
    заголовок, ниже — по записи на ряд: 4 угла грани (xyz, 24 бита), 4 UV, флаги. Вся альфа ≤ 24, поэтому без
    нашего шейдера (ALPHA_CUTOUT 0.1) такие слои не видны совсем.
- **Шейдер** `pack/assets/minecraft/shaders/core/entity.vsh` + `include/armor3d.glsl` (только конвейеры брони):
  каждый ванильный квад брони (6 граней × кость) превращается в одну грань модели. Позу конечности шейдер берёт из
  четырёх вершин самого квада (расширение `GL_KHR_shader_subgroup_quad`, как в objmc PR #105): по UV угадывает,
  какая это грань ванильной коробки и какой угол, по двум рёбрам и нормали восстанавливает поворот × масштаб части
  (точно, включая масштаб игрока/атрибута scale/детей), левые руку/ногу узнаёт по зеркальности. Если квад не сошёлся
  (другой порядок вершин у драйвера, нет расширения) — эта грань остаётся ванильной: слой данных невидим, 2D-слой
  виден. `entity.fsh`: у 3D-граней альфа 0 — дырка, иначе непрозрачно, задние грани отбрасываются (у плоских
  кубов — двусторонние), флаг «светится» — без теней и света. Свечение 251 (custom_armor) не тронуто.
- **Кузнечный стол**: отделка (trim) легла бы на ванильную коробку поверх 3D — поэтому `PrepareSmithingEvent` в
  `core-python/scripts/custom_armor/main.py` для `azerus_reforged_*` даёт пустой результат.

**Новый комплект из .bbmodel** (Blockbench, формат Generic/free, одна текстура до 128×128, кости-группы верхнего
уровня: `helmet`, `torso`, `right_arm`, `left_arm`, `hips`, `right_leg`, `left_leg`, `right_boot`, `left_boot`;
шарниры как у шаблона: тело/бёдра (0,24,0), руки (±5,22,0), ноги/ботинки (±2,12,0); вложенные группы с любыми
поворотами едут на своей верхней кости; `inflate`, повороты кубов и групп запекаются):
1. `source/reforged/reforged_<комплект>_armor.bbmodel`; добавить строку в `SETS` в `tools/armor3d_build.py`.
2. `python3 -I tools/armor3d_build.py [<комплект>]` — модели, текстуры слоёв, equipment, иконки, `items/reforged_armor.yml`,
   `preview/reforged/budget.json`.
3. `python3 -I tools/armor3d_preview.py` — `preview/reforged/<комплект>.png` (4 позы спереди/сзади + 2D-запаска),
   `contact_sheet.png`, `fallback_2d.png` (развёртки слоёв 00), `icons.png`. Рендер идёт **той же математикой, что
   шейдер** (Python-копия `armor3d_codec.shade_quad`).
4. `python3 -I tools/armor3d_check.py` — маркеры не совпадают ни с чем в ванили/паках прода/репозитория, правила альфы,
   запись↔модель туда-обратно, случайные позы (ошибка вершин < 1e-3 px), копии шейдера одинаковы.
   `python3 -I tools/armor3d_glsl_check.py` — компиляция и линковка entity.vsh/fsh glslangValidator во всех
   конвейерах core/entity с subgroup и без.
Бюджет: на кость 6 граней на слой; слоёв в списке = max по костям списка ⌈граней/6⌉ (netherite: 18 humanoid, 6
leggings; нагрудник 1368 вершин). Пустые места слоя шейдер сворачивает в точку вне экрана.

Деплой (только по «заливай»): `pack/assets/azerus/{equipment,models/item/reforged,textures/item/reforged,
textures/entity/equipment}` → `/plugins/Nexo/pack/assets/azerus/…`; `pack/assets/minecraft/shaders/core/entity.vsh`,
`entity.fsh`, `include/armor3d.glsl` → `/plugins/Nexo/pack/assets/minecraft/shaders/…`; `items/reforged_armor.yml` →
`/plugins/Nexo/items/`; `nexo reload all`; запрет кузнечного стола — `core-python/scripts/custom_armor/main.py`
(`/python reload custom_armor`, когда core-python будет на проде).
