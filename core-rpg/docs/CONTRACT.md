# CoreRpg: контракт модулей

Система Amazake `core-rpg` (пакет `core.amazake.systems.corerpg`) объединяет три плагина — Zatochka, MagicSeals,
RoyalQuestClasses — и решения артефакта экономики (`docs/economy.html` в корне репозитория) в одну систему `.azs`.

Гайд по системам Amazake: `/home/user/tearsmilk/amazake/docs/systems.md` (обязательно прочитать разделы «Потоки»,
«Что ядро убирает само», «Чек-лист»). Шаблон: `/home/user/tearsmilk/amazake/systems-template/`.

## Правила для всех модулей

1. **Потоки.** Игрока трогает только его поток: его события, его команды, `context.runOnceForPlayer/runForPlayer`.
   Общие данные — `ConcurrentHashMap`, `Atomic*`, неизменяемые `record`. Настройки модуля — неизменяемый record в
   `AtomicReference`, подменяется целиком в `reload`. Файлы — вне тика. Никаких `Thread.sleep`, `future.get()`.
2. **Регистрации** только через `SystemContext` (`registerListener`, `registerCommand`, `run…`). Свои потоки не
   заводить. Если нужен асинхронный разовый запуск — `Bukkit.getAsyncScheduler().runNow(context.plugin(), …)`.
3. **Тексты** — MiniMessage через `util.Text` (`Text.send`, `Text.mm`, `Text.item` для предметов без курсива).
   Пользовательский ввод — `Placeholder.unparsed`. Все сообщения и конфиги — по-русски.
4. **Конфиг модуля** — свой файл в `src/main/resources/` (список ниже), загрузка `Configs.load(context, "<файл>")`.
   Разбор — статический `XxxSettings.from(ConfigurationSection, Consumer<String> warn)`, неверные значения →
   предупреждение и значение по умолчанию. Файл подробно прокомментирован по-русски.
5. **Права.** Команды игроков — без права (доступны всем). Админские подкоманды проверяют право вручную
   (`sender.hasPermission(...)`, у OP есть по умолчанию): `core.class.admin`, `core.sharpen.admin`,
   `core.seals.admin`. Никакой приписки `rq`, `royalquest`, `zatochka`, `magicseals` в новых командах и правах.
6. **Не править чужое.** Модуль меняет только свой пакет, свои ресурсы и свои тесты. Общие классы (`CoreRpgSystem`,
   `CoreModule`, `GeneralSettings`, `util/*`, `profile/*`, `aspect/*`) и фасады других модулей не трогать. Если без
   изменения общего кода не обойтись — описать нужное изменение в итоговом отчёте.
7. **Публичные сигнатуры фасада** (`XxxModule`) сохранить ровно как в заглушке; можно добавлять новые публичные
   методы, но другие модули на них не рассчитывают.
8. **Тесты** — JUnit 5 на чистую логику (разбор настроек, формулы, таблицы шансов) в
   `src/test/java/core/amazake/systems/corerpg/<модуль>/`.
9. Сборка: `./gradlew azs test --no-daemon -q` (Java 21, API систем уже лежит в `libs/`). Код должен собираться
   без ошибок; предупреждения `-Xlint:all` допустимы, но лучше без них.

## Общие сервисы (готовы, не менять)

