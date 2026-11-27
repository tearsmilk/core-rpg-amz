# Ассеты TACZ в этой папке — чужие, со своей лицензией

**Не наш арт.** Модели, текстуры и звуки в `guns/assets-tacz/` взяты из мода
**TACZ (Timeless and Classics Zero)**, стандартный оружейный пак `tacz_default_gun`: оружие **«АКМ» (`ak47`)**,
**«Accuracy International AWM» (`ai_awp`, у нас «AWP»)** с прицелом **«Scout 4-10x» (`scope_standard_8x`)** и
**«Glock 17» (`glock_17`)**.

- Источник: https://github.com/MCModderAnchor/TACZ (папка `src/main/resources/assets/tacz/custom/tacz_default_gun`)
- Авторы: TACZ Dev Team; художники (Artist) **NekoCrane, Receke, Pos_2333**
- Лицензия ассетов: **CC BY-NC-ND 4.0** (Attribution-NonCommercial-NoDerivatives 4.0 International),
  https://creativecommons.org/licenses/by-nc-nd/4.0/ (полный текст — https://creativecommons.org/licenses/by-nc-nd/4.0/legalcode)
- Код TACZ (GPL-3.0) **не используется**: модули `guns` (сервер и клиент) и конвертер `tools/tacz2bb/` написаны
  нами заново; из TACZ взяты только числа (характеристики оружия, тайминги перезарядки, отдача).

## Условия, которые надо соблюдать

- **NonCommercial** — только некоммерческое использование. Если на сервере есть платные привилегии/донат,
  влияющие на игру, эти ассеты использовать нельзя (заменить своими).
- **Attribution** — этот файл и строка в описании предмета («Модель и звуки: TACZ, CC BY-NC-ND 4.0») должны
  оставаться.
- **NoDerivatives** — модели, текстуры и звуки не редактировать (не перерисовывать, не перекрашивать, не резать
  звуки, не менять форму модели). Новые варианты — только своим артом.

## Что именно здесь и что с этим сделано

