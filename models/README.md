# Модели (ModelEngine)

- `vexa_npc/vexa_npc.bbmodel` — NPC «Векса» (розовая), анимация `idle` (играет сама).
- `vexa_npc/vexa_npc_dark.bbmodel` — тёмный двойник из того же файла (в исходнике обе модели были в одном
  проекте, вторая сдвинута на 0.7 px вниз — вместе они торчали бы друг из друга, поэтому разделены).
- `source/` — исходники как прислали: `vexa_npc.original.bbmodel`, `Janet.bdengine` (BDEngine).
- `tools/prepare_bbmodel.py` — вырезать одну корневую кость из .bbmodel, убрать грани без текстуры, привести
  повороты кубов к допустимым в Java, переименовать анимацию.

На сервере: `/plugins/ModelEngine/blueprints/`, затем `meg reload` (Nexo сам подхватывает пак ModelEngine)
и `nexo reload pack`. Подключение к NPC — `/npc model <id> vexa_npc` (core-npc).

## Janet (BDEngine)

`Janet.bdengine` — 41 голова игрока (item_display) в иерархии костей (голова, тело, ноги, руки, пальцы).
24 скина вшиты в файл, а у 11 голов скин только ссылкой на textures.minecraft.net — из этой среды он закрыт,
поэтому конвертация в .bbmodel пока не сделана.
