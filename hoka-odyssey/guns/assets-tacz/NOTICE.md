# Ассеты TACZ в этой папке — чужие, со своей лицензией

**Не наш арт.** Модели, текстуры и звуки в `guns/assets-tacz/` взяты из мода
**TACZ (Timeless and Classics Zero)**, стандартный оружейный пак `tacz_default_gun`: оружие **«АКМ» (`ak47`)**,
**«Accuracy International AWM» (`ai_awp`, у нас «AWP»)** с прицелом **«Scout 4-10x» (`scope_standard_8x`)** и
**«Glock 17» (`glock_17`)**, **«M4A1» (`m4a1`)**, **«M16A1» (`m16a1`)**, **«Desert Eagle» (`deagle`)** и
**«Winchester M1897» (`m1897`)**, а также **все обвесы** стандартного пака (прицелы, коллиматоры, дульные
устройства, приклады, рукояти, ЛЦУ, магазины и патроны, повышающие планки — модели, иконки, звуки установки; таблица —
в конце файла).

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
| `models/ak47.bbmodel` | `geo_models/gun/ak47_geo.json`, `textures/gun/uv/ak47.png`, `animations/ak47.animation.json` | техническая конвертация формата Bedrock geo → Blockbench .bbmodel (CC 4.0 §2(a)(4)); текстура вложена как есть; кости, которые TACZ показывает только с обвесами (`mag_extended_1/2/3`, `mount`, `oem_stock_heavy`, `oem_stock_light`, `ar_stock_adapter`), перенесены без изменений и только перечислены в нашем поле `hoka_hidden` (клиентский модуль их не рисует, пока обвеса нет — как TACZ); у костей `lefthand_pos`/`righthand_pos` убраны кубы-заглушки рук (это не арт оружия, TACZ их тоже не рисует) |
| `models/ai_awp.bbmodel` | `geo_models/gun/ai_awp_geo.json`, `textures/gun/uv/ai_awp.png`, `animations/ai_awp.animation.json`; прицел — `geo_models/attachment/scope_standard_8x_geo.json`, `textures/attachment/uv/scope_standard_8x.png` | та же конвертация формата; прицел — отдельная модель TACZ, поставленная в кость `scope_pos` винтовки так же, как её ставит при рисовании сам TACZ (кости и кубы прицела не меняются, только переносятся в точку кости; своя текстура — второй в файле). Это сборник двух работ без изменений, а не их переработка. Как TACZ при установленном прицеле, не перенесён механический прицел (`sight`, часть материала, §2(a)(1)(A)); удлинённые магазины (`mag_extended_1/2/3`) — без изменений, в поле `hoka_hidden`; у костей рук убраны кубы-заглушки |
| `models/ai_awp_lod.bbmodel` | `geo_models/gun/lod/ai_awp.json`, `textures/gun/lod/ai_awp.png`; прицел — `geo_models/attachment/lod/scope_standard_8x.json`, `textures/attachment/lod/scope_standard_8x.png` | конвертация формата; упрощённый прицел — в кости `scope_pos` упрощённой винтовки (как выше); добавлен раздел `display` (по её точкам `thirdperson_hand`, `ground`, `fixed`; в инвентаре — эта модель с прицелом) |
| `models/glock_17.bbmodel` | `geo_models/gun/glock_17_geo.json`, `textures/gun/uv/glock_17.png`, `animations/glock_17.animation.json` | конвертация формата; удлинённые магазины (`mag_extended_1/2/3`) — без изменений, в поле `hoka_hidden`; у костей рук убраны кубы-заглушки |
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
| `models/m4a1.bbmodel` | `geo_models/gun/m4a1_geo.json`, `textures/gun/uv/m4a1.png`, `animations/m4a1.animation.json` | конвертация формата; кости, которые TACZ показывает только с обвесами (`mag_extended_1/2/3`, `handguard_tactical`, `oem_side_cover`, `oem_grip_cover`), — без изменений, в поле `hoka_hidden`; у костей рук убраны кубы-заглушки |
| `models/m4a1_lod.bbmodel` | `geo_models/gun/lod/m4a1.json`, `textures/gun/lod/m4a1.png` | конвертация формата; добавлен раздел `display` |
| `models/m16a1.bbmodel` | `geo_models/gun/m16a1_geo.json`, `textures/gun/uv/m16a1.png`, `animations/m16a1.animation.json` | конвертация формата; `mag_extended_1/2/3`, `handguard_tactical`, `oem_handguard_a2`, `mount` — без изменений, в поле `hoka_hidden`; у костей рук убраны кубы-заглушки |
| `models/m16a1_lod.bbmodel` | `geo_models/gun/lod/m16a1.json`, `textures/gun/lod/m16a1.png` | конвертация формата; добавлен раздел `display` |
| `models/deagle.bbmodel` | `geo_models/gun/deagle_geo.json`, `textures/gun/uv/deagle.png`, `animations/deagle.animation.json` | конвертация формата; `mag_extended_1/2/3` — без изменений, в поле `hoka_hidden`; у костей рук убраны кубы-заглушки |
| `models/deagle_lod.bbmodel` | `geo_models/gun/lod/deagle.json`, `textures/gun/lod/deagle.png` | конвертация формата; добавлен раздел `display` |
| `models/m1897.bbmodel` | `geo_models/gun/m1897_geo.json`, `textures/gun/uv/m1897.png`, `animations/m1897.animation.json` | конвертация формата; штык (`bayonet`) — без изменений, в поле `hoka_hidden`; у костей рук убраны кубы-заглушки |
| `models/m1897_item.bbmodel` | `geo_models/gun/m1897_geo.json`, `textures/gun/uv/m1897.png` | конвертация формата (упрощённой модели у TACZ нет — предмет из полной); не перенесены штык (`attachment_adapter`) и патрон с правой рукой (`bullet_and_righthand`) — часть материала, §2(a)(1)(A); у кости левой руки убраны кубы-заглушки; добавлен раздел `display` |
| `textures/m4a1_hud.png`, `m4a1_slot.png`, `m16a1_hud.png`, `m16a1_slot.png`, `deagle_hud.png`, `deagle_slot.png`, `m1897_hud.png`, `m1897_slot.png` | `textures/gun/hud/…`, `textures/gun/slot/…` | без изменений |
| `textures/556x45.png`, `50ae.png`, `12g.png` | `textures/ammo/slot/…` | без изменений |
| `sounds/m4a1_*.ogg` (18), `sounds/m16a1_*.ogg` (8), `sounds/deagle_*.ogg` (22), `sounds/m1897_*.ogg` (14) | `tacz_sounds/m4a1/…`, `tacz_sounds/m16a1/…`, `tacz_sounds/deagle/…`, `tacz_sounds/m1897/…` | без изменений (выстрел, выстрел с глушителем, доставание/убирание, перезарядка, помпа) |
| `sounds/ak47_silence.ogg`, `ak47_silence_3p.ogg`, `awp_silence.ogg`, `awp_silence_3p.ogg`, `glock17_silence.ogg`, `glock17_silence_3p.ogg` | `tacz_sounds/ak47/…`, `tacz_sounds/ai_awp/…`, `tacz_sounds/glock_17/…` | без изменений (выстрел с глушителем) |
| `sounds/dry_fire.ogg`, `fire_select.ogg`, `flesh_hit.ogg`, `head_hit.ogg`, `kill.ogg` | `tacz_sounds/*.ogg` | без изменений |

