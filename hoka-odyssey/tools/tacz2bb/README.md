# tacz2bb — Bedrock geo (TACZ) → Blockbench .bbmodel

Наш конвертер (код TACZ не используется) для ассетов модуля `guns`: переводит модель Bedrock (`*.geo.json`) и её
анимации (`*.animation.json`) в `.bbmodel` (формат 4.10, «free»), который читает ядро мода (`BbModel`/`Pose`) и
модуль hoitems. Это **только смена формата** — геометрия, UV и пиксели текстуры не меняются (лицензия ассетов TACZ
CC BY-NC-ND 4.0 разрешает техническую конвертацию формата; см. `guns/assets-tacz/NOTICE.md`).

Правила осей — как у импорта Bedrock в самом Blockbench (подробно — в начале `geo2bbmodel.py`). Дополнительно:
- `pre`/`post` ключа анимации, если различаются, — два ключа (t − 0,0001 и t): ядро читает только `data_points[0]`;
- звуки анимации (`sound_effects`) — в поле `hoka_sounds` [{time, effect}] (его читает клиентский модуль guns:
  звуки перезарядки в такт анимации; Blockbench и ядро поле не знают);
- `--drop` — не переносить кости (и всё в них), `--strip-cubes` — оставить кость без кубов, `--display` — добавить
  раздел `display` (положение предмета в руке/на земле/в рамке, Java-формат) из JSON;
- `--attach GEO PNG BONE` (и `--attach-name`) — поставить модель обвеса TACZ (прицел) в кость `BONE` оружия так, как
  её ставит сам TACZ при рисовании: кости и кубы обвеса не меняются, только сдвигаются в точку кости; у обвеса своя
  текстура (вторая в файле, со своим `uv_width`/`uv_height`). Имена костей обвеса, совпавшие с именами костей оружия,
  получают приставку `<attach-name>_` (у AWP и прицела есть кость `views` — у прицела она `scope_views`).

## Команды (как сделаны файлы в `guns/assets-tacz/models/`)

`P` — папка пака в клоне TACZ: `src/main/resources/assets/tacz/custom/tacz_default_gun`. Запускать `python3 -I`
(файлы пака — чужие данные).

```bash
python3 -I geo2bbmodel.py P/assets/tacz/geo_models/gun/ak47_geo.json P/assets/tacz/textures/gun/uv/ak47.png ak47.bbmodel \
    --anim P/assets/tacz/animations/ak47.animation.json \
    --drop mag_extended_1,mag_extended_2,mag_extended_3,mount,oem_stock_heavy,oem_stock_light,ar_stock_adapter \
    --strip-cubes lefthand_pos,righthand_pos --name ak47
python3 -I geo2bbmodel.py P/assets/tacz/geo_models/gun/lod/ak47.json P/assets/tacz/textures/gun/lod/ak47.png ak47_lod.bbmodel \
    --name ak47_lod --display ak47_display.json
python3 -I geo2bbmodel.py P/assets/tacz/geo_models/gun/ai_awp_geo.json P/assets/tacz/textures/gun/uv/ai_awp.png ai_awp.bbmodel \
    --anim P/assets/tacz/animations/ai_awp.animation.json \
    --drop mag_extended_1,mag_extended_2,mag_extended_3,sight --strip-cubes lefthand_pos,righthand_pos --name ai_awp \
    --attach P/assets/tacz/geo_models/attachment/scope_standard_8x_geo.json \
             P/assets/tacz/textures/attachment/uv/scope_standard_8x.png scope_pos --attach-name scope
python3 -I geo2bbmodel.py P/assets/tacz/geo_models/gun/lod/ai_awp.json P/assets/tacz/textures/gun/lod/ai_awp.png ai_awp_lod.bbmodel \
    --name ai_awp_lod --display ai_awp_display.json \
    --attach P/assets/tacz/geo_models/attachment/lod/scope_standard_8x.json \
             P/assets/tacz/textures/attachment/lod/scope_standard_8x.png scope_pos --attach-name scope
python3 -I geo2bbmodel.py P/assets/tacz/geo_models/gun/glock_17_geo.json P/assets/tacz/textures/gun/uv/glock_17.png glock_17.bbmodel \
    --anim P/assets/tacz/animations/glock_17.animation.json \
    --drop mag_extended_1,mag_extended_2,mag_extended_3 --strip-cubes lefthand_pos,righthand_pos --name glock_17
python3 -I geo2bbmodel.py P/assets/tacz/geo_models/gun/lod/glock_17.json P/assets/tacz/textures/gun/lod/glock_17.png glock_17_lod.bbmodel \
    --name glock_17_lod --display glock_17_display.json
```

Остальные файлы AWP и Glock (силуэты HUD, иконки, патроны, звуки, `pistol_default.player_animation.json`) — копии из
пака без изменений (список — в `guns/assets-tacz/NOTICE.md`).

- `ak47.bbmodel` — модель от первого лица (77 костей, 16 анимаций, текстура 256×256 вшита): её рисует клиентский
  модуль guns. Убраны кости, которые TACZ показывает только с обвесами (удлинённые магазины, крепление прицела,
  другие приклады), и кубы-заглушки рук.
- `ak47_lod.bbmodel` — упрощённая модель TACZ (текстура 64×64) для предмета hoitems: в руке, от третьего лица, на
  земле. `ak47_display.json` — положения для неё (подбирались по точкам `thirdperson_hand`, `ground`, `fixed` модели).
- `ai_awp.bbmodel` — AWP с прицелом 8x (110 костей, 18 анимаций, две текстуры): механический прицел `sight` не
  перенесён — TACZ его прячет, когда стоит прицел. `ai_awp_lod.bbmodel` — упрощённые AWP и прицел для hoitems (и
  в инвентаре: иконки без прицела у TACZ нет — показывается модель, `gui` в `ai_awp_display.json`).
- `glock_17.bbmodel` (51 кость, 11 анимаций, текстура 1024×1024), `glock_17_lod.bbmodel` — Glock 17.
- Положения `display` для AWP и Glock — по тем же правилам, что у АКМ: `translation = −масштаб·(точка
  thirdperson_hand − (0, 8, 0))` (x у .bbmodel с обратным знаком), на земле — нижняя точка модели на той же высоте.

Проверка: модуль guns при загрузке пишет `guns: модель guns/ak47.bbmodel — костей 77, анимаций 16` (у AWP ещё
`прицел (сетка 68 граней)` — грани сетки прицела для круга оптики). Перегенерация `ak47*.bbmodel` этой версией
конвертера даёт байт-в-байт те же файлы.
