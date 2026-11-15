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