| Класс | Что даёт |
|---|---|
| `CoreRpgSystem` | `context()`, `settings()`, `keys()`, `profiles()`, `aspects()`, `progression()`, `sharpen()`, `seals()`, `combat()`. Загружает профиль при входе и при включении (онлайн-игроки), затем в потоке игрока: `aspects().refresh(player)` и `progression().onProfileLoaded(player, profile)`. При выходе — `profiles().unload`. Таймер записи профилей. В `disable` снимает модификаторы атрибутов и сохраняет профили |
| `util.Keys` | Ключи PDC: `sharpenLevel`, `sharpenConsumable`, `sealId`, `sealColor`, `sealBound`, `sealConsumable`, `projectileSharpen`, `projectileWeaponKind`, `key(String)` для своих; `LEGACY_*` — ключи старых плагинов (`zatochka:sharpen_level`, `zatochka:consumable_id`, `magicseals:seal_id`, `magicseals:seal_color`, `magicseals:bound_to`, `magicseals:consumable`) |
| `util.Text` | MiniMessage, префикс, `num(double)` для красивых чисел |
| `util.Configs` | `load(context, "file.yml")` |
| `util.Gear` | `kind(item)`: ARMOR, WEAPON_1H, WEAPON_2H, RANGED, SHIELD, TOOL или null; `Kind.sharpenCategory()` (armor / weapon_1h / weapon_2h), `Kind.sealType()` (WEAPON / ARMOR / SHIELD / null), `Kind.attacks()`; `grade(item)`: C, B, A (золото — B) |
| `profile.PlayerProfile` | record: `classId`, `professionId`, `level`, `exp` (внутри уровня), `freePoints`, `primary`, `agility`, `stamina`, `luck` + `with…` |
| `profile.ProfileStore` | `get(uuid)` (никогда не null), `isLoaded`, `update(uuid, op)` (атомарно; для незагруженного — null), `readOffline/writeOffline` (вне тика) |
| `aspect.Aspect` | ключи аспектов = ключи бонусов печатей/классов: attack_damage, armor, max_health, movement_speed, attack_speed, knockback, knockback_resistance, range, resistance, resistance_pvp, crit_chance, crit_damage, lifesteal, regen, heal_on_kill, stat_primary/agility/stamina/luck, accuracy, evasion, exp_bonus, gold_bonus, status_bleed/poison/burn, armor_sharpen, resource, proc. Целевые: `resistance:<цель>`, `damage_vs:<цель>` |
| `aspect.AspectSnapshot` | неизменяемый снимок; `Builder.add(key|Aspect, value)` |
| `aspect.AspectContributor` | `contribute(player, profile, builder)` в потоке игрока; `order()`: 0 — сырые бонусы (классы, печати, заточка), 100 — формулы параметров, 200 — закрытые аспекты профессии |
| `aspect.AspectService` | `register(contributor)` (в enable модуля), `get(uuid)`, `compute(player)` (без применения), `refresh(player)` (поток игрока), `refreshLater(player)` (любой поток). Применяет как атрибуты: attack_damage, armor, max_health, movement_speed, attack_speed, knockback, knockback_resistance, range. Остальные аспекты читает бой и другие модули. Сам пересчитывает при смене экипировки (события надевания, слота, инвентаря, подбора, выброса, поломки, респавна) |

Порядок модулей: progression → sharpen → seals → combat → papi (enable), обратный при disable.

## Модуль A: progression (классы, профессии, уровни, параметры)

Источник функционала: RoyalQuestClasses v1 и v3 (`/tmp/claude-0/-home-user-core-rpg-amz/015ee473-2663-5d47-be56-2048160629df/scratchpad/classes/RoyalQuestClasses` и `.../classes2/RoyalQuestClasses`).
Сохранить всё, что было в плагине: выбор класса в GUI при первом входе (один раз), выбор профессии с уровня профессии
(GUI и по имени, русские и английские названия), меню персонажа, меню параметров, блокировка опыта до выбора
профессии (сообщение не чаще раза в 15 с), конвертация ванильного опыта (ванильный отменяется), очки параметров,
админ-команды. Исправить баги v3, описанные в артефакте: двойное наложение модификаторов, неприменение бонусов
классов из конфига, захардкоженный 20 уровень, один уровень за начисление.

Файлы: `progression.yml`, `classes.yml`. Классы: `progression/*`, меню `progression/menu/*`, команды
`progression/command/*`.

**Классы и профессии (ключи как в плагине, имена из артефакта):**

| Ключ | Имя | Главный параметр | Иконка | Профессии |
|---|---|---|---|---|
| swordsman | Мечник | Сила | IRON_SWORD | crusader «Паладин», dark_knight «Берсерк» |
| archer | Лучник | Точность | BOW | sniper «Снайпер», hunter «Охотник» |
| mage | Маг | Интеллект | BLAZE_ROD | warlock «Чернокнижник», wizard «Клирик» |
| thief | Асассин | Сила | GOLDEN_SWORD | rogue «Разбойник», assassin «Жнец» |

