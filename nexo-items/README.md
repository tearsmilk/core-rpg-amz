# nexo-items — свои предметы Nexo

- `items/*.yml` → `plugins/Nexo/items/`, `pack/assets/<ns>/` → `plugins/Nexo/pack/assets/<ns>/`, затем `nexo reload all`.
- `source/` — исходные модели Blockbench; `tools/bbjava2json.py` переводит «Java Block/Item» .bbmodel в JSON-модель
  и текстуру: `python3 tools/bbjava2json.py <model.bbmodel> pack/assets <namespace> <id>`.

## azerus_weapons.yml — тестовое оружие (12 шт.)

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
| azerus_moon_staff | Посох Лунного Странника (объёмный, магия core_magic) | алмазный меч |

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

## Посох Лунного Странника (`azerus_moon_staff`)

Маг держит ПКМ 3 с — перед ним раскрывается голубой магический круг; отпускает — из круга бьёт белый луч
(10 урона всем на пути, до 29 блоков). Логика — Python-система `core-python/scripts/core_magic` (её README);
здесь — модель, текстуры, модели эффектов и шейдерный хук.

- **Модель** (42 куба, повороты по одной оси на ±22.5/±45°, координаты −16..32): длина 46 px (y −15..31, в руке ×0.66
  ≈ 1.9 блока). Снизу золотой подток с острым кончиком и ободком, тёмно-багровое древко 2×2 с двумя золотыми кольцами
  хвата, красная лента под навершием (4 витка через один повёрнуты на ±22.5° вокруг Y, два хвоста спереди), ступенчатый
  золотой воротник. Навершие: золотой полумесяц — открытое кольцо из хорд через 22.5° от воротника вверх по левой
  стороне через верх, остриём вправо-вверх (разрыв справа, слева полоса толще), оправа-восьмиугольник (4 бруска),
  4 лапки к полумесяцу и большой круглый красный самоцвет из 4 скрещённых брусков (`light_emission` 10, без затенения,
  блик — 1 тексель слева сверху).
- **Текстура 64×64** раскрашивается кодом по точке модели (как у косы): пять тонов на материал (дерево, золото, ткань,
  самоцвет), свет сверху слева, без шума. Одноцветные грани берут общий «образец» цвета, одинаковые грани — общий
  прямоугольник, остальное уложено «по горизонту» с рамкой 1 тексель.
- **Вид в руке** считается (`aim` в скрипте): от третьего лица посох стоит по диагонали, верх и навершие вперёд; от
  первого лица навершие справа вверху и не закрывает прицел (масштаб 0.40). В слоте — по диагонали, самоцвет справа
  вверху.
- **Колдовство**: у предмета ванильный компонент `consumable` (3600 с, анимация `BOW`, без звука и частиц) — так
  получается удержание ПКМ с позой лука. Определение `items/moon_staff.json`: в слоте/на земле/в рамке/на голове —
  `moon_staff`, в руках при использовании (`using_item`) — `moon_staff_cast` (свои копии кубов: самоцвет светится 15,
  поза «колдует»: от третьего лица почти вертикально с наклоном вперёд ~20°, самоцвет над головой впереди лица; от
  первого лица — вперёд-вверх, самоцвет правее и выше центра экрана, внутри круга).
- **Эффекты без предмета Nexo** (ItemStack строит сервер: бумага + `item_model` + цвет `custom_model_data`):
  `azerus:moon_staff_circle` (4 плоскости: кольцо, руны, гексаграмма, ядро) и `azerus:moon_staff_beam` (звезда из 8
  плоскостей через 22.5° вдоль +Z от z=8 до 16 + вспышка у начала). Спрайты — `textures/item/moon_staff_fx/*.png`,
  128×128, голубая гамма, руны — свои абстрактные знаки. Вращение, раскрытие рун по заряду, пульс, бег штрихов по лучу
  и мягкий край считает **шейдер** по `GameTime` — сервер ничего не шлёт каждый тик.