| Файл здесь | Оригинал в паке (`assets/tacz/…`) | Что сделано |
|---|---|---|
| `models/ak47.bbmodel` | `geo_models/gun/ak47_geo.json`, `textures/gun/uv/ak47.png`, `animations/ak47.animation.json` | техническая конвертация формата Bedrock geo → Blockbench .bbmodel (CC 4.0 §2(a)(4)); текстура вложена как есть; кости, которые TACZ показывает только с обвесами (`mag_extended_1/2/3`, `mount`, `oem_stock_heavy`, `oem_stock_light`, `ar_stock_adapter`), не перенесены (часть материала, §2(a)(1)(A)); у костей `lefthand_pos`/`righthand_pos` убраны кубы-заглушки рук (это не арт оружия, TACZ их тоже не рисует) |
| `models/ai_awp.bbmodel` | `geo_models/gun/ai_awp_geo.json`, `textures/gun/uv/ai_awp.png`, `animations/ai_awp.animation.json`; прицел — `geo_models/attachment/scope_standard_8x_geo.json`, `textures/attachment/uv/scope_standard_8x.png` | та же конвертация формата; прицел — отдельная модель TACZ, поставленная в кость `scope_pos` винтовки так же, как её ставит при рисовании сам TACZ (кости и кубы прицела не меняются, только переносятся в точку кости; своя текстура — второй в файле). Это сборник двух работ без изменений, а не их переработка. Как TACZ при установленном прицеле, не перенесены механический прицел (`sight`) и удлинённые магазины (`mag_extended_1/2/3`); у костей рук убраны кубы-заглушки |
| `models/ai_awp_lod.bbmodel` | `geo_models/gun/lod/ai_awp.json`, `textures/gun/lod/ai_awp.png`; прицел — `geo_models/attachment/lod/scope_standard_8x.json`, `textures/attachment/lod/scope_standard_8x.png` | конвертация формата; упрощённый прицел — в кости `scope_pos` упрощённой винтовки (как выше); добавлен раздел `display` (по её точкам `thirdperson_hand`, `ground`, `fixed`; в инвентаре — эта модель с прицелом) |
| `models/glock_17.bbmodel` | `geo_models/gun/glock_17_geo.json`, `textures/gun/uv/glock_17.png`, `animations/glock_17.animation.json` | конвертация формата; не перенесены удлинённые магазины (`mag_extended_1/2/3`), у костей рук убраны кубы-заглушки |
| `models/glock_17_lod.bbmodel` | `geo_models/gun/lod/glock_17.json`, `textures/gun/lod/glock_17.png` | конвертация формата; добавлен раздел `display` |
| `animations/pistol_default.player_animation.json` | `player_animator/pistol_default.player_animation.json` | без изменений (анимации тела с пистолетом от третьего лица) |
| `textures/ai_awp_hud.png`, `ai_awp_slot.png`, `glock_17_hud.png`, `glock_17_slot.png` | `textures/gun/hud/…`, `textures/gun/slot/…` | без изменений |
| `textures/338.png`, `9mm.png` | `textures/ammo/slot/338.png`, `9mm.png` | без изменений |
| `sounds/awp_*.ogg` (24), `sounds/glock17_*.ogg` (11) | `tacz_sounds/ai_awp/…`, `tacz_sounds/glock_17/…` | без изменений (выстрел, доставание/убирание, затвор, перезарядка) |
| `models/ak47_lod.bbmodel` | `geo_models/gun/lod/ak47.json`, `textures/gun/lod/ak47.png` | та же конвертация формата; добавлен раздел `display` (положение в руке/на земле/в рамке для нашего движка — как отдельный `display/guns/ak47_display.json` у TACZ и его кость `thirdperson_hand`, сама модель не меняется; от третьего лица — как у TACZ: точка `thirdperson_hand` в руке, ствол вдоль руки, масштаб 0,6) |
| `animations/rifle_default.player_animation.json` | `player_animator/rifle_default.player_animation.json` | без изменений (анимации тела игрока с винтовкой от третьего лица; читает их наш клиентский модуль) |
| `textures/ak47.png`, `ak47_lod.png`, `ak47_slot.png`, `ak47_hud.png` | `textures/gun/uv/ak47.png`, `textures/gun/lod/ak47.png`, `textures/gun/slot/ak47.png`, `textures/gun/hud/ak47.png` | без изменений |
| `textures/762x39.png` | `textures/ammo/slot/762x39.png` | без изменений |
| `textures/common_muzzle_flash.png` | `textures/flash/common_muzzle_flash.png` | без изменений |
| `sounds/ak47_*.ogg` | `tacz_sounds/ak47/ak47_*.ogg` | без изменений (только выстрел, доставание/убирание, перезарядка) |
| `sounds/dry_fire.ogg`, `fire_select.ogg`, `flesh_hit.ogg`, `head_hit.ogg`, `kill.ogg` | `tacz_sounds/*.ogg` | без изменений |

Сетку прицела в круге оптики клиентский модуль рисует из тех же кубов `division` модели прицела (проекция из точки
`scope_view` на экран, текстура прицела как есть) — так же, как её видит TACZ; своей перерисовки нет.

Визуальных правок нет: геометрия, UV и пиксели текстур совпадают с оригиналом (проверка — рендер
`tools/tacz2bb/README.md`). Конвертер повторяется командами из `tools/tacz2bb/README.md`.

---

*English summary.* Assets in this folder come from **TACZ (Timeless and Classics Zero)**, default gun pack, guns
“AKM” (`ak47`), “Accuracy International AWM” (`ai_awp`) with the “Scout 4-10x” scope (`scope_standard_8x`, placed at
the rifle's `scope_pos` bone exactly as TACZ mounts it — a collection of the two unmodified models) and “Glock 17”
(`glock_17`), https://github.com/MCModderAnchor/TACZ — by the TACZ Dev Team, artists NekoCrane, Receke and
Pos_2333 — licensed under **CC BY-NC-ND 4.0** (https://creativecommons.org/licenses/by-nc-nd/4.0/). The `.bbmodel`
files are a technical format conversion (CC 4.0 §2(a)(4)) of the guns' `*_geo.json` / `lod/*.json` and
`*.animation.json` (and of the scope's geo files); parts that TACZ shows only with other attachments or without the
scope were omitted (sharing a part is allowed by §2(a)(1)(A)); textures and sounds are unchanged; no visual edits. Non-commercial use only. No TACZ code is used.