Описания — из карточек артефакта (раздел «Классы» и «Профессии» в `docs/economy.html`) и плагина.
В `classes.yml` у класса/профессии: `display-name`, `description` (список строк), `icon`, `primary-stat` (у класса),
`parent` (у профессии), `bonuses` (список), `locked-aspects` (у профессии), `passives` (список ключей, читает бой).

Форматы бонусов: `aspect:<ключ>:<значение>` (основной; ключи аспектов или целевые `resistance:fire`),
`attribute:<ИМЯ>:<значение>` (совместимость с плагином: ATTACK_DAMAGE/GENERIC_ATTACK_DAMAGE и т. п. переводятся в
аспект: attack_damage, armor, max_health, movement_speed, attack_speed, attack_knockback→knockback,
knockback_resistance, entity_interaction_range→range; прочие — предупреждение), `effect:<ЭФФЕКТ>:<уровень>[:<длительность>]`
(0 = постоянно; постоянные эффекты переустанавливаются при входе, респавне и после молока; при disable и смене класса
снимаются только наложенные системой), `permission:<узел>` (PermissionAttachment от `context.plugin()`, снимается в
disable/смене).

Значения по умолчанию (перебаланс артефакта под масштаб Minecraft):

| Класс/профессия | bonuses | locked-aspects | passives |
|---|---|---|---|
| swordsman | aspect:max_health:4, aspect:armor:2, aspect:knockback:0.1 | — | — |
| archer | aspect:movement_speed:0.01, aspect:crit_chance:5, aspect:range:0.5 | — | headshot |
| mage | aspect:max_health:2, effect:NIGHT_VISION:0, aspect:resistance:chaos:0.05 | — | — |
| thief | aspect:movement_speed:0.015, aspect:attack_speed:0.16, aspect:crit_chance:10, aspect:evasion:4 | — | backstab |
| crusader (Паладин) | aspect:max_health:4, aspect:armor:2, effect:REGENERATION:0 | crit_damage | — |
| dark_knight (Берсерк) | aspect:attack_damage:2, aspect:attack_speed:0.3, aspect:knockback:0.3 | regen | rage |
| sniper | aspect:attack_damage:1.5, aspect:crit_damage:25, aspect:range:1.0 | attack_speed | — |
| hunter (Охотник) | effect:SPEED:0, aspect:evasion:4 | knockback | — |
| warlock | effect:STRENGTH:0, aspect:damage_vs:monster:10 | regen | — |
| wizard (Клирик) | effect:FIRE_RESISTANCE:0, aspect:regen:0.2, aspect:armor:2 | crit_damage | — |
| rogue (Разбойник) | aspect:movement_speed:0.01, aspect:crit_damage:25, aspect:lifesteal:3 | evasion | dual_wield |
| assassin (Жнец) | aspect:attack_speed:0.4, aspect:movement_speed:0.015, aspect:evasion:6 | knockback | — |

Закрытый аспект профессии: вкладчик order 200 обнуляет этот аспект (итог не может быть > 0).

**Уровни (`progression.yml`):** `max-level: 71`, `profession-level: 21`, `block-exp-until-profession: true`,
`open-class-menu-on-first-join: true`, `vanilla-exp-multiplier: 1.0` (ванильные сферы опыта → кастомный опыт, ванильный
отменяется), `points-every-levels: 5`, `points-per-step: 4`, `exp-table` — опыт, нужный ДЛЯ уровня N (2..71),
значения из файла `/tmp/claude-0/-home-user-core-rpg-amz/015ee473-2663-5d47-be56-2048160629df/scratchpad/exptable.json`
(таблица RQ ÷ 200, продолжена до 71). Нет значения — формула `exp-formula: {base: 100, exponent: 1.5}`. Повышение
сразу на несколько уровней за одно начисление. Опыт с аспектом `exp_bonus`: ×(1 + exp_bonus/100). Сообщение о
повышении, звук, при `points-every-levels` — сообщение об очках. При достижении уровня профессии без профессии —
сообщение со списком и открытие меню профессии.