- **Шейдерный хук** — в общем `rendertype_item_entity_translucent_cull` (см. «Шейдер» ниже), код — include
  `pack/assets/azerus/shaders/include/moonstaff_{vertex,fragment}.glsl`.

### Шейдер: маркеры и безопасность

Два ключа, оба должны совпасть, иначе работает ванильный путь без изменений:
- **ключ A (вершина)**: цвет `custom_model_data` = (R, 233, 253): G = 233 и B = 253 ровно, R ≤ 250 — параметр
  (круг: заряд × 250; луч: длина в блоках × 8), и только при яркости дисплея 15/15 (UV2 = (240, 240)). С соседями не
  пересекается: rezusmenu — R = B = 254, core-portals и core-skyrift — R = 255;
- **ключ B (текстура)**: тексель (0,0) спрайта = (177,126,90,3), тексель (1,0) = (K,90,126,3), K — вид 1..9. Альфа 3
  ниже ванильного порога 0.1, поэтому без шейдера подпись невидима. Скан всех PNG репозитория, снимка прода, всех
  zip/jar (в том числе ванильного клиента) — ни одного такого текселя, кроме наших 9 (а текселей с одной лишь альфой 3
  там больше тысячи — поэтому подпись из 4 каналов).
- Хук только пишет свои выходы в `.vsh` (вершина и цвет не меняются) и в `.fsh` срабатывает лишь при обоих ключах —
  обычные предметы, курсор rezusmenu, порталы и разлом рисуются байт-в-байт как раньше (проверка (b) снимает блоки и
  сверяет md5 с продом). GLSL 330 без расширений (macOS GL 4.1).
- Без шейдера (Iris, старый пак) видна обычная картинка спрайтов с оттенком от голубого до бело-голубого — круг всё
  равно выглядит как круг.

Файлы и инструменты (`python3 -I` из `nexo-items`):
- `tools/moon_staff.py` — собирает .bbmodel, модель (через `bbjava2json.py`), текстуру 64×64, `moon_staff_cast`,
  `items/moon_staff.json`, спрайты и модели эффектов (`tools/moonstaff_fx.py`) и превью в `preview/moon_staff/`;
  при запуске перезаписывает `source/Посох-лунного-странника.bbmodel`.
- `tools/moonstaff_fx.py` — спрайты, модели эффектов и Python-копия шейдера (превью и проверка).
- `tools/moonstaff_hook.py apply|strip|status` — вставить/убрать блоки хука во всех 5 копиях общего шейдера.
- `tools/moonstaff_check.py [--pack pack.zip] [--quick]` — проверка (a)–(e) и `armor3d_check.py`, код выхода ≠ 0 при
  ошибке.
- `preview/moon_staff/`: `moon_staff_{gui,front,angles,night,thirdperson,firstperson}` — основа;
  `moon_staff_cast_{thirdperson,firstperson,night}` — поза «колдует» (от первого лица — с кругом впереди при заряде 0.5
  и 1.0; натяжение лука 0 и 1); `moon_staff_circle_charge` — круг при заряде 0.15/0.5/1.0 в моменты 0 и 0.4 с;
  `moon_staff_beam` — луч сбоку при длине 2/15/29 блоков; `moon_staff_flare`, `moon_staff_fx_sprites`.

Деплой (только по «заливай»), строго в этом порядке:
1. `pack/assets/azerus/shaders/include/moonstaff_*.glsl`, `textures/item/moon_staff*.png`, `textures/item/moon_staff_fx/`,
   `models/item/moon_staff*.json`, `items/moon_staff*.json` → `plugins/Nexo/pack/assets/azerus/`. **Include — раньше
   (или вместе с) общих файлов**: без include не соберётся весь общий шейдер, и пропадут все предметы на дисплеях.
2. Общий `minecraft/shaders/core/rendertype_item_entity_translucent_cull.{vsh,fsh}` (копии из `core-skyrift/pack`, они
   = `core-portals/pack` = `rezusmenu/pack`) → `plugins/Nexo/pack/assets/minecraft/shaders/core/`.
