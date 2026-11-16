# custom_armor — кастомная броня (カスタム防具)

Первая Python-система сервера (работает в [core-python](../../README.md)). Код — на Python с японскими именами и
комментариями (`main.py` — события и команда, `items.py` — предметы и характеристики, `bonuses.py` — бонусы
комплектов). Комплекты и все числа — `sets.yml`. Предметы и их вид — Nexo (`nexo-items/items/azerus_armor.yml`).

## Для игроков

Три комплекта по 4 части (шлем, нагрудник, поножи, сапоги). Каждая часть сама по себе даёт броню, твёрдость и
своё свойство; если надеть **2** или **4** части одного комплекта — включается бонус комплекта (в строке над хотбаром
появится «Бонус комплекта …»). Светящиеся руны/угольки на броне переливаются (нужен пакет ресурсов сервера; с
шейдерпаками OptiFine/Iris броня выглядит так же, только без переливания).

| Комплект | Части (основа) | 2 части | 4 части |
|---|---|---|---|
| **Багровый доспех** (`guren`, 紅蓮) — в стиле «Багровой косы» | незерит: 3/8/6/3 брони, по 3 твёрдости, по 10 % от отбрасывания, нагрудник +1 сердце | +8 % урона, огнестойкость | удар поджигает цель (шанс 25 %, 4 с), 12 % нанесённого урона лечит |
| **Доспех лунного льда** (`tsukikori`, 月氷) | алмаз: 3/8/6/3 брони, по 2 твёрдости, скорость +2 % (сапоги +4 %) | ночное зрение | тот, кто ударил владельца, замедлен (Замедление II, 3 с) и покрыт инеем |
| **Доспех дубравы** (`morikashi`, 森樫) | железо: 2/7/5/2 брони, по 1 твёрдости, **каждая часть +1 сердце** | +20 % сопротивления отбрасыванию | регенерация, пока стоишь на траве / мху / подзоле / мицелии |

## Для администраторов

```
/carmor give <комплект> [часть|all] [игрок]     выдать (часть: helmet, chestplate, leggings, boots; по умолчанию all)
/carmor list                                     комплекты и бонусы
/carmor reload                                   перечитать sets.yml (и починить надетые предметы онлайн-игроков)
```
Японский псевдоним — `/防具` (Bukkit его принимает, проверено ботом), ещё `/customarmor`. Право — `customarmor.admin`.

- Выдача берёт предмет из Nexo (`NexoItems.itemFromId("azerus_<комплект>_<часть>")`) и вешает на него
  характеристики — модификаторы атрибутов предмета. Они работают как у ванильной брони (видны в подсказке
  «На теле: +8 брони»), даже если система выключена.
- `/nexo give <ник> azerus_guren_chestplate` тоже можно: характеристик у такого предмета нет, система добавит их при
  первом надевании (или при входе игрока). Так же чинятся предметы после правки чисел в `sets.yml` (на предмете
  хранится метка версии `py_custom_armor:stats`) и после того, как Nexo обновил предмет (ItemUpdater).
- Без Nexo (тестовый сервер) выдаётся такой же предмет, собранный вручную: ванильный материал, имя, описание,
  `equippable` с `asset_id azerus:<комплект>`, метка `nexo:id` — всё, кроме внешнего вида, работает так же.
- Бонусы комплекта — временные: модификаторы атрибутов игрока и эффекты (обновляются раз в 2 с). При выгрузке
  системы (`/python unload custom_armor`) или выключении core-python они снимаются сами.

### sets.yml

```yaml
sets:
  guren:
    jp: 紅蓮                                   # имя в коде и логах
    name: "<#d4334a>Багровый доспех"           # для игроков
    material: NETHERITE                        # основа: NETHERITE_HELMET … (прочность, починка)
    pieces:
      chestplate: {name: "...", armor: 8, toughness: 3, knockback: 0.1, health: 2}   # + speed: 0.02
    bonus:
      2: {damage: 0.08, effects: [{type: fire_resistance, level: 1}]}
      4: {ignite_chance: 0.25, ignite_seconds: 4, lifesteal: 0.12}
```
Общие поля бонуса: `damage`, `effects`, `attributes` (`{attribute, amount, operation}`); особые: `ignite_chance` /
`ignite_seconds`, `lifesteal`, `slow_attackers: {seconds, level, freeze}`, `grass_regen: {level, blocks}`. Новый
комплект = секция в `sets.yml` + 4 предмета `azerus_<id>_<часть>` в Nexo + текстуры.

### Где что лежит

| Что | В репозитории | На сервере |
|---|---|---|
| код и `sets.yml` | `core-python/scripts/custom_armor/` | `/systems/core-python/scripts/custom_armor/` |
| предметы Nexo | `nexo-items/items/azerus_armor.yml` | `/plugins/Nexo/items/azerus_armor.yml` |
| слои брони 64x32 и иконки 16x16 | `nexo-items/pack/assets/azerus/textures/item/armor/` | `/plugins/Nexo/pack/assets/azerus/textures/item/armor/` |
| шейдер свечения | `nexo-items/entity_shaders/azerus_entity_1_21_11/assets/minecraft/shaders/core/entity.fsh` (оверлей только для 1.21.11) | внутри `/plugins/Nexo/pack/external_packs/azerus-entity-shaders.zip` (`nexo-items/tools/entity_shaders_pack.py build`) |
| генератор текстур, превью, проверка шейдера | `nexo-items/tools/armor_textures.py`, `armor_preview.py`, `armor_shader_check.py` | — |
| превью | `nexo-items/preview/armor/` (`all_sets.png`, `<комплект>_views.png`, `<комплект>_glow.gif`, `icons.png`) | — |

### Свечение (core-шейдер брони)

Пиксели рун, угольков и прожилок в слоях брони нарисованы с **альфой ровно 251**. Новый `entity.fsh` (ванильный +
один блок) на конвейерах брони (только у них одновременно `ALPHA_CUTOUT`, `NO_OVERLAY` и `PER_FACE_LIGHTING`) рисует
такие пиксели без освещения и с волной яркости по строкам текстуры (период ~2 с, `GameTime`). Общий шейдер
`rendertype_item_entity_translucent_cull` (rezusmenu/core-portals/core-skyrift) **не тронут**; вершинный `entity.vsh`
общий для rezusmenu и nexo-items (там же 3D-броня armor3d, см. `nexo-items/README.md`). Оба файла — только в оверлее
формата 75 (`nexo-items/entity_shaders`): клиенты 26.x через ViaVersion свечения не видят (их core/entity другой). Проверка `python3 nexo-items/tools/armor_shader_check.py <client.jar> <паки…>`: маркер
срабатывает только на 251; в ванильной броне, отделке (trims), паке Nexo с прода и внешних паках нет ни одной текстуры
брони с альфой 251 (альфу 251 имеют только баннеры/щиты — другой конвейер, без `ALPHA_CUTOUT`, — и HUD MythicHUD).
GLSL проверен glslang для всех наборов define. В клиенте не проверялось (боты картинку не видят) — после заливки
посмотреть глазами. Если Nexo при сборке пака (`compress_textures: true`) вдруг изменит альфу, руны просто не будут
светиться — вреда нет.

### Кузнечный стол и 3D-броня

`main.py` слушает `PrepareSmithingEvent`: если основа — предмет Nexo с id на `azerus_reforged_` («перекованная»
3D-броня на шейдере, `nexo-items/items/reforged_armor.yml`), результат пустой — ни отделки (trim лёг бы на ванильную
коробку поверх 3D-модели), ни улучшения до незерита.
