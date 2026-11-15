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

Модель объёмная: 87 кубов, все с поворотом по одной оси на 0/±22.5/±45°, координаты в пределах −16..32.
- **Лезвие** — дуга из отрезков через 11.25°. В каждом отрезке три слоя по радиусу: обух 3 px толщиной, середина
  2 px, режущая кромка 1 px. Получается скос к кромке. Кромка и искры светятся (`light_emission` 15, без затенения
  граней), середина — `light_emission` 4. Тёмные «натёки» обуха сделаны отдельными кубами.
- **Древко** — ломаная из отрезков 2×2 с узлами на изломах, внизу комель с корешком. Через голову проходит светлая
  ветка с сучком, ниже — красная обмотка с перевязками.
- **Текстура** (128×128) раскрашивается кодом: цвет каждого текселя берётся из точки модели, куда он попадает с
  учётом поворота куба, поэтому на перекрытиях отрезков цвета совпадают. Палитра снята с референса, пять тонов со
  сдвигом оттенка, свет сверху слева.
- **Вид в руке** считается, а не подбирается на глаз (`aim` в скрипте). От третьего лица коса стоит почти вертикально,
  лезвие над головой и смотрит вперёд, хват ниже обмотки. От первого лица она наклонена вверх-влево. В слоте и рамке
  модель вписана в 16×16 с наклоном 10°.
- **Кадры замаха** `crimson_scythe_swing_1..3`: та же модель (`parent`), меняется только `display` в руках —
  поворот на −40° (замах назад), +55° (удар) и +105° (проводка) вокруг оси плеч. Определение предмета
  `items/crimson_scythe.json`: в GUI, на земле, в рамке и на голове показывается основа, в руках — `select` по
  строке `custom_model_data` (`cw_swing_N` ставит система core-weapons). `hand_animation_on_swap: false`.

Файлы:
- `source/Багровая-коса.bbmodel` — проект Blockbench (java_block), его можно править.
- `tools/crimson_scythe.py` — собирает .bbmodel, JSON (через `bbjava2json.py`, теперь он переносит
  `light_emission` и `shade`), PNG, кадры и определение предмета.
- `tools/render_model.py` — программный рендер JSON-модели для проверки:
  `python3 tools/render_model.py pack/assets azerus:item/crimson_scythe preview crimson_scythe`.
- `preview/crimson_scythe_*.png`: `front` (спереди), `angles` (0/35/90/180°), `gui` (иконка в слоте), `night`
  (светящиеся части в темноте), `thirdperson` (на манекене с трёх сторон), `firstperson`, `swing_frames` (кадры
  замаха от первого и третьего лица).

После правки .bbmodel в Blockbench: `python3 tools/bbjava2json.py source/Багровая-коса.bbmodel pack/assets azerus crimson_scythe`.
Учтите, что `crimson_scythe.py` при запуске перезаписывает .bbmodel.

Деплой: `pack/assets/azerus/` → `plugins/Nexo/pack/assets/azerus/` (models/item/crimson_scythe*.json,
textures/item/crimson_scythe.png, items/crimson_scythe.json), `items/azerus_weapons.yml` → `plugins/Nexo/items/`,
затем `nexo reload all`. Эффекты (частицы, замах, дрожь) даёт система `core-weapons` (`dist/core-weapons.azs` → `systems/`).