3. `items/azerus_weapons.yml` → `plugins/Nexo/items/`, `nexo reload all`.
4. `python3 -I tools/moonstaff_check.py --pack pack.zip` и `python3 -I tools/armor3d_check.py --pack pack.zip`.
5. Python-система: `core-python/scripts/core_magic/` → `systems/core-python/scripts/core_magic/`, `/python load core_magic`.

Не проверено в клиенте: вид модели и поз в игре, шейдер на настоящих видеокартах (проверено glslang и Python-копией),
мипмапы атласа предметов (ореол круга берётся с мип-уровня 2; если у атласа предметов мипов нет — ореол просто равен
рисунку). Ключи компонента `consumable` в Nexo не сверены с docs.nexomc.com (сайт недоступен из среды сборки) — если
Nexo их не примет, core_magic восстанавливает компонент сам.

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
- Свечение — пиксели с альфой ровно 251 + `entity_shaders/azerus_entity_1_21_11/assets/minecraft/shaders/core/entity.fsh`
  (только конвейеры брони; оверлей только для 1.21.11 — см. «Шейдеры сущностей» ниже).
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
- **Шейдер** `entity_shaders/azerus_entity_1_21_11/assets/minecraft/shaders/core/entity.vsh` + `include/armor3d.glsl`
  (только конвейеры брони): каждый ванильный квад брони (6 граней × кость) превращается в одну грань модели. Позу
  конечности шейдер берёт из четырёх вершин самого квада, собранных с соседних линий subgroup (`az3d_gather`:
  `GL_KHR_shader_subgroup_shuffle`, иначе `GL_ARB_shader_ballot` — Mesa даёт KHR-макросы только с `#version 430`, а
  ARB уже при 330; `#version` поднимать нельзя — macOS GL 4.1 потеряла бы все сущности). По UV угадывает,
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
поворотами едут на своей верхней кости; `inflate`, повороты кубов и групп запекаются). Записи считаются от
ВАНИЛЬНОГО шарнира части (ноги ±1.9, а не ±2 шаблона), поэтому в покое каждая грань стоит в игре ровно там, где в
Blockbench (`armor3d_check` это проверяет):
1. `source/reforged/reforged_<комплект>_armor.bbmodel`; добавить строку в `SETS` в `tools/armor3d_build.py`.
2. `python3 -I tools/armor3d_build.py [<комплект>]` — модели, текстуры слоёв, equipment, иконки, `items/reforged_armor.yml`,
   `preview/reforged/budget.json`.
3. `python3 -I tools/armor3d_preview.py` — `preview/reforged/<комплект>.png` (4 позы спереди/сзади + 2D-запаска),
   `contact_sheet.png`, `fallback_2d.png` (развёртки слоёв 00), `icons.png`. Рендер идёт **той же математикой, что
   шейдер** (Python-копия `armor3d_codec.shade_quad`).
4. `python3 -I tools/armor3d_check.py` — маркеры не совпадают ни с чем в ванили/паках прода/репозитория, правила альфы,
   запись↔модель туда-обратно, случайные позы (ошибка вершин < 1e-3 px), копии шейдера одинаковы.
   `python3 -I tools/armor3d_glsl_check.py [--mesa]` — компиляция и линковка entity.vsh/fsh glslangValidator во
   всех 15 конвейерах core/entity × режимы сбора khr / arb / без расширений (`--mesa` — ещё и настоящим драйвером
   через moderngl + EGL, печатает, какой сбор драйвер выбрал сам).
Бюджет: на кость 6 граней на слой; слоёв в списке = max по костям списка ⌈граней/6⌉ (netherite: 18 humanoid, 6
leggings; нагрудник 1368 вершин). Пустые места слоя шейдер сворачивает в точку вне экрана.

