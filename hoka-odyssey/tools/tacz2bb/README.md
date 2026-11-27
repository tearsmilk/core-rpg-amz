# tacz2bb — Bedrock geo (TACZ) → Blockbench .bbmodel

Наш конвертер (код TACZ не используется) для ассетов модуля `guns`: переводит модель Bedrock (`*.geo.json`) и её
анимации (`*.animation.json`) в `.bbmodel` (формат 4.10, «free»), который читает ядро мода (`BbModel`/`Pose`) и
модуль hoitems. Это **только смена формата** — геометрия, UV и пиксели текстуры не меняются (лицензия ассетов TACZ
CC BY-NC-ND 4.0 разрешает техническую конвертацию формата; см. `guns/assets-tacz/NOTICE.md`).

Правила осей — как у импорта Bedrock в самом Blockbench (подробно — в начале `geo2bbmodel.py`). Дополнительно:
- `pre`/`post` ключа анимации, если различаются, — два ключа (t − 0,0001 и t): ядро читает только `data_points[0]`;
- звуки анимации (`sound_effects`) — в поле `hoka_sounds` [{time, effect}] (его читает клиентский модуль guns:
  звуки перезарядки в такт анимации; Blockbench и ядро поле не знают);
- `--drop` — не переносить кости (и всё в них), `--hide` — перенести, но перечислить в поле `hoka_hidden` (клиентский
  модуль guns их не рисует — так TACZ без обвесов прячет удлинённые магазины, крепления, другие приклады), `--strip-cubes` — оставить кость без кубов, `--display` — добавить
  раздел `display` (положение предмета в руке/на земле/в рамке, Java-формат) из JSON;
- `--attach GEO PNG BONE` (и `--attach-name`) — поставить модель обвеса TACZ (прицел) в кость `BONE` оружия так, как
  её ставит сам TACZ при рисовании: кости и кубы обвеса не меняются, только сдвигаются в точку кости; у обвеса своя
  текстура (вторая в файле, со своим `uv_width`/`uv_height`). Имена костей обвеса, совпавшие с именами костей оружия,
  получают приставку `<attach-name>_` (у AWP и прицела есть кость `views` — у прицела она `scope_views`);
- `--view DISPLAY [DISPLAY_ПРИЦЕЛА]` — поле `hoka_view` {`model_fov`, `zoom_model_fov`}: угол обзора модели от
  первого лица из display оружия TACZ (нет — 70, как у TACZ), с прицелом в прицеле — `views_fov` прицела (так берёт
  TACZ); его читает клиентский модуль guns.

## Команды (как сделаны файлы в `guns/assets-tacz/models/`)

`P` — папка пака в клоне TACZ: `src/main/resources/assets/tacz/custom/tacz_default_gun`. Запускать `python3 -I`
(файлы пака — чужие данные).

