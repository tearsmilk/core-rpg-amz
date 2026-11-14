# Навыки MMOCore/MythicLib: исправления и проверка урона (раунд 2), 2026-11-14

Отдельный тест-сервер /home/user/mc-skilltest, порт 25601. Итог по всем 12 классам: **100 из 100 тестов PASS** (было 75 PASS / 27 FAIL в DAMAGE_TEST_1). Каждый навык, который наносит урон, теперь бьёт и того, в кого целишься. Ни один навык не ранит кастера. Сопартийцев навыки не трогают, других игроков бьют только при включённом PvP.

На прод я ничего не писал и консольных команд там не выполнял. С прода делал только GET: MythicLib/config.yml, LostAssets_ClassPack_*.yml, LostAssets-паки MythicMobs и список /plugins. Сервер 25599 не трогал. Папка after/ не изменена. Коммитов нет.

Таблица замеров: `DAMAGE_TEST_FINAL.md` (в этой же папке). Полный дифф всех изменений: `changes2.diff`.

## 1. Что было сломано

1. **P0. Цель под прицелом не получала урона.** Затронуты 9 rq_* навыков: FREEZE, ARCANE_HAIL, CORRUPT, CONTAMINATION, ICE_SPIKES, LIGHTNING_BEAM, TNT_THROW, CORROSION, LIFE_ENDER. Во всех `nearby_entities` было `ignore_caster=true` (это значение по умолчанию). Когда в мету передан `targetEntity`, MythicLib исключает именно его, а не кастера. Поэтому моб в прицеле получал 0, а соседи полный урон. Подтверждено и в раунде 1, и сейчас.
2. **LIGHT_DASH.** Хит считался вокруг точки старта (origin), а не вокруг текущей позиции игрока. Кроме того, `length` 4+1/ур. давал полёт на 8–20 с (около 74 блоков).
3. **POWER_MARK** (встроенный). Луч шёл только по блокам, поэтому метка ложилась на землю за мобом и не копила урон: прибавка +0.
4. **SNOWMAN_TURRET** (встроенный). Турель тоже ставилась за целью. Хуже того, ванильные снежки, пролетевшие мимо, попадали в кастера: **кастер терял 9.5 HP**. Встроенный обработчик ставит урон любому, в кого попал снежок, без проверки PvP и пати.
5. **ITEM_THROW.** `force` 2+1/ур. давал скорость 16.5 бл/тик, предмет пролетал цель насквозь: на L10 урон 0.
6. **EVADE, FURTIVE_STRIKE, DEEP_WOUND.** Мана 8+3/ур. = 35 на L10 при пуле энергии 20–33, на L≥6–7 навыки вообще не кастовались. У HUMAN_SHIELD мана 28.5.
7. **Нет `interaction_rules` в MythicLib/config.yml.** Все навыки били сопартийцев и любых игроков, вешали на них яд, стан, замедление.
8. **Абсурдные формулы.**
   - Подброс SHOCKWAVE, SKY_SMASH, TACTICAL_GRENADE отправлял мобов на 100+ бл. вверх (смерть от падения).
   - CIRCULAR_SLASH отбрасывал на 48–58 бл.
   - LEAP давал 14.5 бл/тик.
   - Радиусы до 12–14: CIRCULAR_SLASH, EMPOWERED_ATTACK, ITEM_BOMB, OVERLOAD, GRAND_HEAL, MAGICAL_SHIELD, SNOWMAN_TURRET.
   - «Тихие» задержки каста росли с уровнем: OVERLOAD до 13.9 с, SKY_SMASH 8.5, GRAND_HEAL 11, HEAL 2.9.
   - Вечный контроль, у которого длительность ≥ КД: STUN 7.8 с, BLIND 14, POISON 12.7, WITHER 10.5, FREEZE 5.7, SHADOW_VEIL 19, CONTAMINATION, BLACK_HOLE, ARCANE_HAIL, MAGICAL_SHIELD.
   - Пассивки BACKSTAB, SNEAKY_PICKY, VAMPIRISM, AMBERS тратили ману за каждое срабатывание.
   - DEATH_MARK наносил 4→4.9 урона за 10→23.5 маны.
