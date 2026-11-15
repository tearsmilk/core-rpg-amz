# Модели (ModelEngine)

- `vexa_npc/vexa_npc.bbmodel` — NPC «Векса» (розовая), анимация `idle` (играет сама).
- `vexa_npc/vexa_npc_dark.bbmodel` — тёмный двойник из того же файла (в исходнике обе модели были в одном
  проекте, вторая сдвинута на 0.7 px вниз — вместе они торчали бы друг из друга, поэтому разделены).
- `source/` — исходники как прислали: `vexa_npc.original.bbmodel`, `Janet.bdengine` (BDEngine).
- `tools/prepare_bbmodel.py` — вырезать одну корневую кость из .bbmodel, убрать грани без текстуры, привести
  повороты кубов к допустимым в Java, переименовать анимацию.

На сервере: `/plugins/ModelEngine/blueprints/`, затем `meg reload` (Nexo сам подхватывает пак ModelEngine)
и `nexo reload pack`. Подключение к NPC — `/npc model <id> vexa_npc` (core-npc).

## Janet (BDEngine → .bbmodel)

`janet/janet.bbmodel` — сконвертировано из `source/Janet.bdengine` скриптом `tools/bdengine2bbmodel.py`:
41 голова игрока (item_display) → кости-головы (куб 8 px + слой шляпы), коллекции BDEngine (голова, тело, ноги,
руки, предплечья, кисти, пальцы и их «согнутые» фаланги) → кости с опорой в суставе — их можно анимировать
в Blockbench. Скины собраны в один атлас 256×256 (24 вшитых в файл + 8 скачанных с textures.minecraft.net,
копии в `janet/skins/`). Рост ≈ 2 блока. Перед модели — −Z (так и в BDEngine: грудь и кисти ближе к −Z, и у ModelEngine); текстуры голов — стандартная развёртка головы игрока (лицо на north/−Z, правая сторона на east/+X). `janet/preview.png` — грубый рендер (спереди/сзади).
Анимаций в исходнике нет — для NPC её можно дорисовать в Blockbench (назвать `idle`).

    python3 tools/bdengine2bbmodel.py source/Janet.bdengine janet/janet.bbmodel --name janet --skins janet/skins --preview janet/preview.png