**Параметры (`progression.yml`, раздел `stats`):** эффективные очки = вложенные в профиле + аспекты stat_* (печати,
+11). Вкладчик order 100 превращает их в аспекты:

- главный: attack_damage +0.5 за очко, +3 за каждые 10; accuracy + главный/5;
- выносливость: max_health +2 за очко;
- ловкость: attack_speed +0.01 за очко, +0.1 за каждые 10; evasion +2 за каждые 10;
- удача: рейтинг крита 0.33 за очко + 1 за каждые 10; crit_chance += 100 × 0.6 × (1 − 0.999^(рейтинг × (60.1007 −
  уровень × 0.6491))), не больше 57; accuracy +2 за каждые 10; crit_damage = база 50 + 5 за каждые 10 удачи
  (база добавляется всем).

Все коэффициенты — в конфиге. Очки вкладываются только вверх (сброс — админом). Меню параметров: ЛКМ +1, Shift+ЛКМ +10,
лор показывает, что даёт параметр и сколько уже даёт.

**Команды** (A регистрирует): `/class` (алиас `класс`): без класса — меню выбора, с классом — меню персонажа;
`/class choose <класс>`; `/class admin reset <ник> class|profession|stats|all`, `/class admin setlevel <ник> <1..max>`,
`/class admin giveexp <ник> <опыт>`, `/class admin set <ник> class|profession <ключ>`, `/class admin info <ник>`,
`/class admin reload` (перечитывает всю систему: `system.reload(context)` в try/catch с сообщением). Админ-команды
работают с игроками онлайн. `/profession [имя]` (алиасы `профессия`, `prof`); `/stats` (алиасы `параметры`, `params`),
`/stats add <primary|agility|stamina|luck|сила|точность|интеллект|ловкость|выносливость|удача> [кол-во]`. Автодополнение
всего. Сброс stats возвращает все вложенные очки в свободные.

**Фасад (заглушка `ProgressionModule`):** `onProfileLoaded`, `addExp`, `expToNext`, `maxLevel`, `professionLevel`,
`className`, `professionName`, `primaryStatName`. Дополнительно публичный метод для боя:
`java.util.Set<String> passives(java.util.UUID uuid)` — ключи пассивок класса и профессии игрока (любой поток).

После изменения профиля: `system.profiles().update(...)`, затем `system.aspects().refresh(player)` (поток игрока).

## Модуль B: sharpen (заточка)

Источник: Zatochka (`/tmp/claude-0/-home-user-core-rpg-amz/015ee473-2663-5d47-be56-2048160629df/scratchpad/zatochka/zatochka`). Сохранить весь функционал: меню
45 слотов с предметом из руки, слотами камня и руны, превью шансов, кнопками «Улучшить»/«Закрыть», подсказками,
возвратом расходников при закрытии, переоткрытием после попытки с остатками, звуками, лором предмета, расходниками
(crumb, shard, cube, black, white, red, purple, rune_save, rune_cold — названия, материалы, лор из плагина),
NPC-открытием по имени сущности (по умолчанию «Королевский алхимик», без зависимости от Citizens), `/zatochka info`.

Решения артефакта:

- **Режим шансов** `mode: royal-quest` (по умолчанию) — таблицы RQ по категории (armor / weapon_1h / weapon_2h),
  грейду (C, B, A) и камню (shard, black, white, red, purple) для +1..+10, успех и уничтожение, из
  `/tmp/claude-0/-home-user-core-rpg-amz/015ee473-2663-5d47-be56-2048160629df/scratchpad/rq/sharpen_rq.json`
  (формат: `{категория: {success|destroy: {C|B|A: [[5 камней] × 10 уровней]}}}`; 0.5 означает «<1%»). Режим
  `simple` — таблицы плагина (success/destroy по категории) × множители камней: black 2, white 4, red 6, purple 8.
  Крошка (только грейд C) и Ашкалотский кубик берут колонку Осколка; кубик никогда не уничтожает. Руна: при провале
  уровень не меняется и предмет цел.