Сетку прицела в круге оптики клиентский модуль рисует из тех же кубов `division` модели прицела (проекция из точки
`scope_view` на экран, текстура прицела как есть) — так же, как её видит TACZ; своей перерисовки нет.

Визуальных правок нет: геометрия, UV и пиксели текстур совпадают с оригиналом (проверка — рендер
`tools/tacz2bb/README.md`). Конвертер повторяется командами из `tools/tacz2bb/README.md`.

---

*English summary.* Assets in this folder come from **TACZ (Timeless and Classics Zero)**, default gun pack, guns
“AKM” (`ak47`), “Accuracy International AWM” (`ai_awp`) with the “Scout 4-10x” scope (`scope_standard_8x`, placed at
the rifle's `scope_pos` bone exactly as TACZ mounts it — a collection of the two unmodified models), “Glock 17”
(`glock_17`), “M4A1” (`m4a1`), “M16A1” (`m16a1`), “Desert Eagle” (`deagle`) and “Winchester M1897” (`m1897`), https://github.com/MCModderAnchor/TACZ — by the TACZ Dev Team, artists NekoCrane, Receke and
Pos_2333 — licensed under **CC BY-NC-ND 4.0** (https://creativecommons.org/licenses/by-nc-nd/4.0/). The `.bbmodel`
files are a technical format conversion (CC 4.0 §2(a)(4)) of the guns' `*_geo.json` / `lod/*.json` and
`*.animation.json` (and of the scope's geo files); parts that TACZ shows only with attachments are kept unchanged and
only listed in our `hoka_hidden` field (not drawn while no attachment is fitted); a few parts (the AWP's iron sight,
the M1897 bayonet and hand-held shell in its item model) were omitted (sharing a part is allowed by §2(a)(1)(A)); textures and sounds are unchanged; no visual edits. Non-commercial use only. No TACZ code is used.

<!-- attachments:begin (строки сделаны tools/tacz2bb/attachments2yml.py) -->

### Обвесы (все из `tacz_default_gun`, кроме встроенных обвесов оружия, которого у нас нет)

| Файл здесь | Оригинал в паке (`assets/tacz/…`) | Что сделано |
|---|---|---|
| `textures/att/ammo_mod_fmj.png` | `textures/attachment/slot/ammo_mod_fmj.png` | без изменений |
| `textures/att/ammo_mod_he.png` | `textures/attachment/slot/ammo_mod_he.png` | без изменений |
| `textures/att/ammo_mod_hp.png` | `textures/attachment/slot/ammo_mod_hp.png` | без изменений |
| `textures/att/ammo_mod_i.png` | `textures/attachment/slot/ammo_mod_i.png` | без изменений |
| `textures/att/ammo_mod_overpressure_recoil.png` | `textures/attachment/slot/ammo_mod_overpressure_recoil.png` | без изменений |
| `textures/att/ammo_mod_slug.png` | `textures/attachment/slot/ammo_mod_slug.png` | без изменений |
| `textures/att/bayonet_6h3.png` | `textures/attachment/slot/bayonet_6h3.png` | без изменений |
| `models/att/bayonet_6h3.bbmodel` | `geo_models/attachment/bayonet_6h3_geo.json, textures/attachment/uv/bayonet_6h3.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `textures/att/bayonet_m9.png` | `textures/attachment/slot/bayonet_m9.png` | без изменений |
| `models/att/bayonet_m9.bbmodel` | `geo_models/attachment/bayonet_m9_geo.json, textures/attachment/uv/bayonet_m9.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `textures/att/deagle_golden_long_barrel.png` | `textures/attachment/slot/deagle_golden_long_barrel.png` | без изменений |
| `models/att/deagle_golden_long_barrel.bbmodel` | `geo_models/attachment/deagle_golden_long_barrel_geo.json, textures/attachment/uv/deagle_golden.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `textures/att/extended_mag_1.png` | `textures/attachment/slot/extended_mag_1.png` | без изменений |
| `textures/att/extended_mag_2.png` | `textures/attachment/slot/extended_mag_2.png` | без изменений |
| `textures/att/extended_mag_3.png` | `textures/attachment/slot/extended_mag_3.png` | без изменений |
| `textures/att/grip_cobra.png` | `textures/attachment/slot/grip_cobra.png` | без изменений |
| `models/att/grip_cobra.bbmodel` | `geo_models/attachment/grip_cobra.json, textures/attachment/uv/grip_cobra.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `textures/att/grip_cqr.png` | `textures/attachment/slot/grip_cqr.png` | без изменений |
| `models/att/grip_cqr.bbmodel` | `geo_models/attachment/grip_cqr.json, textures/attachment/uv/grip_cqr.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `textures/att/grip_magpul_afg.png` | `textures/attachment/slot/grip_magpul_afg.png` | без изменений |
| `models/att/grip_magpul_afg.bbmodel` | `geo_models/attachment/grip_magpul_afg_geo.json, textures/attachment/uv/grip_magpul_afg.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `textures/att/grip_magpul_afg_2.png` | `textures/attachment/slot/grip_magpul_afg_2.png` | без изменений |
| `models/att/grip_magpul_afg_2.bbmodel` | `geo_models/attachment/grip_magpul_afg_2_geo.json, textures/attachment/uv/grip_magpul_afg_2.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `textures/att/grip_osovets_black.png` | `textures/attachment/slot/grip_osovets_black.png` | без изменений |
| `models/att/grip_osovets_black.bbmodel` | `geo_models/attachment/grip_osovets_black.json, textures/attachment/uv/grip_osovets_black.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `textures/att/grip_rk0.png` | `textures/attachment/slot/grip_rk0.png` | без изменений |
| `models/att/grip_rk0.bbmodel` | `geo_models/attachment/grip_rk0.json, textures/attachment/uv/grip_rk0.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `textures/att/grip_rk1_b25u.png` | `textures/attachment/slot/grip_rk1_b25u.png` | без изменений |
| `models/att/grip_rk1_b25u.bbmodel` | `geo_models/attachment/grip_rk1_b25u.json, textures/attachment/uv/grip_rk1_b25u.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `textures/att/grip_rk6.png` | `textures/attachment/slot/grip_rk6.png` | без изменений |
| `models/att/grip_rk6.bbmodel` | `geo_models/attachment/grip_rk6.json, textures/attachment/uv/grip_rk6.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `textures/att/grip_se_5.png` | `textures/attachment/slot/grip_se_5.png` | без изменений |
| `models/att/grip_se_5.bbmodel` | `geo_models/attachment/grip_se_5.json, textures/attachment/uv/grip_se_5.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `textures/att/grip_td.png` | `textures/attachment/slot/grip_td.png` | без изменений |
| `models/att/grip_td.bbmodel` | `geo_models/attachment/grip_td.json, textures/attachment/uv/grip_td.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `textures/att/grip_troy.png` | `textures/attachment/slot/grip_troy.png` | без изменений |
| `models/att/grip_troy.bbmodel` | `geo_models/attachment/grip_troy_geo.json, textures/attachment/uv/grip_troy.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `textures/att/grip_vertical_military.png` | `textures/attachment/slot/grip_vertical_military.png` | без изменений |
| `models/att/grip_vertical_military.bbmodel` | `geo_models/attachment/grip_vertical_military_geo.json, textures/attachment/uv/grip_vertical_military.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `textures/att/grip_vertical_ranger.png` | `textures/attachment/slot/grip_vertical_ranger.png` | без изменений |
| `models/att/grip_vertical_ranger.bbmodel` | `geo_models/attachment/grip_vertical_ranger_geo.json, textures/attachment/uv/grip_vertical_ranger.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `textures/att/grip_vertical_talon.png` | `textures/attachment/slot/grip_vertical_talon.png` | без изменений |
| `models/att/grip_vertical_talon.bbmodel` | `geo_models/attachment/grip_vertical_talon_geo.json, textures/attachment/uv/grip_vertical_talon.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `textures/att/laser_compact_slot.png` | `textures/attachment/slot/laser_compact_slot.png` | без изменений |
| `models/att/laser_compact.bbmodel` | `geo_models/attachment/laser_compact_geo.json, textures/attachment/uv/laser_compact.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `models/att/laser_compact_lod.bbmodel` | `geo_models/attachment/lod/laser_compact.json, textures/attachment/lod/laser_compact.png` | конвертация формата |
| `textures/att/laser_lopro_slot.png` | `textures/attachment/slot/laser_lopro_slot.png` | без изменений |
| `models/att/laser_lopro.bbmodel` | `geo_models/attachment/laser_lopro_geo.json, textures/attachment/uv/laser_lopro.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `models/att/laser_lopro_lod.bbmodel` | `geo_models/attachment/lod/laser_lopro.json, textures/attachment/lod/laser_lopro.png` | конвертация формата |
| `textures/att/laser_ngal.png` | `textures/attachment/slot/laser_ngal.png` | без изменений |
| `models/att/laser_ngal.bbmodel` | `geo_models/attachment/laser_ngal_geo.json, textures/attachment/uv/laser_ngal.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `textures/att/laser_nightstick_slot.png` | `textures/attachment/slot/laser_nightstick_slot.png` | без изменений |
| `models/att/laser_nightstick.bbmodel` | `geo_models/attachment/laser_nightstick_geo.json, textures/attachment/uv/laser_nightstick.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `models/att/laser_nightstick_lod.bbmodel` | `geo_models/attachment/lod/laser_nightstick.json, textures/attachment/lod/laser_nightstick.png` | конвертация формата |
| `textures/att/grip_td.png` | `textures/attachment/slot/grip_td.png` | без изменений |
| `models/att/laser_peq15.bbmodel` | `geo_models/attachment/laser_peq15_geo.json, textures/attachment/uv/laser_peq15.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `textures/att/laser_peq6.png` | `textures/attachment/slot/laser_peq6.png` | без изменений |
| `models/att/laser_peq6.bbmodel` | `geo_models/attachment/laser_peq6_geo.json, textures/attachment/uv/laser_peq6.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `textures/att/light_extended_mag_1.png` | `textures/attachment/slot/light_extended_mag_1.png` | без изменений |
| `textures/att/light_extended_mag_2.png` | `textures/attachment/slot/light_extended_mag_2.png` | без изменений |
| `textures/att/light_extended_mag_3.png` | `textures/attachment/slot/light_extended_mag_3.png` | без изменений |
| `textures/att/muzzle_brake_cthulhu.png` | `textures/attachment/slot/muzzle_brake_cthulhu.png` | без изменений |
| `models/att/muzzle_brake_cthulhu.bbmodel` | `geo_models/attachment/muzzle_brake_cthulhu_geo.json, textures/attachment/uv/muzzle_brake_cthulhu.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `textures/att/muzzle_brake_cyclone_d2.png` | `textures/attachment/slot/muzzle_brake_cyclone_d2.png` | без изменений |
| `models/att/muzzle_brake_cyclone_d2.bbmodel` | `geo_models/attachment/muzzle_brake_cyclone_d2_geo.json, textures/attachment/uv/muzzle_brake_cyclone_d2.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `textures/att/muzzle_brake_mastiff_sg.png` | `textures/attachment/slot/muzzle_brake_mastiff_sg.png` | без изменений |
| `models/att/muzzle_brake_mastiff_sg.bbmodel` | `geo_models/attachment/muzzle_brake_mastiff_sg_geo.json, textures/attachment/uv/muzzle_brake_mastiff_sg.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `textures/att/muzzle_brake_pioneer.png` | `textures/attachment/slot/muzzle_brake_pioneer.png` | без изменений |
| `models/att/muzzle_brake_pioneer.bbmodel` | `geo_models/attachment/muzzle_brake_pioneer_geo.json, textures/attachment/uv/muzzle_brake_pioneer.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `textures/att/muzzle_brake_timeless50_slot.png` | `textures/attachment/slot/muzzle_brake_timeless50_slot.png` | без изменений |
| `models/att/muzzle_brake_timeless50.bbmodel` | `geo_models/attachment/muzzle_brake_timeless50_geo.json, textures/attachment/uv/muzzle_brake_timeless50.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `textures/att/muzzle_brake_trex.png` | `textures/attachment/slot/muzzle_brake_trex.png` | без изменений |
| `models/att/muzzle_brake_trex.bbmodel` | `geo_models/attachment/muzzle_brake_trex_geo.json, textures/attachment/uv/muzzle_brake_trex.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `textures/att/muzzle_choke_sg.png` | `textures/attachment/slot/muzzle_choke_sg.png` | без изменений |
| `models/att/muzzle_choke_sg.bbmodel` | `geo_models/attachment/muzzle_choke_sg_geo.json, textures/attachment/uv/muzzle_choke_sg.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `textures/att/muzzle_compensator_trident.png` | `textures/attachment/slot/muzzle_compensator_trident.png` | без изменений |
| `models/att/muzzle_compensator_trident.bbmodel` | `geo_models/attachment/muzzle_compensator_trident_geo.json, textures/attachment/uv/muzzle_compensator_trident.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `textures/att/muzzle_silencer_knight_qd.png` | `textures/attachment/slot/muzzle_silencer_knight_qd.png` | без изменений |
| `models/att/muzzle_silencer_knight_qd.bbmodel` | `geo_models/attachment/muzzle_silencer_knight_qd_geo.json, textures/attachment/uv/muzzle_silencer_knight_qd.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `textures/att/muzzle_silencer_mirage.png` | `textures/attachment/slot/muzzle_silencer_mirage.png` | без изменений |
| `models/att/muzzle_silencer_mirage.bbmodel` | `geo_models/attachment/muzzle_silencer_mirage_geo.json, textures/attachment/uv/muzzle_silencer_mirage.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `textures/att/muzzle_silencer_phantom_s1.png` | `textures/attachment/slot/muzzle_silencer_phantom_s1.png` | без изменений |
| `models/att/muzzle_silencer_phantom_s1.bbmodel` | `geo_models/attachment/muzzle_silencer_phantom_s1_geo.json, textures/attachment/uv/muzzle_silencer_phantom_s1.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `textures/att/muzzle_silencer_ptilopsis.png` | `textures/attachment/slot/muzzle_silencer_ptilopsis.png` | без изменений |
| `models/att/muzzle_silencer_ptilopsis.bbmodel` | `geo_models/attachment/muzzle_silencer_ptilopsis_geo.json, textures/attachment/uv/muzzle_silencer_ptilopsis.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `textures/att/muzzle_silencer_sg.png` | `textures/attachment/slot/muzzle_silencer_sg.png` | без изменений |
| `models/att/muzzle_silencer_sg.bbmodel` | `geo_models/attachment/muzzle_silencer_sg_geo.json, textures/attachment/uv/muzzle_silencer_sg.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `textures/att/muzzle_silencer_socom_rc1.png` | `textures/attachment/slot/muzzle_silencer_socom_rc1.png` | без изменений |
| `models/att/muzzle_silencer_socom_rc1.bbmodel` | `geo_models/attachment/muzzle_silencer_socom_rc1_geo.json, textures/attachment/uv/muzzle_silencer_socom_rc1.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `textures/att/muzzle_silencer_ursus.png` | `textures/attachment/slot/muzzle_silencer_ursus.png` | без изменений |
| `models/att/muzzle_silencer_ursus.bbmodel` | `geo_models/attachment/muzzle_silencer_ursus_geo.json, textures/attachment/uv/muzzle_silencer_ursus.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `textures/att/muzzle_silencer_vulture.png` | `textures/attachment/slot/muzzle_silencer_vulture.png` | без изменений |
| `models/att/muzzle_silencer_vulture.bbmodel` | `geo_models/attachment/muzzle_silencer_vulture_geo.json, textures/attachment/uv/muzzle_silencer_vulture.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `textures/att/muzzle_silencer_wraith.png` | `textures/attachment/slot/muzzle_silencer_wraith.png` | без изменений |
| `models/att/muzzle_silencer_wraith.bbmodel` | `geo_models/attachment/muzzle_silencer_wraith_geo.json, textures/attachment/uv/muzzle_silencer_wraith.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `textures/att/oem_grip_cover.png` | `textures/attachment/slot/oem_grip_cover.png` | без изменений |
| `textures/att/oem_handguard_assault.png` | `textures/attachment/slot/oem_handguard_assault.png` | без изменений |
| `textures/att/oem_m1897_bayonet.png` | `textures/attachment/slot/oem_m1897_bayonet.png` | без изменений |
| `textures/att/oem_side_cover.png` | `textures/attachment/slot/oem_side_cover.png` | без изменений |
| `textures/att/oem_sight_1.png` | `textures/attachment/slot/oem_sight_1.png` | без изменений |
| `models/att/oem_sight_1.bbmodel` | `geo_models/attachment/oem_sight_1_geo.json, textures/gun/uv/m4a1.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `textures/att/oem_sight_2.png` | `textures/attachment/slot/oem_sight_2.png` | без изменений |
| `models/att/oem_sight_2.bbmodel` | `geo_models/attachment/oem_sight_2_geo.json, textures/gun/uv/m4a1.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `textures/att/oem_stock_heavy.png` | `textures/attachment/slot/oem_stock_heavy.png` | без изменений |
| `textures/att/oem_stock_light.png` | `textures/attachment/slot/oem_stock_light.png` | без изменений |
| `textures/att/oem_stock_removed.png` | `textures/attachment/slot/oem_stock_removed.png` | без изменений |
| `textures/att/oem_stock_tactical.png` | `textures/attachment/slot/oem_stock_tactical.png` | без изменений |
| `textures/att/scope_1866_4x_slot.png` | `textures/attachment/slot/scope_1866_4x_slot.png` | без изменений |
| `models/att/scope_1866_4x.bbmodel` | `geo_models/attachment/scope_1866_4x_geo.json, textures/attachment/uv/scope_1866_4x.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `textures/att/scope_1873_6x_slot.png` | `textures/attachment/slot/scope_1873_6x_slot.png` | без изменений |
| `models/att/scope_1873_6x.bbmodel` | `geo_models/attachment/scope_1873_6x_geo.json, textures/attachment/uv/scope_1873_6x.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `textures/att/scope_98k.png` | `textures/attachment/slot/scope_98k.png` | без изменений |
| `models/att/scope_98k.bbmodel` | `geo_models/attachment/scope_98k_geo.json, textures/attachment/uv/scope_98k.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `textures/att/scope_acog_ta31.png` | `textures/attachment/slot/scope_acog_ta31.png` | без изменений |
| `models/att/scope_acog_ta31.bbmodel` | `geo_models/attachment/scope_acog_ta31_geo.json, textures/attachment/uv/scope_acog_ta31.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `models/att/scope_acog_ta31_lod.bbmodel` | `geo_models/attachment/lod/scope_acog_ta31.json, textures/attachment/lod/scope_acog_ta31.png` | конвертация формата |
| `textures/att/scope_amg_10.png` | `textures/attachment/slot/scope_amg_10.png` | без изменений |
| `models/att/scope_amg_10.bbmodel` | `geo_models/attachment/scope_amg_10_geo.json, textures/attachment/uv/scope_amg_10.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `textures/att/scope_c79.png` | `textures/attachment/slot/scope_c79.png` | без изменений |
| `models/att/scope_c79.bbmodel` | `geo_models/attachment/scope_c79_geo.json, textures/attachment/uv/scope_c79.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `models/att/scope_c79_lod.bbmodel` | `geo_models/attachment/lod/scope_c79.json, textures/attachment/lod/scope_c79.png` | конвертация формата |
| `textures/att/scope_contender_slot.png` | `textures/attachment/slot/scope_contender_slot.png` | без изменений |
| `models/att/scope_contender.bbmodel` | `geo_models/attachment/scope_contender_geo.json, textures/attachment/uv/scope_contender.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `textures/att/scope_elcan_4x.png` | `textures/attachment/slot/scope_elcan_4x.png` | без изменений |
| `models/att/scope_elcan_4x.bbmodel` | `geo_models/attachment/scope_elcan_4x_geo.json, textures/attachment/uv/scope_elcan_4x.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `models/att/scope_elcan_4x_lod.bbmodel` | `geo_models/attachment/lod/scope_elcan_4x.json, textures/attachment/lod/scope_elcan_4x.png` | конвертация формата |
| `textures/att/scope_hamr_slot.png` | `textures/attachment/slot/scope_hamr_slot.png` | без изменений |
| `models/att/scope_hamr.bbmodel` | `geo_models/attachment/scope_hamr_geo.json, textures/attachment/uv/scope_hamr.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `models/att/scope_hamr_lod.bbmodel` | `geo_models/attachment/lod/scope_hamr.json, textures/attachment/lod/scope_hamr.png` | конвертация формата |
| `textures/att/scope_lpvo_1_6.png` | `textures/attachment/slot/scope_lpvo_1_6.png` | без изменений |
| `models/att/scope_lpvo_1_6.bbmodel` | `geo_models/attachment/scope_lpvo_1_6_geo.json, textures/attachment/uv/scope_lpvo_1_6.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `models/att/scope_lpvo_1_6_lod.bbmodel` | `geo_models/attachment/lod/scope_lpvo_1_6.json, textures/attachment/lod/scope_lpvo_1_6.png` | конвертация формата |
| `textures/att/scope_mk4ert.png` | `textures/attachment/slot/scope_mk4ert.png` | без изменений |
| `models/att/scope_mk4ert.bbmodel` | `geo_models/attachment/scope_mk4ert_geo.json, textures/attachment/uv/scope_mk4ert.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `models/att/scope_mk4ert_lod.bbmodel` | `geo_models/attachment/lod/scope_mk4ert.json, textures/attachment/lod/scope_mk5hd.png` | конвертация формата |
| `textures/att/scope_mk5hd_slot.png` | `textures/attachment/slot/scope_mk5hd_slot.png` | без изменений |
| `models/att/scope_mk5hd.bbmodel` | `geo_models/attachment/scope_mk5hd_geo.json, textures/attachment/uv/scope_mk5hd.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `models/att/scope_mk5hd_lod.bbmodel` | `geo_models/attachment/lod/scope_mk5hd.json, textures/attachment/lod/scope_mk5hd.png` | конвертация формата |
| `textures/att/scope_qmk152.png` | `textures/attachment/slot/scope_qmk152.png` | без изменений |
| `models/att/scope_qmk152.bbmodel` | `geo_models/attachment/scope_qmk152_geo.json, textures/attachment/uv/scope_qmk152.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `textures/att/scope_reap_ir.png` | `textures/attachment/slot/scope_reap_ir.png` | без изменений |
| `models/att/scope_reap_ir.bbmodel` | `geo_models/attachment/scope_reap_ir_geo.json, textures/attachment/uv/scope_reap_ir.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `models/att/scope_reap_ir_lod.bbmodel` | `geo_models/attachment/lod/scope_reap_ir.json, textures/attachment/lod/scope_reap_ir.png` | конвертация формата |
| `textures/att/scope_retro_2x.png` | `textures/attachment/slot/scope_retro_2x.png` | без изменений |
| `models/att/scope_retro_2x.bbmodel` | `geo_models/attachment/scope_retro_2x_geo.json, textures/attachment/uv/scope_retro_2x.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `models/att/scope_retro_2x_lod.bbmodel` | `geo_models/attachment/lod/scope_retro_2x.json, textures/attachment/lod/scope_retro_2x.png` | конвертация формата |
| `textures/att/scope_scout_slot.png` | `textures/attachment/slot/scope_scout_slot.png` | без изменений |
| `models/att/scope_standard_8x.bbmodel` | `geo_models/attachment/scope_scout_geo.json, textures/attachment/uv/scope_scout.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `models/att/scope_standard_8x_lod.bbmodel` | `geo_models/attachment/lod/scope_scout.json, textures/attachment/lod/scope_scout.png` | конвертация формата |
| `textures/att/scope_vudu.png` | `textures/attachment/slot/scope_vudu.png` | без изменений |
| `models/att/scope_vudu.bbmodel` | `geo_models/attachment/scope_vudu_geo.json, textures/attachment/uv/scope_vudu.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `models/att/scope_vudu_lod.bbmodel` | `geo_models/attachment/lod/scope_vudu.json, textures/attachment/lod/scope_vudu.png` | конвертация формата |
| `textures/att/scope_xl50.png` | `textures/attachment/slot/scope_xl50.png` | без изменений |
| `models/att/scope_xl50.bbmodel` | `geo_models/attachment/scope_xl50_geo.json, textures/attachment/uv/scope_xl50.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `models/att/scope_xl50_lod.bbmodel` | `geo_models/attachment/lod/scope_xl50.json, textures/attachment/lod/scope_xl50.png` | конвертация формата |
| `textures/att/scope_xm157_slot.png` | `textures/attachment/slot/scope_xm157_slot.png` | без изменений |
| `models/att/scope_xm157.bbmodel` | `geo_models/attachment/scope_xm157_geo.json, textures/attachment/uv/scope_xm157.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `models/att/scope_xm157_lod.bbmodel` | `geo_models/attachment/lod/scope_xm157.json, textures/attachment/lod/scope_xm157.png` | конвертация формата |
| `textures/att/shotgun_extended_mag_1.png` | `textures/attachment/slot/shotgun_extended_mag_1.png` | без изменений |
| `textures/att/shotgun_extended_mag_2.png` | `textures/attachment/slot/shotgun_extended_mag_2.png` | без изменений |
| `textures/att/shotgun_extended_mag_3.png` | `textures/attachment/slot/shotgun_extended_mag_3.png` | без изменений |
| `textures/att/sight_1p87.png` | `textures/attachment/slot/sight_1p87.png` | без изменений |
| `models/att/sight_1p87.bbmodel` | `geo_models/attachment/sight_1p87_geo.json, textures/attachment/uv/sight_1p87.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `models/att/sight_1p87_lod.bbmodel` | `geo_models/attachment/lod/sight_1p87.json, textures/attachment/lod/sight_1p87.png` | конвертация формата |
| `textures/att/sight_552.png` | `textures/attachment/slot/sight_552.png` | без изменений |
| `models/att/sight_552.bbmodel` | `geo_models/attachment/sight_552_geo.json, textures/attachment/uv/sight_552.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `models/att/sight_552_lod.bbmodel` | `geo_models/attachment/lod/sight_552.json, textures/attachment/lod/sight_552.png` | конвертация формата |
| `textures/att/sight_acro_pistol.png` | `textures/attachment/slot/sight_acro_pistol.png` | без изменений |
| `models/att/sight_acro_pistol.bbmodel` | `geo_models/attachment/sight_acro_pistol_geo.json, textures/attachment/uv/sight_acro_pistol.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `models/att/sight_acro_pistol_lod.bbmodel` | `geo_models/attachment/lod/sight_acro_pistol.json, textures/attachment/lod/sight_acro_pistol.png` | конвертация формата |
| `textures/att/sight_acro_rifle.png` | `textures/attachment/slot/sight_acro_rifle.png` | без изменений |
| `models/att/sight_acro_rifle.bbmodel` | `geo_models/attachment/sight_acro_rifle_geo.json, textures/attachment/uv/sight_acro_rifle.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `models/att/sight_acro_rifle_lod.bbmodel` | `geo_models/attachment/lod/sight_acro_rifle.json, textures/attachment/lod/sight_acro_rifle.png` | конвертация формата |
| `textures/att/sight_apm4s.png` | `textures/attachment/slot/sight_apm4s.png` | без изменений |
| `models/att/sight_apm4s.bbmodel` | `geo_models/attachment/sight_apm4s_geo.json, textures/attachment/uv/sight_apm4s.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `models/att/sight_apm4s_lod.bbmodel` | `geo_models/attachment/lod/sight_apm4s.json, textures/attachment/lod/sight_apm4s.png` | конвертация формата |
| `textures/att/sight_coyote.png` | `textures/attachment/slot/sight_coyote.png` | без изменений |
| `models/att/sight_coyote.bbmodel` | `geo_models/attachment/sight_coyote_geo.json, textures/attachment/uv/sight_coyote.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `models/att/sight_coyote_lod.bbmodel` | `geo_models/attachment/lod/sight_coyote.json, textures/attachment/lod/sight_coyote.png` | конвертация формата |
| `textures/att/sight_deltapoint_pistol.png` | `textures/attachment/slot/sight_deltapoint_pistol.png` | без изменений |
| `models/att/sight_deltapoint_pistol.bbmodel` | `geo_models/attachment/sight_deltapoint_pistol_geo.json, textures/attachment/uv/sight_deltapoint_pistol.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `models/att/sight_deltapoint_pistol_lod.bbmodel` | `geo_models/attachment/lod/sight_deltapoint_pistol.json, textures/attachment/lod/sight_deltapoint_pistol.png` | конвертация формата |
| `textures/att/sight_deltapoint_rifle.png` | `textures/attachment/slot/sight_deltapoint_rifle.png` | без изменений |
| `models/att/sight_deltapoint_rifle.bbmodel` | `geo_models/attachment/sight_deltapoint_rifle_geo.json, textures/attachment/uv/sight_deltapoint_rifle.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `models/att/sight_deltapoint_rifle_lod.bbmodel` | `geo_models/attachment/lod/sight_deltapoint_rifle.json, textures/attachment/lod/sight_deltapoint_rifle.png` | конвертация формата |
| `textures/att/sight_exp3.png` | `textures/attachment/slot/sight_exp3.png` | без изменений |
| `models/att/sight_exp3.bbmodel` | `geo_models/attachment/sight_exp3_geo.json, textures/attachment/uv/sight_exp3.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `models/att/sight_exp3_lod.bbmodel` | `geo_models/attachment/lod/sight_exp3.json, textures/attachment/lod/sight_exp3.png` | конвертация формата |
| `textures/att/sight_fastfire_pistol.png` | `textures/attachment/slot/sight_fastfire_pistol.png` | без изменений |
| `models/att/sight_fastfire_pistol.bbmodel` | `geo_models/attachment/sight_fastfire_pistol_geo.json, textures/attachment/uv/sight_fastfire_pistol.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `models/att/sight_fastfire_pistol_lod.bbmodel` | `geo_models/attachment/lod/sight_fastfire_pistol.json, textures/attachment/lod/sight_fastfire_pistol.png` | конвертация формата |
| `textures/att/sight_fastfire_rifle.png` | `textures/attachment/slot/sight_fastfire_rifle.png` | без изменений |
| `models/att/sight_fastfire_rifle.bbmodel` | `geo_models/attachment/sight_fastfire_rifle_geo.json, textures/attachment/uv/sight_fastfire_rifle.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `models/att/sight_fastfire_rifle_lod.bbmodel` | `geo_models/attachment/lod/sight_fastfire_rifle.json, textures/attachment/lod/sight_fastfire_rifle.png` | конвертация формата |
| `textures/att/sight_infitac_fmp13.png` | `textures/attachment/slot/sight_infitac_fmp13.png` | без изменений |
| `models/att/sight_infitac_fmp13.bbmodel` | `geo_models/attachment/sight_infitac_fmp13_geo.json, textures/attachment/uv/sight_infitac_fmp13.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `models/att/sight_infitac_fmp13_lod.bbmodel` | `geo_models/attachment/lod/sight_infitac_fmp13.json, textures/attachment/lod/sight_infitac_fmp13.png` | конвертация формата |
| `textures/att/sight_okp7.png` | `textures/attachment/slot/sight_okp7.png` | без изменений |
| `models/att/sight_okp7.bbmodel` | `geo_models/attachment/sight_okp7_geo.json, textures/attachment/uv/sight_okp7.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `models/att/sight_okp7_lod.bbmodel` | `geo_models/attachment/lod/sight_okp7.json, textures/attachment/lod/sight_okp7.png` | конвертация формата |
| `textures/att/sight_pk06_pistol.png` | `textures/attachment/slot/sight_pk06_pistol.png` | без изменений |
| `models/att/sight_pk06_pistol.bbmodel` | `geo_models/attachment/sight_pk06_pistol_geo.json, textures/attachment/uv/sight_pk06_pistol.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `models/att/sight_pk06_pistol_lod.bbmodel` | `geo_models/attachment/lod/sight_pk06_pistol.json, textures/attachment/lod/sight_pk06_pistol.png` | конвертация формата |
| `textures/att/sight_pk06_rifle.png` | `textures/attachment/slot/sight_pk06_rifle.png` | без изменений |
| `models/att/sight_pk06_rifle.bbmodel` | `geo_models/attachment/sight_pk06_rifle_geo.json, textures/attachment/uv/sight_pk06_rifle.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `models/att/sight_pk06_rifle_lod.bbmodel` | `geo_models/attachment/lod/sight_pk06_rifle.json, textures/attachment/lod/sight_pk06_rifle.png` | конвертация формата |
| `textures/att/sight_rmr_dot.png` | `textures/attachment/slot/sight_rmr_dot.png` | без изменений |
| `models/att/sight_rmr_dot.bbmodel` | `geo_models/attachment/sight_rmr_dot_geo.json, textures/attachment/uv/sight_rmr_dot.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `models/att/sight_rmr_dot_lod.bbmodel` | `geo_models/attachment/lod/sight_rmr_dot.json, textures/attachment/lod/sight_rmr_dot.png` | конвертация формата |
| `textures/att/sight_sro_dot.png` | `textures/attachment/slot/sight_sro_dot.png` | без изменений |
| `models/att/sight_sro_dot.bbmodel` | `geo_models/attachment/sight_sro_dot_geo.json, textures/attachment/uv/sight_sro_dot.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `models/att/sight_sro_dot_lod.bbmodel` | `geo_models/attachment/lod/sight_sro_dot.json, textures/attachment/lod/sight_sro_dot.png` | конвертация формата |
| `textures/att/sight_srs_02.png` | `textures/attachment/slot/sight_srs_02.png` | без изменений |
| `models/att/sight_srs_02.bbmodel` | `geo_models/attachment/sight_srs_02_geo.json, textures/attachment/uv/sight_srs_02.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `models/att/sight_srs_02_lod.bbmodel` | `geo_models/attachment/lod/sight_srs_02.json, textures/attachment/lod/sight_srs_02.png` | конвертация формата |
| `textures/att/sight_t1.png` | `textures/attachment/slot/sight_t1.png` | без изменений |
| `models/att/sight_t1.bbmodel` | `geo_models/attachment/sight_t1_geo.json, textures/attachment/uv/sight_t1.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `models/att/sight_t1_lod.bbmodel` | `geo_models/attachment/lod/sight_t1.json, textures/attachment/lod/sight_t1.png` | конвертация формата |
| `textures/att/sight_t2.png` | `textures/attachment/slot/sight_t2.png` | без изменений |
| `models/att/sight_t2.bbmodel` | `geo_models/attachment/sight_t2_geo.json, textures/attachment/uv/sight_t2.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `models/att/sight_t2_lod.bbmodel` | `geo_models/attachment/lod/sight_t2.json, textures/attachment/lod/sight_t2.png` | конвертация формата |
| `textures/att/sight_uh1.png` | `textures/attachment/slot/sight_uh1.png` | без изменений |
| `models/att/sight_uh1.bbmodel` | `geo_models/attachment/sight_uh1_geo.json, textures/attachment/uv/sight_uh1.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `models/att/sight_uh1_lod.bbmodel` | `geo_models/attachment/lod/sight_uh1.json, textures/attachment/lod/sight_uh1.png` | конвертация формата |
| `textures/att/sniper_extended_mag_1.png` | `textures/attachment/slot/sniper_extended_mag_1.png` | без изменений |
| `textures/att/sniper_extended_mag_2.png` | `textures/attachment/slot/sniper_extended_mag_2.png` | без изменений |
| `textures/att/sniper_extended_mag_3.png` | `textures/attachment/slot/sniper_extended_mag_3.png` | без изменений |
| `textures/att/stock_ak12.png` | `textures/attachment/slot/stock_ak12.png` | без изменений |
| `models/att/stock_ak12.bbmodel` | `geo_models/attachment/stock_ak12_geo.json, textures/attachment/uv/stock_ak12.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `textures/att/stock_carbon_bone_c5.png` | `textures/attachment/slot/stock_carbon_bone_c5.png` | без изменений |
| `models/att/stock_carbon_bone_c5.bbmodel` | `geo_models/attachment/stock_carbon_bone_c5_geo.json, textures/attachment/uv/stock_carbon_bone_c5.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `textures/att/stock_heavy_ctr.png` | `textures/attachment/slot/stock_heavy_ctr.png` | без изменений |
| `models/att/stock_heavy_ctr.bbmodel` | `geo_models/attachment/stock_heavy_ctr_geo.json, textures/attachment/uv/stock_heavy_ctr.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `textures/att/stock_hk_slim_line.png` | `textures/attachment/slot/stock_hk_slim_line.png` | без изменений |
| `models/att/stock_hk_slim_line.bbmodel` | `geo_models/attachment/stock_hk_slim_line_geo.json, textures/attachment/uv/stock_hk_slim_line.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `textures/att/stock_m4ss.png` | `textures/attachment/slot/stock_m4ss.png` | без изменений |
| `models/att/stock_m4ss.bbmodel` | `geo_models/attachment/stock_m4ss_geo.json, textures/attachment/uv/stock_m4ss.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `textures/att/stock_militech_b5.png` | `textures/attachment/slot/stock_militech_b5.png` | без изменений |
| `models/att/stock_militech_b5.bbmodel` | `geo_models/attachment/stock_militech_b5_geo.json, textures/attachment/uv/stock_militech_b5.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `textures/att/stock_moe.png` | `textures/attachment/slot/stock_moe.png` | без изменений |
| `models/att/stock_moe.bbmodel` | `geo_models/attachment/stock_moe_geo.json, textures/attachment/uv/stock_moe.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `textures/att/stock_psg.png` | `textures/attachment/slot/stock_psg.png` | без изменений |
| `models/att/stock_psg.bbmodel` | `geo_models/attachment/stock_psg_geo.json, textures/attachment/uv/stock_psg.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `textures/att/stock_ripstock.png` | `textures/attachment/slot/stock_ripstock.png` | без изменений |
| `models/att/stock_ripstock.bbmodel` | `geo_models/attachment/stock_ripstock_geo.json, textures/attachment/uv/stock_ripstock.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `textures/att/stock_sba3.png` | `textures/attachment/slot/stock_sba3.png` | без изменений |
| `models/att/stock_sba3.bbmodel` | `geo_models/attachment/stock_sba3_geo.json, textures/attachment/uv/stock_sba3.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `textures/att/stock_tactical_ar.png` | `textures/attachment/slot/stock_tactical_ar.png` | без изменений |
| `models/att/stock_tactical_ar.bbmodel` | `geo_models/attachment/stock_tactical_ar_geo.json, textures/attachment/uv/stock_tactical_ar.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `models/att/riser_pistol_high.bbmodel` | `geo_models/attachment/riser_pistol_high_geo.json, textures/attachment/uv/riser_pistol_high.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `models/att/riser_pistol_low.bbmodel` | `geo_models/attachment/riser_pistol_low_geo.json, textures/attachment/uv/riser_pistol_low.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `models/att/riser_pistol_mid.bbmodel` | `geo_models/attachment/riser_pistol_mid_geo.json, textures/attachment/uv/riser_pistol_mid.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `models/att/riser_rail_high.bbmodel` | `geo_models/attachment/riser_rail_high_geo.json, textures/attachment/uv/riser_rail_high.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `models/att/riser_rail_mid.bbmodel` | `geo_models/attachment/riser_rail_mid_geo.json, textures/attachment/uv/riser_rail_mid.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `models/att/riser_rail_unity.bbmodel` | `geo_models/attachment/riser_rail_unity_geo.json, textures/attachment/uv/riser_rail_unity.png` | конвертация формата Bedrock geo → .bbmodel, текстура вложена как есть |
| `sounds/attachment_general_a.ogg` | `tacz_sounds/attachments/attachment_general_a.ogg` | без изменений (установка/снятие обвеса) |
| `sounds/attachment_general_b.ogg` | `tacz_sounds/attachments/attachment_general_b.ogg` | без изменений (установка/снятие обвеса) |
| `sounds/attachment_general_uninstall.ogg` | `tacz_sounds/attachments/attachment_general_uninstall.ogg` | без изменений (установка/снятие обвеса) |
| `sounds/bayonet_install.ogg` | `tacz_sounds/attachments/bayonet_install.ogg` | без изменений (установка/снятие обвеса) |
| `sounds/bayonet_uninstall.ogg` | `tacz_sounds/attachments/bayonet_uninstall.ogg` | без изменений (установка/снятие обвеса) |
| `sounds/extended_mag_install.ogg` | `tacz_sounds/attachments/extended_mag_install.ogg` | без изменений (установка/снятие обвеса) |
| `sounds/extended_mag_uninstall.ogg` | `tacz_sounds/attachments/extended_mag_uninstall.ogg` | без изменений (установка/снятие обвеса) |
| `sounds/muzzle_general.ogg` | `tacz_sounds/attachments/muzzle_general.ogg` | без изменений (установка/снятие обвеса) |
| `sounds/scope_general_a.ogg` | `tacz_sounds/attachments/scope_general_a.ogg` | без изменений (установка/снятие обвеса) |
| `sounds/scope_general_b.ogg` | `tacz_sounds/attachments/scope_general_b.ogg` | без изменений (установка/снятие обвеса) |
| `sounds/scope_general_c.ogg` | `tacz_sounds/attachments/scope_general_c.ogg` | без изменений (установка/снятие обвеса) |
| `sounds/scope_general_d.ogg` | `tacz_sounds/attachments/scope_general_d.ogg` | без изменений (установка/снятие обвеса) |
| `sounds/scope_general_e.ogg` | `tacz_sounds/attachments/scope_general_e.ogg` | без изменений (установка/снятие обвеса) |

<!-- attachments:end -->