9. **Навыки LOSTASSETS_\* (8 способностей MMOItems в sword.yml) не загружались.** Им нужен MythicMobs, а он выключен. В логе было «Could not find skill source 'mythicmobs'».
10. **У rq-навыков item-значения для MMOItems были 0 или null:** CORRUPT.radius, TNT_THROW.damage/radius, ICE_SPIKES.damage/slow. Например, способность CORRUPT на броне ничего не делала.

## 2. Что изменено (старое → новое)

### plugins/MythicLib/script/royalquest_skills.yml (из after/)
- **Все 11 `nearby_entities`: добавлено `ignore_caster=false`.** Это ice_spikes_at, lightning_beam_at, freeze_at, arcane_hail_strike, black_hole_tick, contamination_tick, corrosion_at, corrupt_at, life_ender_impact, tnt_boom и rq_light_dash. Кастера по-прежнему отсекает `can_target`.
- **Все 9 корневых `ray_trace`:** `range=30` → `range=50` (как у встроенных), добавлен `neutral=false`. Луч больше не застревает на стойках для брони и союзниках.
- **LIGHT_DASH.** `rq_light_dash_tick` теперь вызывает новый `rq_light_dash_at` с `target_location={type=caster}`, и хит r1.5 считается вокруг текущей позиции игрока.
- **TNT_THROW.** Снаряд стартует в 0.9 бл. позади глаз, поэтому первый блок полёта тоже проверяется (раньше цель вплотную не задевалась). `life_span` 24 → 25.
- **Новый `rq_power_mark`** (POWER_MARK).
  - Метка вешается на врага под прицелом. Если целиться в блок, метка ложится на ближайшего врага в 2 бл. от точки.
  - Метка копит ratio% всего урона, который эта цель получила за duration с (HP в начале − HP в конце).
  - Затем взрыв: накопленный урон и стан (slowness 10) на `stun` с по врагам в r5.
- **Новый `rq_snowman_turret`** (SNOWMAN_TURRET).
  - Турель встаёт в 2 бл. перед целью под прицелом или на блок под прицелом.
  - Раз в секунду она бросает MythicLib-снаряд в одного врага в радиусе. Снаряд сталкивается только с допустимыми целями, так что кастер и сопартийцы не страдают.
  - Снеговик нарисован частицами. Живой Snow Golem потребовал бы консольных команд (summon/kill) из скрипта, и я от этого отказался.

### plugins/MythicLib/script/lostassets_skills.yml (новый файл)
- Восемь скриптов `la_swordmaster_lc/rc/slc/src` и `la_revenant_lc/rc/slc/src`.
- Они повторяют боевую часть паков MythicMobs (plugins/MythicMobs/packs/LostAssets_ClassPack_0*/Skills): те же зоны, радиусы, задержки и число ударов.
- Повторить без MythicMobs нельзя:
  - модели и VFX (summon ModelEngine/armorstand); вместо них ванильные частицы и звуки;
  - трёхступенчатое комбо ЛКМ на аурах; теперь каждый ЛКМ — первая ступень;
  - плоский бонус `+<caster.mmostat.skill_damage>`; вместо него MythicLib применяет стат SKILL_DAMAGE в процентах.

### plugins/MythicLib/skill/LostAssets_ClassPack_01_Swordmaster.yml, LostAssets_ClassPack_04_Revenant.yml (взяты с прода)
- `source: mythicmobs:lostassets_X` → `source: mythiclib:la_X`, все 8 способностей.

### plugins/MythicLib/config.yml (взят с прода, на проде не менялся, сверено cmp)
- В конец дописан блок `interaction_rules` (`enabled: true`).
- offense: `self: false`, `party_member: false`, `guild_ally: false`; `guild_enemy`, `guild_neutral` и `party_other` = `'{world_pvp}'`.
- support: себе, пати и альянсу можно; чужим — только если `!{world_pvp}`.
- `support_skills_on_mobs: true`.