- Провал без руны: бросок уничтожения; не уничтожен — уровень −1 (не ниже 0).
- **Максимум:** C +10, B и A +11.
- **+11 (RQ):** только с +10, для B и A. Нужен второй предмет того же материала в отдельном слоте меню; камень не
  нужен. Шанс по заточке жертвы: 0→10%, 1→20, 2→30, 3→35, 4→40, 5→50, 6→60, 7→70, 8→80, 9→90, 10→100 (в конфиге).
  Жертва тратится всегда; провал оставляет +10. Каждый надетый предмет +11 даёт +1 ко всем четырём параметрам
  (вкладчик: stat_primary/agility/stamina/luck +1).
- **Бонусы** (как в плагине, кумулятивные): броня 10, 22, 36, 52, 70, 90, 112, 136.2, 162.1, 200, 260; оружие 5, 11,
  18, 26, 35.1, 45, 56.1, 68.2, 81, 100, 130. Щит — без боевого бонуса (как в плагине), но точится.
- Вкладчик order 0: `armor_sharpen` = средний % бонуса по заточенным надетым частям брони (как CombatListener
  плагина), плюс stat_* от +11. Сам урон модуль B не меняет — это делает бой через `bonusPercent`.
- Старые предметы: читать `Keys.LEGACY_ZATOCHKA_LEVEL` и переносить на новый ключ при первом касании; старые
  расходники с `zatochka:consumable_id` принимаются.
- Лор: строка «Заточка: +N (Атака +X% ×M)» как в плагине; удаляет старые строки заточки.

Файл `sharpen.yml`. Команды: `/sharpen` (алиасы `zatochka`, `zat`, `заточка`): `menu`, `info`, `list` (камни и руны
с шансами для предмета в руке), `give <ник> <расходник|all|stones|runes> [1..64]` (русские имена тоже: осколок,
крошка, кубик, чёрный, белый, красный, пурпурный, руна сохранения → rune_save…), `set <ник> <0..11>` (заточка предмета
в руке игрока), `reload` (перечитать sharpen.yml). Админ: `core.sharpen.admin`. После изменения предмета —
`system.aspects().refreshLater(player)`.

**Фасад:** `level`, `bonusPercent`, `maxLevel` (как в заглушке).

## Модуль C: seals (магические печати)

Источник: MagicSeals (`/tmp/claude-0/-home-user-core-rpg-amz/015ee473-2663-5d47-be56-2048160629df/scratchpad/seals`). Сохранить весь функционал: меню мастера со
слотами камня, кристалла, сургуча и сердца, действия APPLY / CHANGE / WAX / ACCOUNT_WAX / ARKONITE / HEART с теми же
правилами и сообщениями, привязку к игроку («Персональный предмет», чужой не может менять), лор, `list`, `info`,
`give`, `reload`, NPC «Мастер зачарования» по имени сущности.

Решения артефакта:

- Каталог `src/main/resources/seals.yml` УЖЕ сгенерирован (144 печати: 57 плагина + 87 Royal Quest). Не менять
  раздел `seals:`; добавить перед ним раздел `settings:` с комментариями: `roll-weights` 60/27/10/3,
  `arkonite-reroll-chance: 100`, `class-filter: true`, `npc-name`, материалы и имена расходников.
- Грейд — `Gear.grade` (золото = B, единое для всех модулей): stone_c для C, stone_b для B, stone_a для A.
- Тип для печатей — `Gear.kind(item).sealType()`; ACCESSORY в Minecraft не встречается.
- **Выбор печати:** включённые (`enabled` ≠ false), подходящие по типу, и при `class-filter` — у которых `classes`
  содержит any, класс или профессию игрока. Если по классу пусто — все подходящие по типу.