```bash
python3 -I geo2bbmodel.py P/assets/tacz/geo_models/gun/ak47_geo.json P/assets/tacz/textures/gun/uv/ak47.png ak47.bbmodel \
    --anim P/assets/tacz/animations/ak47.animation.json \
    --hide mag_extended_1,mag_extended_2,mag_extended_3,mount,oem_stock_heavy,oem_stock_light,ar_stock_adapter \
    --strip-cubes lefthand_pos,righthand_pos --name ak47 \
    --view P/assets/tacz/display/guns/ak47_display.json
python3 -I geo2bbmodel.py P/assets/tacz/geo_models/gun/lod/ak47.json P/assets/tacz/textures/gun/lod/ak47.png ak47_lod.bbmodel \
    --name ak47_lod --display ak47_display.json
python3 -I geo2bbmodel.py P/assets/tacz/geo_models/gun/ai_awp_geo.json P/assets/tacz/textures/gun/uv/ai_awp.png ai_awp.bbmodel \
    --anim P/assets/tacz/animations/ai_awp.animation.json \
    --drop sight --hide mag_extended_1,mag_extended_2,mag_extended_3 --strip-cubes lefthand_pos,righthand_pos --name ai_awp \
    --attach P/assets/tacz/geo_models/attachment/scope_standard_8x_geo.json \
             P/assets/tacz/textures/attachment/uv/scope_standard_8x.png scope_pos --attach-name scope \
    --view P/assets/tacz/display/guns/ai_awp_display.json P/assets/tacz/display/attachments/scope_standard_8x_display.json
python3 -I geo2bbmodel.py P/assets/tacz/geo_models/gun/lod/ai_awp.json P/assets/tacz/textures/gun/lod/ai_awp.png ai_awp_lod.bbmodel \
    --name ai_awp_lod --display ai_awp_display.json \
    --attach P/assets/tacz/geo_models/attachment/lod/scope_standard_8x.json \
             P/assets/tacz/textures/attachment/lod/scope_standard_8x.png scope_pos --attach-name scope
python3 -I geo2bbmodel.py P/assets/tacz/geo_models/gun/glock_17_geo.json P/assets/tacz/textures/gun/uv/glock_17.png glock_17.bbmodel \
    --anim P/assets/tacz/animations/glock_17.animation.json \
    --hide mag_extended_1,mag_extended_2,mag_extended_3 --strip-cubes lefthand_pos,righthand_pos --name glock_17 \
    --view P/assets/tacz/display/guns/glock_17_display.json
python3 -I geo2bbmodel.py P/assets/tacz/geo_models/gun/lod/glock_17.json P/assets/tacz/textures/gun/lod/glock_17.png glock_17_lod.bbmodel \
    --name glock_17_lod --display glock_17_display.json
# M4A1, M16A1, Desert Eagle: модель от первого лица и упрощённая (LOD) для hoitems
python3 -I geo2bbmodel.py P/assets/tacz/geo_models/gun/m4a1_geo.json P/assets/tacz/textures/gun/uv/m4a1.png m4a1.bbmodel \
    --anim P/assets/tacz/animations/m4a1.animation.json \
    --hide mag_extended_1,mag_extended_2,mag_extended_3,handguard_tactical,oem_side_cover,oem_grip_cover \
    --strip-cubes lefthand_pos,righthand_pos --name m4a1 --view P/assets/tacz/display/guns/m4a1_display.json
python3 -I geo2bbmodel.py P/assets/tacz/geo_models/gun/lod/m4a1.json P/assets/tacz/textures/gun/lod/m4a1.png m4a1_lod.bbmodel \
    --name m4a1_lod --display m4a1_display.json
python3 -I geo2bbmodel.py P/assets/tacz/geo_models/gun/m16a1_geo.json P/assets/tacz/textures/gun/uv/m16a1.png m16a1.bbmodel \
    --anim P/assets/tacz/animations/m16a1.animation.json \
    --hide mag_extended_1,mag_extended_2,mag_extended_3,handguard_tactical,oem_handguard_a2,mount \
    --strip-cubes lefthand_pos,righthand_pos --name m16a1 --view P/assets/tacz/display/guns/m16a1_display.json
python3 -I geo2bbmodel.py P/assets/tacz/geo_models/gun/lod/m16a1.json P/assets/tacz/textures/gun/lod/m16a1.png m16a1_lod.bbmodel \
    --name m16a1_lod --display m16a1_display.json
python3 -I geo2bbmodel.py P/assets/tacz/geo_models/gun/deagle_geo.json P/assets/tacz/textures/gun/uv/deagle.png deagle.bbmodel \
    --anim P/assets/tacz/animations/deagle.animation.json \
    --hide mag_extended_1,mag_extended_2,mag_extended_3 --strip-cubes lefthand_pos,righthand_pos --name deagle \
    --view P/assets/tacz/display/guns/deagle_display.json
python3 -I geo2bbmodel.py P/assets/tacz/geo_models/gun/lod/deagle.json P/assets/tacz/textures/gun/lod/deagle.png deagle_lod.bbmodel \
    --name deagle_lod --display deagle_display.json
# M1897: упрощённой модели у TACZ нет — предмет hoitems из полной (без штыка и патрона в правой руке)
python3 -I geo2bbmodel.py P/assets/tacz/geo_models/gun/m1897_geo.json P/assets/tacz/textures/gun/uv/m1897.png m1897.bbmodel \
    --anim P/assets/tacz/animations/m1897.animation.json \
    --hide bayonet --strip-cubes lefthand_pos,righthand_pos --name m1897 --view P/assets/tacz/display/guns/m1897_display.json
python3 -I geo2bbmodel.py P/assets/tacz/geo_models/gun/m1897_geo.json P/assets/tacz/textures/gun/uv/m1897.png m1897_item.bbmodel \
    --drop attachment_adapter,bullet_and_righthand --strip-cubes lefthand_pos --name m1897_item --display m1897_display.json
```

Положения `display` новых (`m4a1`, `m16a1`, `deagle`, `m1897`) — по тем же правилам. Остальные файлы AWP, Glock,
M4A1, M16A1, Desert Eagle и M1897 (силуэты HUD, иконки, патроны, звуки, `pistol_default.player_animation.json`) — копии из
пака без изменений (список — в `guns/assets-tacz/NOTICE.md`).

- `ak47.bbmodel` — модель от первого лица (77 костей, 16 анимаций, текстура 256×256 вшита): её рисует клиентский
  модуль guns. Кости, которые TACZ показывает только с обвесами (удлинённые магазины, крепление прицела, другие
  приклады), остаются в модели, но перечислены в поле `hoka_hidden` (`--hide`): клиентский модуль их не рисует, пока
  обвеса нет; кубы-заглушки рук убраны.
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
