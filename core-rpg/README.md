# core-rpg — RPG-система для Amazake

Цельная система (`.azs`) для ядра [Amazake](https://github.com/tearsmilk/amazake) (Paper 1.21.11), собранная по
артефакту экономики сервера. Заменяет три плагина: **Zatochka**, **MagicSeals** и **RoyalQuestClasses**. Держать их
вместе с системой нельзя: они перечислены в `conflicts`.

| Модуль | Что делает |
|---|---|
| Прогрессия | 71 уровень (таблица опыта RQ ÷200), 4 класса (Мечник, Лучник, Маг, Асассин), 8 профессий с 21 уровня, очки параметров, меню класса, профессии, персонажа и параметров |
| Аспекты | Общий движок характеристик: бонусы классов, параметров, заточки и печатей сводятся в один снимок и ставятся временными модификаторами `core-rpg:aspect_*` |
| Заточка | До +10 камнями грейдов D/C/B/A (обычные и зачарованные), руны защиты, +11 через жертву, таблицы шансов RQ по категории и грейду |
| Печати | 144 печати (плагин + RQ), 4 цвета (60/27/10/3), камни, кристаллы крови, воск, сердце Аркона, персональные предметы |
| Бой | Крит, точность и уклонение, сопротивления (кап 40%), урон по типам целей, пассивки (ярость, выстрел в голову, удар в спину, два клинка), бонус заточки оружия и брони |
| PlaceholderAPI | Плейсхолдеры `%core_…%`, если на сервере стоит PlaceholderAPI |

## Сборка и установка

```bash
./gradlew azs
```

Готовый файл лежит в `build/libs/core-rpg.azs`. Его кладут в папку `systems/` сервера и в консоли выполняют:

```
amazake system enable core-rpg
```

Чтобы обновить систему без остановки сервера: `SERVER=user@host SERVER_DIR=/путь/к/серверу ./deploy.sh`, затем
`amazake system restart core-rpg`. Тесты запускаются командой `./gradlew test`.

## Команды

Права админа по умолчанию есть у операторов (`op`).

| Команда | Кто | Что |
|---|---|---|
| `/class` | все | меню выбора класса или персонажа; `/class choose <класс>`, `info`, `menu`, `help` |
| `/profession [имя]` | все | выбор профессии с 21 уровня (без имени — меню) |
| `/stats [add <параметр> [кол-во]]` | все | распределение очков параметров |
| `/myclass [text]` | все | класс, профессия, уровень и опыт |
| `/sharpen` (`/zatochka`, `/заточка`) | все | меню заточки; `info`, `list`, `help` |
| `/seals` (`/seal`, `/печати`) | все | меню мастера печатей; `list [оружие\|броня\|щит] [класс] [страница]`, `info` |
| `/class admin info\|reset\|setlevel\|giveexp\|set <ник> …` | `core.class.admin` | управление персонажем игрока; `/class admin reload` |
| `/sharpen give <ник> <расходник\|all\|stones\|runes> [кол-во]` | `core.sharpen.admin` | выдать камни и руны (`/sharpen give Ник all 64`) |
| `/sharpen set <ник> <0..11>` | `core.sharpen.admin` | уровень заточки предмета в руке; `/sharpen reload` |
| `/seals give <ник> <расходник\|all\|stones\|wax> [1..64]` | `core.seals.admin` | выдать расходники печатей (`/seals give Ник all 64`) |
| `/seals set <ник> <печать> <цвет>` | `core.seals.admin` | печать на предмет в руке игрока; `/seals reload` |

## Плейсхолдеры PlaceholderAPI

Идентификатор задаётся в `config.yml` (`placeholderapi.identifier`, по умолчанию `core`). Пока профиль игрока не
загружен, плейсхолдер возвращает пустую строку.

| Плейсхолдер | Значение |
|---|---|
| `%core_class%`, `%core_class_key%` | класс (название или ключ) |
| `%core_profession%`, `%core_profession_key%` | профессия |
| `%core_level%`, `%core_max_level%` | уровень и максимум |
| `%core_exp%`, `%core_exp_needed%`, `%core_exp_left%`, `%core_exp_progress%` | опыт, нужно до уровня, осталось, прогресс в % |
| `%core_points%` | свободные очки параметров |
| `%core_stat_primary%`, `_agility`, `_stamina`, `_luck` | вложенные очки |
| `%core_stat_primary_total%` и т. д. | параметр с учётом заточки и печатей |
| `%core_stat_primary_name%` и т. д. | название параметра (у мага — Интеллект и т. п.) |
| `%core_has_class%`, `%core_has_profession%`, `%core_needs_profession%` | true/false |
| `%core_crit_chance%` | шанс крита |
| `%core_sharpen%`, `%core_sharpen_max%`, `%core_sharpen_bonus%`, `%core_sharpen_text%` | заточка предмета в руке |
| `%core_seal%`, `%core_seal_color%` | печать на предмете в руке |
| `%core_aspect_<ключ>%` | любой аспект: `attack_damage`, `armor`, `max_health`, `movement_speed`, `attack_speed`, `crit_chance`, `crit_damage`, `accuracy`, `evasion`, `resistance`, `resistance_pvp`, `lifesteal`, `regen`, `armor_sharpen`, `exp_bonus` и др. |
| `%core_aspect_resistance:<цель>%`, `%core_aspect_damage_vs:<цель>%` | сопротивление и урон против цели (`fire`, `monster` …) |

Старые имена `%royalquest_…%` можно включить в `papi.yml` (`legacy-names: true`). По умолчанию они выключены.

## Конфиги

Файлы создаются в `systems/core-rpg/` при первом запуске. После правки — `amazake system reload core-rpg`.

| Файл | Что внутри |
|---|---|
| `config.yml` | префикс сообщений, интервал автосохранения, PlaceholderAPI |
| `progression.yml` | таблица опыта, уровень профессии, очки параметров, перевод ванильного опыта |
| `classes.yml` | классы и профессии: параметры, бонусы, пассивки, оружие |
| `combat.yml` | формулы боя, капы, пассивки (числа отсюда же показываются в меню) |
| `sharpen.yml` | шансы заточки, бонусы за уровень, камни, руны, +11 |
| `seals.yml` | каталог печатей, веса цветов, расходники |
| `papi.yml` | тексты «нет класса» и «нет печати», знаки после запятой, старые имена |

Профили игроков хранятся в `systems/core-rpg/players/<uuid>.yml` и сохраняются асинхронно.

## Переход со старых плагинов

Предметы, заточенные в **Zatochka** (`zatochka:sharpen_level`), и печати **MagicSeals** (`magicseals:seal_id`,
`seal_color`, `bound_to`) система читает как свои: переносить предметы не нужно. Перед включением системы удалите из
`plugins/` jar-файлы Zatochka, MagicSeals и RoyalQuestClasses.

## Чего пока нет

- навыков, талантов и маны (печати на ману, энергию и прок-эффекты есть в каталоге, но не выпадают);
- экономики (золото, магазины): аспект `gold_bonus` уже считается и ждёт модуля денег.