- Бонусы цвета: значение `~` — у этого цвета бонуса нет. Вкладчик order 0 суммирует печати надетой брони, а также
  предмета в основной руке (если это оружие или щит) и во второй руке (щит или оружие), ключи → `builder.add(key, v)`.
- Лор: «Печать: <имя>», «Цвет печати: <цвет>», строки «• <аспект>: +<значение><ед.>» (проценты для resistance
  ×100%, crit_*, lifesteal, exp_bonus, gold_bonus, status_*, damage_vs; для resource/proc — «(пока не действует)»),
  «Персональный предмет».
- Старые предметы MagicSeals: `Keys.LEGACY_SEAL_ID/COLOR/BOUND` читаются и переносятся (id плагинных печатей
  совпадают); старые расходники `magicseals:consumable` принимаются.
- Сопротивление урону печати сами НЕ применяют — это делает бой (combat.yml).

Команды: `/seals` (алиасы `seal`, `печати`): `menu`, `list [оружие|броня|щит] [класс] [страница]`, `info`,
`give <ник> <расходник|all|stones|wax> [1..64]` (русские имена: изумруд, сапфир, морион, кристалл, сургуч, особый,
арконит, сердце), `set <ник> <печать> <green|blue|purple|orange|зелёная|синяя|фиолетовая|оранжевая>` (на предмет в
руке игрока), `reload`. Админ: `core.seals.admin`. После изменения предмета — `system.aspects().refreshLater(player)`.

**Фасад:** `sealName`, `sealColorName`, `catalogSize`, `isBound(item)` (персональный предмет: меню других модулей
тоже не принимают его в слоты).

## Модуль D: combat (бой, регенерация, пассивки)

Источник: CombatListener Zatochka (бонус оружия, делитель брони), CombatListener MagicSeals (сопротивление),
CombatCalculator RoyalQuestClasses (крит, уворот, поглощение). Файл `combat.yml`, всё настраивается.

Атакующий-игрок (`EntityDamageByEntityEvent`, HIGH, ignoreCancelled; ближний бой — предмет в основной руке, снаряд —
снимок при выстреле в PDC снаряда: `EntityShootBowEvent` для луков/арбалетов, `ProjectileLaunchEvent` для трезубца;
ключи `keys.projectileSharpen`, `keys.projectileWeaponKind`):

1. заточка оружия: ×(1 + `sharpen().bonusPercent(оружие)`/100);
2. пассивки (`progression().passives(uuid)`; таблица `passives:` в combat.yml с enabled и числами):
   `rage` — +2% урона за каждые 5% потерянного здоровья, максимум +40%; `headshot` — попадание снаряда выше уровня
   глаз цели −0.3 блока ×1.5; `backstab` — удар сзади (угол между взглядом цели и направлением на атакующего > 120°)
   всегда критический; `dual_wield` — если во второй руке меч или топор, + 60% атаки этого предмета (ATTACK_DAMAGE
   модификаторы предмета или значение по таблице материала);
3. `damage_vs:monster` +% если цель Monster; `damage_vs:fire|air|earth|water` +% ко всем целям, уменьшенный на
   `resistance:<та же стихия>` цели-игрока;
4. крит: шанс = аспект crit_chance (кап 57%); при крите ×(1 + crit_damage/100); частицы и звук;
5. статусы (шанс %): bleed → WITHER 3 с, poison → POISON 3 с, burn → огонь 3 с (в конфиге);
6. вампиризм (MONITOR, итоговый урон × lifesteal%, кап 20%) и `heal_on_kill` (EntityDeathEvent): лечить атакующего
   ТОЛЬКО через `context.runOnceForPlayer(attacker, …)` — событие идёт в потоке региона цели.

Защищающийся-игрок:

7. уворот: шанс = clamp((evasion цели − accuracy атакующего (0 у мобов))/100, 0, 0.85) → удар отменяется, звук и
   actionbar «Уворот!»;
8. заточка брони (`EntityDamageEvent`, кроме VOID, STARVATION, SUICIDE, KILL, CUSTOM, WORLD_BORDER): урон ÷ (1 +
   armor_sharpen/100);