### plugins/MythicLib/skill/default_skills.yml (из after/)
Формула параметра: `base + per-level*(ур-1)`, затем обрезка min/max. Значения L10 приведены в скобках.

    EVADE             mana: base=8.0 per-level=3.0 -> base=8.0 per-level=0.8 min=8.0 max=15.0
    DEEP_WOUND        mana: base=8.0 per-level=3.0 -> base=8.0 per-level=0.8 min=8.0 max=15.0
    FURTIVE_STRIKE    mana: base=8.0 per-level=3.0 -> base=8.0 per-level=0.8 min=8.0 max=15.0
    HUMAN_SHIELD      mana: base=15.0 per-level=1.5 -> base=12.0 per-level=0.5 min=12.0 max=16.0
    FURTIVE_STRIKE    radius: base=7.0 per-level=1.0 -> 5.0
    ITEM_THROW        force: base=2 per-level=1 -> base=1.0 per-level=0.05 min=1.0 max=1.4
    LIGHT_DASH        length: base=4 per-level=1 -> base=1.0 per-level=0.05 min=1.0 max=1.5
    LEAP              force: base=3 per-level=0.5 -> base=0.6 per-level=0.04 min=0.6 max=1.0
    SHOCKWAVE         knock-up: base=5 per-level=1 -> base=1.5 per-level=0.05 min=1.5 max=2.0
    SKY_SMASH         knock-up: base=2 per-level=0.3 -> base=0.5 per-level=0.03 min=0.5 max=0.8
    TACTICAL_GRENADE  knock-up: base=3 per-level=0.5 -> base=1.0 per-level=0.05 min=1.0 max=1.5
    CIRCULAR_SLASH    knockback: base=2 per-level=1 -> base=0.3 per-level=0.03 min=0.3 max=0.6
    CIRCULAR_SLASH    radius: base=3 per-level=1 -> base=3 per-level=0.2 min=3 max=5
    EMPOWERED_ATTACK  radius: base=4.0 per-level=1.0 -> base=3.0 per-level=0.1 min=3.0 max=4.0
    EMPOWERED_ATTACK  ratio: base=30.0 per-level=10.0 max=100.0 min=30.0 -> base=30.0 per-level=3.0 min=30.0 max=60.0
    ITEM_BOMB         radius: base=3 per-level=1 -> base=3 per-level=0.2 min=3 max=5
    ITEM_BOMB         slow-duration: base=5 per-level=3 -> base=3 per-level=0.3 min=3 max=6
    OVERLOAD          radius: base=5 per-level=1 -> base=5 per-level=0.2 min=5 max=7
    GRAND_HEAL        radius: base=3 per-level=1 -> base=3 per-level=0.4 min=3 max=7
    MAGICAL_SHIELD    radius: base=3 per-level=1 -> base=3 per-level=0.3 min=3 max=6
    MAGICAL_SHIELD    duration: base=10 per-level=3 -> base=5 per-level=0.3 min=5 max=8
    MAGICAL_SHIELD    cooldown: base=4.0 per-level=-0.16 min=2.4 max=4.0 -> base=12.0 per-level=-0.3 min=9.0 max=12.0
    SNOWMAN_TURRET    radius: base=4 per-level=1 -> base=4 per-level=0.5 min=4 max=9
    SNOWMAN_TURRET    duration: base=6 per-level=0.5 -> base=6 per-level=0.4 min=6 max=9.6
    SNOWMAN_TURRET    cooldown: base=8.0 per-level=-0.5 min=4.8 max=8.0 -> base=12.0 per-level=-0.2 min=10.0 max=12.0
    OVERLOAD          delay: base=4 per-level=1.1 min=0 max=0 -> base=1.5 per-level=-0.1 min=0.5 max=1.5
    SKY_SMASH         delay: base=4 per-level=0.5 min=0 max=0 -> base=1.0 per-level=-0.05 min=0.5 max=1.0
    GRAND_HEAL        delay: base=2 per-level=1 min=0 max=0 -> base=1.5 per-level=-0.1 min=0.5 max=1.5
    HEAL              delay: base=2 per-level=0.1 min=0 max=0 -> base=1.0 per-level=-0.05 min=0.5 max=1.0
    STUN              duration: base=6 per-level=0.2 -> base=1.0 per-level=0.1 min=1.0 max=2.0
    BLIND             duration: base=5 per-level=1 -> base=2 per-level=0.1 min=2 max=3
    POISON            duration: base=10 per-level=0.3 -> base=4 per-level=0.1 min=4 max=5
    WITHER            duration: base=6 per-level=0.5 -> base=3 per-level=0.1 min=3 max=4
    FREEZE            duration: base=3 per-level=0.3 min=3 max=6 -> base=2 per-level=0.1 min=2 max=3
    SHADOW_VEIL       duration: base=10 per-level=1 -> base=5 per-level=0.5 min=5 max=10
    SHADOW_VEIL       cooldown: base=10.0 per-level=-0.4 min=6.0 max=10.0 -> base=14.0 per-level=-0.2 min=12.0 max=14.0
    CONTAMINATION     duration: base=4 per-level=1 -> base=4 per-level=0.4 min=4 max=8
    CONTAMINATION     cooldown: base=8 per-level=-0.2 min=5 max=8 -> base=10 per-level=-0.2 min=8 max=10
    BLACK_HOLE        duration: base=3 per-level=0.2 min=3 max=5 -> base=3 per-level=0.15 min=3 max=4.5
    BLACK_HOLE        cooldown: base=4.0 per-level=-0.16 min=2.4 max=4.0 -> base=6.0 per-level=-0.15 min=4.5 max=6.0
    ARCANE_HAIL       duration: base=4 per-level=1 -> base=4 per-level=0.35 min=4 max=7
    BACKSTAB          mana: base=8.0 per-level=1.0 -> 0
    SNEAKY_PICKY      mana: base=8.0 per-level=1.0 -> 0
    VAMPIRISM         mana: base=2 per-level=1 -> 0
    AMBERS            mana: base=2.0 per-level=1.0 -> 0
    DEATH_MARK        damage: base=4 per-level=0.1 -> base=5 per-level=1.5
    POWER_MARK        source: default:POWER_MARK -> mythiclib:rq_power_mark
    POWER_MARK        stun: base=2.4 per-level=1.03 -> base=1.5 per-level=0.1 min=1.5 max=2.5
    POWER_MARK        lore rewritten
    SNOWMAN_TURRET    source: default:SNOWMAN_TURRET -> mythiclib:rq_snowman_turret
    CORRUPT           radius (item, MMOItems): нет -> 2.7
    TNT_THROW         damage/radius (item): 0/0 -> 7/3.5
    ICE_SPIKES        damage/slow (item): 0/0 -> 3/4
    POWER_MARK        duration/stun/ratio (item): 0/0/0 -> 10/1.5/10

