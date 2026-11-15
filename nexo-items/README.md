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

Выдать: `/nexo give <ник> <id>`.