9. сопротивление: урон × (1 − min(кап, resistance + resistance:<цель> + resistance_pvp если атакует игрок)), кап 0.40
   (решение артефакта вместо 0.60 плагина). Цели по причине/атакующему: fire (FIRE, FIRE_TICK, LAVA, HOT_FLOOR,
   CAMPFIRE, атакующий Blaze/MagmaCube), chaos (MAGIC, WITHER, DRAGON_BREATH, SONIC_BOOM), undead (атакующий из
   `Tag.ENTITY_TYPES_SENSITIVE_TO_SMITE`), humanoid (Player, AbstractVillager, Illager, Witch, PiglinAbstract),
   water (DROWNING, атакующий Drowned/Guardian), air (FALL, FLY_INTO_WALL, LIGHTNING, атакующий Phantom/Breeze/Ghast),
   earth (FALLING_BLOCK, SUFFOCATION, CONTACT), poison (POISON, атакующий Spider/CaveSpider/Bee), animal (атакующий
   Animals, Wolf), arthropod (`Tag.ENTITY_TYPES_SENSITIVE_TO_BANE_OF_ARTHROPODS`).

Регенерация: задача игрока каждые 20 тиков (`runForPlayer`, стартовать для онлайн-игроков при enable и при входе)
лечит на аспект `regen` (не мёртвого, не выше максимума). Порядок обработчиков не должен дважды применять одно и
то же. Чистая математика — в отдельном классе с тестами.

**Фасад:** enable/reload/disable и `passiveDescription(key)` — описание пассивки по числам combat.yml для меню
прогрессии.

## Модуль E: papi (PlaceholderAPI)

Загрузчик системы видит классы сервера, но не плагинов, поэтому наследник `PlaceholderExpansion` нельзя загрузить
обычным путём. Решение (уже настроено в `build.gradle.kts`): исходник моста —
`src/papiBridge/java/core/amazake/systems/corerpg/papibridge/CoreRpgExpansion.java` (компилируется против
PlaceholderAPI 2.11.6 и Paper; в `.azs` кладётся как `papi-bridge/core/amazake/systems/corerpg/papibridge/CoreRpgExpansion.class.bin`).
Мост зависит только от PlaceholderAPI, Bukkit и JDK: конструктор
`(String identifier, String author, String version, java.util.function.BiFunction<org.bukkit.OfflinePlayer, String, String> resolver)`,
`persist()` = true, `canRegister()` = true, `onRequest` делегирует в resolver.

`papi/PapiModule` + `papi/BridgeLoader` (наследник `ClassLoader` с родителем — загрузчиком плагина PlaceholderAPI;
определяет класс из байтов ресурса; ресурс читать без кеша URL) + `papi/Placeholders` (чистое разрешение по
снимкам, любой поток, тестируемо). Регистрация `register()` рефлексией, в `disable` и при `PluginDisableEvent`
PlaceholderAPI — `unregister()`; при `PluginEnableEvent` PlaceholderAPI после системы — зарегистрировать. Включается
при `settings().papiEnabled()`, идентификатор `settings().papiIdentifier()` (по умолчанию core).

Предмет в руке нельзя читать из чужого потока: E регистрирует вкладчика аспектов order 300, который в потоке игрока
кладёт в `ConcurrentHashMap` снимок «руки» (заточка, макс., бонус, имя и цвет печати основной руки) — пересчёт уже
вызывается при смене слота и экипировки.

Плейсхолдеры (`%core_<имя>%`): class, class_key, profession, profession_key, level, max_level, exp, exp_needed,
exp_progress (0..100, 1 знак), points, stat_primary, stat_agility, stat_stamina, stat_luck, stat_primary_name,
has_class, has_profession, needs_profession (yes/no → «да»/«нет» в конфиге? — возвращать true/false), aspect_<ключ>
(любой Aspect, `Text.num`), crit_chance (то же что aspect_crit_chance), sharpen, sharpen_max, sharpen_bonus, seal,
seal_color. Для игрока без загруженного профиля — пустая строка.