MMOCore/classes/*.yml, MythicHUD и PlaceholderAPI я не менял: правок там не потребовалось. Эти файлы в after2 не входят, на проде остаются версии из after/.

## 3. Итоговый урон по навыкам (L10, раунд 2, тест-сервер)

T — зомби 1000 HP в прицеле в 6 бл. (у ближних навыков в 3.5 бл.); S — зомби в 2 бл. сбоку от T; B — зомби в 6 бл. за T. Урон показан с учётом 2 брони зомби. «Кастеру» — урон, полученный кастером от собственного навыка (у лечилок и щитов урон кастеру нанесён тестом, в таблице не указан). Полная таблица по всем 100 тестам (класс × навык) лежит в DAMAGE_TEST_FINAL.md.

| Навык | Источник | Классы | T (цель) | S (сосед) | B (за целью) | Мана L10 (списано/цена) | Прочее |
|---|---|---|---|---|---|---|---|
| FIREBOLT | встр.FIREBOLT | MAGE, WARLOCK, WIZARD | 14.81 | 0 | 0 | 9.9/11 |  |
| FREEZE | rq_freeze | HUNTER, MAGE, WARLOCK, WIZARD | 0 | 0 | 0 | 14.7/15.8 | эффект на цели: slowness+2 |
| BLINK | rq_blink | MAGE, SNIPER, WARLOCK, WIZARD | 0 | 0 | 0 | 11.9/13 | смещение 6.8 бл. |
| ARCANE_HAIL | rq_arcane_hail | MAGE, WARLOCK, WIZARD | 47.23 | 70.85 | 47.23 | 9.8/11.9 |  |
| AMBERS | встр.AMBERS | MAGE, WARLOCK, WIZARD | 34.63 | 0 | 0 | —/0 |  |
| CORRUPT | rq_corrupt | WARLOCK | 14.82 | 14.82 | 0 | 11/11 | эффект на цели: wither+1 |
| CURSED_BEAM | встр.CURSED_BEAM | WARLOCK | 21.65 | 21.65 | 0 | 12.3/13.4 | эффект на цели: blindness |
| CONTAMINATION | rq_contamination | WARLOCK | 75.77 | 75.77 | 0 | 13.9/15 |  |
| DEATH_MARK | встр.DEATH_MARK | WARLOCK | 16.81 | 0 | 0 | 22.4/23.5 | эффект на цели: slowness+2 |
| BLACK_HOLE | rq_black_hole | WARLOCK | 0 | 0 | 0 | 11.9/13 | притяжение S 1.74, B 5.7 бл. |
| FIREBALL | встр.FIREBALL | WIZARD | 34.49 | 0 | 0 | 23.9/25 |  |
| ICE_SPIKES | rq_ice_spikes | WIZARD | 11.81 | 11.81 | 0 | 36.9/38 | эффект на цели: slowness+1 |
| LIGHTNING_BEAM | rq_lightning_beam | WIZARD | 7.38 | 7.38 | 0 | 31.4/32.5 |  |
| FIRE_STORM | встр.FIRE_STORM | WIZARD | 97.46 | 0 | 0 | 31.9/33 |  |
| STARFALL | встр.STARFALL | WIZARD | 13.87 | 0 | 0 | 11.4/12.5 |  |
| ITEM_THROW | встр.ITEM_THROW | ARCHER, HUNTER, SNIPER | 13.68 | 0 | 0 | 11.8/12.9 |  |
| SLOW | встр.SLOW | ARCHER, HUNTER, SNIPER | 0 | 0 | 0 | 11.8/12.9 | эффект на цели: slowness+2 |
| SWIFTNESS | встр.SWIFTNESS | ARCHER, HUNTER, SNIPER | 0 | 0 | 0 | 13.7/14.8 |  |
| LEAP | встр.LEAP | ARCHER, CRUSADER, DARK_KNIGHT, HUNTER, SNIPER, SWORDSMAN | 0 | 0 | 0 | 2/3.1 | смещение 4.2 бл. |
| SHULKER_MISSILE | встр.SHULKER_MISSILE | ARCHER, HUNTER, SNIPER | 13.78 | 0 | 0 | 17/17 |  |
| SNOWMAN_TURRET | rq_snowman_turret | HUNTER | 84.13 | 0 | 0 | 2.9/4 | 1 снежок/с по одной цели; кастеру 0 (встроенный: кастеру −9.5) |
| ITEM_BOMB | встр.ITEM_BOMB | HUNTER | 10.82 | 10.82 | 10.82 | 9.9/11 | эффект на цели: slowness+2 |
| POWER_MARK | rq_power_mark | HUNTER | 15.5 | 0 | 15.98 | 9.9/11 |  |
| SKY_SMASH | встр.SKY_SMASH | HUNTER | 18.22 | 18.22 | 0 | 2.9/4 |  |
| WEAKEN_TARGET | встр.WEAKEN_TARGET | SNIPER | 0 | 0 | 0 | 7.4/8.5 | 10 урона → 11.12 (контроль 9.84; см. WEAKEN) |
| STUN | встр.STUN | ROGUE, SNIPER | 0 | 0 | 0 | 5.1/6.2 | эффект на цели: slowness |
| TACTICAL_GRENADE | встр.TACTICAL_GRENADE | SNIPER | 15.36 | 15.36 | 0 | 3.2/5.5 |  |
| TNT_THROW | rq_tnt_throw | SNIPER | 15.74 | 15.74 | 0 | 7.4/8.5 |  |
| BACKSTAB | встр.BACKSTAB | ASSASSIN, ROGUE, THIEF | 25.39 | 0 | 0 | 0/0 | удар 5.9→19.48 |
| POISON | встр.POISON | ASSASSIN, ROGUE, THIEF | 8 | 0 | 0 | 15.6/16.7 | эффект на цели: poison+2 |
| SNEAKY_PICKY | встр.SNEAKY_PICKY | ASSASSIN, ROGUE, THIEF | 11.81 | 0 | 0 | 0/0 | срабатывает только вне боя; 2-й удар теста уже «в бою» (5.9→5.9); отдельная проверка zz_sneaky.js: 5.9→19.48 |
| LIGHT_DASH | rq_light_dash | ASSASSIN, ROGUE, THIEF | 8.56 | 0 | 8.56 | 12.9/14 |  |
| EVADE | встр.EVADE | ASSASSIN, ROGUE, THIEF | 0 | 0 | 0 | 13.9/15 | получено 0 из 6 |
| FURTIVE_STRIKE | встр.FURTIVE_STRIKE | ASSASSIN | 18.2 | 0 | 0 | 13.9/15 |  |
| CORROSION | rq_corrosion | ASSASSIN | 9 | 9 | 9 | 9.9/11 | эффект на цели: poison+2 |
| SHADOW_VEIL | встр.SHADOW_VEIL | ASSASSIN | 0 | 0 | 0 | 18.8/19.9 |  |
| DEEP_WOUND | встр.DEEP_WOUND | ASSASSIN, DARK_KNIGHT | 18.2 | 0 | 0 | 13.9/15 |  |
| LIFE_ENDER | rq_life_ender | ASSASSIN | 10.82 | 10.82 | 10.82 | 9.9/11 |  |
| BLIND | встр.BLIND | ROGUE | 0 | 0 | 0 | 12.9/14 | эффект на цели: blindness |
| CONFUSE | встр.CONFUSE | ROGUE | 0 | 0 | 0 | 9.9/11 |  |
| COMBO_ATTACK | встр.COMBO_ATTACK | ROGUE | 19.7 | 0 | 0 | 3.9/5 |  |
| BLOODBATH | встр.BLOODBATH | ROGUE | 0 | 0 | 0 | 9.9/11 | сытость цели 20→9 |
| CIRCULAR_SLASH | встр.CIRCULAR_SLASH | CRUSADER, DARK_KNIGHT, SWORDSMAN | 10.82 | 10.82 | 0 | 9.9/11 |  |
| THRUST | встр.THRUST | CRUSADER, DARK_KNIGHT, SWORDSMAN | 8.56 | 0 | 0 | 2.9/4 |  |
| EMPOWERED_ATTACK | встр.EMPOWERED_ATTACK | CRUSADER, DARK_KNIGHT, SWORDSMAN | 17.83 | 3.37 | 0 | 10.7/13 | удар 5.9→11.93 |
| SHOCKWAVE | встр.SHOCKWAVE | CRUSADER, DARK_KNIGHT, SWORDSMAN | 0 | 0 | 0 | 5.8/6.9 | подъём T 3.79 бл. |
| HEAL | встр.HEAL | CRUSADER | 0 | 0 | 0 | 4.7/5.8 | вылечено 9.1 (после тестового /damage 12) |
| HUMAN_SHIELD | встр.HUMAN_SHIELD | CRUSADER | 0 | 0 | 0 | 14.9/16 | союзник в пати получил 4.2 из 10, на кастера перенесено 1.9 (так задумано) |
| SMITE | встр.SMITE | CRUSADER | 13.68 | 0 | 0 | 7.4/8.5 |  |
| MAGICAL_SHIELD | встр.MAGICAL_SHIELD | CRUSADER | 0 | 0 | 0 | 7.6/8.7 | тестовый удар 6 → получено 5.28 (−12%) |
| GRAND_HEAL | встр.GRAND_HEAL | CRUSADER | 0 | 0 | 0 | 5.9/7 | вылечено 12 (весь тестовый урон 12) |
| WEAKEN | встр.WEAKEN | DARK_KNIGHT | 0 | 0 | 0 | 11.9/13 | 10 урона → 24.25 (контроль 9.84; см. WEAKEN) |
| VAMPIRISM | встр.VAMPIRISM | DARK_KNIGHT | 0 | 0 | 0 | 0/0 | лечение с удара 0.3 → 1.3 (кастеру заранее нанесено 10 тестовым /damage) |
| WITHER | встр.WITHER | DARK_KNIGHT | 7 | 0 | 0 | 9.4/10.5 | эффект на цели: wither+2 |
| OVERLOAD | встр.OVERLOAD | DARK_KNIGHT | 16.53 | 16.53 | 0 | 22.4/23.5 |  |

Пояснения:
- **POISON и CORROSION** проверены на свинье: зомби иммунны к яду.
- **WEAKEN** в таблице показывает +146%. Это артефакт команды `/damage … by`. Реальный удар мечом даёт ×1.571, то есть ровно +57% (zz_weaken.js).
- **STUN, CONFUSE, SHADOW_VEIL** урона не наносят и проверяются по эффекту, повороту или невидимости.
- **LostAssets:** SWORDMASTER LC/RC/SLC/SRC = 2.94 / 6.89 / 9.84 / 23.62 (4 волны), REVENANT LC/RC/SLC/SRC = 2.94 / 6.89 / 49.2 (5 ударов) / 5.9. Урон кастеру 0.
- **Пати/PvP** (ICE_SPIKES и CIRCULAR_SLASH по второму игроку):
  - в пати: 0 урона и 0 эффектов;
  - без пати, pvp=true: 11.5 и 10.5;
  - без пати, pvp=false: 0.
- **Очередь кастов во время delay** (OVERLOAD, GRAND_HEAL): нет, три нажатия дают один каст.

## 4. Оставшиеся ограничения

- **LOSTASSETS_\*:** без MythicMobs нет моделей и VFX, нет трёхступенчатого комбо и плоского бонуса skill_damage (подробнее в разделе 2). Если MythicMobs вернут, достаточно откатить `source:` в двух файлах LostAssets_ClassPack_*.yml на `mythicmobs:lostassets_*`.
- **SNOWMAN_TURRET:**
  - снеговик нарисован частицами, живого Snow Golem нет;
  - 1 снежок/с по одному врагу: L10 около 84 урона за 9.6 с по одиночной цели;
  - встроенный навык на практике давал около 93 суммарно по трём мобам и ранил кастера.
- **POWER_MARK:**
  - копит любой урон по помеченной цели, не только урон кастера (так и написано в лоре);
  - если цель умерла, взрыв всё равно срабатывает по её последней позиции;
  - лечение цели уменьшает накопление;
  - при промахе в небо мана и КД списываются. То же верно для всех rq_* навыков при промахе дальше 50 бл. или в небо: встроенные в этом случае не кастовались.
- **FIRE_STORM и COMBO_ATTACK.**
  - Неуязвимость после удара (10 тиков) съедает часть урона FIRE_STORM: 97 из 192 номинальных. Это встроенный навык, конфигом не лечится.
  - COMBO_ATTACK делит урон на число ударов, так что 19.7 из 20.7 — это норма. Он отбрасывает моба примерно на 23 бл. за 13 ударов (встроенный knockback каждого удара).
- **`interaction_rules` меняют и ближний бой, и стрельбу:** сопартийцы больше не могут ударить друг друга. Остальные игроки бьются, только если в мире включён gamerule pvp: правило `{world_pvp}` берёт `World#getPVP()`.
- **LIFE_ENDER и CORRUPT как способности MMOItems** в режимах ATTACK/DAMAGED бьют по точке прицела, а не по атакованному. В ближнем бою это почти всегда одно и то же.
- **Не трогал:**
  - SWIFTNESS: бафф на себя дольше КД, некритично;
  - спам `[math] Invalid Placeholder` от MythicHUD: это не навык, к тому же в after/ уже есть фикс через `noskillcd_check`;
  - баланс урона, который не был сломан.

## 5. Что заливать на прод и в каком порядке

Все файлы лежат в `/home/user/core-rpg-amz/ops/mmocore-skills-2026-11-14/after2/` под теми же путями, что на сервере.

1. `plugins/MythicLib/script/royalquest_skills.yml` → `/plugins/MythicLib/script/royalquest_skills.yml` (замена).
2. `plugins/MythicLib/script/lostassets_skills.yml` → `/plugins/MythicLib/script/lostassets_skills.yml` (**новый** файл).
3. `plugins/MythicLib/skill/default_skills.yml` → `/plugins/MythicLib/skill/default_skills.yml` (замена).
4. `plugins/MythicLib/skill/LostAssets_ClassPack_01_Swordmaster.yml` и `LostAssets_ClassPack_04_Revenant.yml` → `/plugins/MythicLib/skill/` (замена).
5. `plugins/MythicLib/config.yml` → `/plugins/MythicLib/config.yml` (замена). Это продовый файл плюс блок interaction_rules. Если на проде его успели изменить, перенесите вручную только блок `interaction_rules` из конца файла.

Порядок важен: сначала скрипты (шаги 1–2), потом навыки (3–4). Навыки ссылаются на `mythiclib:rq_power_mark`, `rq_snowman_turret` и `la_*`.

Затем в консоли выполнить `mythiclib reload`, потом `mmocore reload`. Надёжнее перезапустить сервер.

В логе после этого:
- не должно быть `Could not load skill` для POWER_MARK, SNOWMAN_TURRET и LOSTASSETS_\*;
- не должно быть `Could not load interaction rules`;
- предупреждения про mythicmobs для LOSTASSETS должны исчезнуть.

MMOCore classes, MythicHUD и PlaceholderAPI остаются из after/, их перезаливать не нужно.

## 6. Тестовые файлы (для повторной проверки)

- `/home/user/mc-bot/skilltest.js`: общий тест. По сравнению с раундом 1 SkT2 сразу уходит с линии огня, получает уровень 65, а для HUMAN_SHIELD собирается пати.
- `/home/user/mc-bot/st_report2.py`: генератор DAMAGE_TEST_FINAL.md.
- `/home/user/mc-bot/st_params.json`: пересчитан под after2. Старые версии сохранены как `st_params_after1.json` и `skilltest_round1.js`.
- Данные:
  - `/home/user/mc-bot/skilltest_results_round2.jsonl`: полный прогон;
  - `/home/user/mc-bot/r2_rerun.jsonl` и `/home/user/mc-bot/r2_snow.jsonl`: перепрогоны;
  - `/home/user/mc-bot/skilltest_results_final.jsonl`: сводные данные;
  - `/home/user/mc-bot/zz_la_results.jsonl`: LostAssets.
- Разовые проверки: `/home/user/mc-bot/zz_party.js`, `/home/user/mc-bot/zz_weaken.js`, `/home/user/mc-bot/zz_delayq.js`, `/home/user/mc-bot/zz_la.js`. Для zz_la нужны тестовые копии из `/home/user/mc-skilltest/testscripts/la/`; после теста я вернул оригиналы.
- Состояние тест-сервера: /home/user/mc-skilltest работает, его конфиги MythicLib побайтно равны after2.
