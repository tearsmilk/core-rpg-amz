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
  раздел `display` (положение предмета в руке/на земле/в рамке, Java-формат) из JSON.

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
```

- `ak47.bbmodel` — модель от первого лица (77 костей, 16 анимаций, текстура 256×256 вшита): её рисует клиентский
  модуль guns. Убраны кости, которые TACZ показывает только с обвесами (удлинённые магазины, крепление прицела,
  другие приклады), и кубы-заглушки рук.
- `ak47_lod.bbmodel` — упрощённая модель TACZ (текстура 64×64) для предмета hoitems: в руке, от третьего лица, на
  земле. `ak47_display.json` — положения для неё (подбирались по точкам `thirdperson_hand`, `ground`, `fixed` модели).

Проверка: модуль guns при загрузке пишет `guns: модель guns/ak47.bbmodel — костей 77, анимаций 16`.