Где 3D включается: только там, где драйвер даёт KHR shuffle или ARB ballot в вершинном шейдере при `#version 330`
(NVIDIA; Mesa radeonsi/iris/Steam Deck — через ARB; проверено на Mesa 25.2 llvmpipe/zink). macOS, Iris с
шейдерпаком, драйверы без расширений — 2D-слой `00`. Известные ограничения (приняты):
- слой `00` прячет грань сам, а слой данных решает за себя (отдельная отрисовка): если GPU в разных отрисовках
  группирует линии по-разному, может остаться дырка — проверять в настоящем клиенте;
- зомби-жители: их модель брони строит ноги с деформацией +0.1 (у игрока −0.1) → поножи/ботинки на ногах на 5–7%
  крупнее и сдвинуты; стойки для брони считаются верно;
- контур свечения (эффект Glowing) рисует ванильный `rendertype_outline` по альфе ≠ 0: обводится 2D-коробка брони
  (куски слоёв данных), а не 3D-модель; общий шейдер контура не трогаем.

### Шейдеры сущностей — отдельный внешний пак, только 1.21.11

`entity_shaders/` — мини-пак: `pack.mcmeta` с одним оверлеем `azerus_entity_1_21_11` (формат ровно 75) и в нём
`core/entity.vsh` (хук меню rezusmenu + armor3d), `core/entity.fsh` (armor3d + свечение 251), `include/armor3d.glsl`.
Единственная копия на весь сервер (в `pack/assets/minecraft/shaders` ни одного пака репозитория их быть не должно —
это проверяет `armor3d_check.py`). Зачем: через ViaVersion заходят клиенты 26.x, у них `core/entity` другой (26.1/26.2 —
`sample_lightmap`, `DISSOLVE`; 26.3 — `#include`, `layout(location)`), наш вариант 1.21.11 в базовом `assets/` сломал
бы им все сущности. Так же сделаны оверлеи MythicHUD в `hl-resourcepack.zip`, которые Nexo сохраняет.
`python3 -I tools/entity_shaders_pack.py build` → `build/azerus-entity-shaders.zip` (детерминированный, печатает sha256).

Деплой (только по «заливай»):
1. Бэкап с прода: `/plugins/Nexo/pack/assets/minecraft/shaders/core/entity.vsh`, `entity.fsh` (старые версии в базе).
2. `pack/assets/azerus/{equipment,models/item/reforged,textures/item/reforged,textures/entity/equipment}` →
   `/plugins/Nexo/pack/assets/azerus/…`; `items/reforged_armor.yml` → `/plugins/Nexo/items/`.
3. `tools/entity_shaders_pack.py build` → залить `build/azerus-entity-shaders.zip` в
   `/plugins/Nexo/pack/external_packs/` (сверить sha256); **удалить** с прода базовые
   `/plugins/Nexo/pack/assets/minecraft/shaders/core/entity.vsh` и `entity.fsh` (и `include/armor3d.glsl`, если есть).
   `rendertype_item_entity_translucent_cull.*` не трогать.
4. `nexo reload all`; в логе «Finished generating resourcepack», без ошибок.
5. Скачать собранный `/plugins/Nexo/pack/pack.zip` (> 5 МБ — через `files/compress` или ссылку `files/download`) и
   `python3 -I tools/armor3d_check.py --pack pack.zip`: пиксели всех `reforged_*.png` и иконок == репозиторий,
   модели шлемов (elements/rotation) == репозиторий, слои из zip проходят туда-обратно и математику шейдера,
   оверлей `azerus_entity_1_21_11` в `pack.mcmeta` (формат 75) и файлы на месте, в базе `core/entity.*` нет,
   оверлеи MythicHUD живы. Если Nexo выкинул оверлей — вернуть файлы из бэкапа шага 1 (свечение/меню как раньше,
   без 3D), zip убрать, `nexo reload pack`, разобраться.
6. Зайти клиентом 1.21.11: свечение custom_armor, иконки меню rezusmenu, 3D-броня (NVIDIA/AMD/Intel); клиентом 26.x —
   сущности ванильные и целые.
7. Запрет кузнечного стола — `core-python/scripts/custom_armor/main.py` (`/python reload custom_armor`, когда
   core-python будет на проде).
