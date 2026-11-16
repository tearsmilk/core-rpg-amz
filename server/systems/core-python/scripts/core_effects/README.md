# core_effects (効果) — Python-система

Порт Java-системы `core-effects` в core-python: те же четыре эффекта (кровотечение, паника, тепло, проклятье),
та же команда, тот же `config.yml`, те же ключи PDC и модификаторов, те же плейсхолдеры. Как работают сами эффекты,
накладки, иконки и пакет ресурсов — в `core-effects/README.md` (пакет `core-effects/pack/` остаётся там, система
ссылается на те же шрифты `coreeffects:overlay` / `coreeffects:icons` и модель `coreeffects:bandage`).

## Переход с Java-системы

1. `amazake system disable core-effects`. Если Java-система включена (её `/effects` в карте команд), core_effects
   при загрузке пишет ошибку и ничего не делает (игроков не берёт, команда отвечает «выключен»): иначе эффекты
   считались бы дважды. После выключения Java-системы — `/python reload core_effects`.
2. Папку `scripts/core_effects/` залить в `/systems/core-python/scripts/core_effects/`; `config.yml` в ней — копия
   прод-конфига `systems/core-effects/config.yml` (v2). Правки конфига — здесь, затем `/effects reload`.
3. `/python load core_effects`. Эффекты игроков (`coreeffects:state` в PDC) подхватываются как есть, старые бинты
   (`coreeffects:bandage`) работают, фантомы и модификаторы прошлого запуска убираются при входе/загрузке.

## Команда

`/effects give|remove|clear|list|bandage|reload` — как у Java (`README` core-effects). Псевдонимы `/coreeffects`,
`/эффекты`, право `coreeffects.admin`, автодополнение то же.

## Файлы

| Файл | Что | Java-оригинал |
|---|---|---|
| `main.py` | события, команда, игроки, плейсхолдеры, загрузка/выгрузка | `CoreEffectsSystem`, `EffectManager`, `EffectsCommand` |
| `fx.py` | эффекты игрока (тик, атрибуты, ванильные помощники, накладка, actionbar), фантомы | `PlayerFx`, `Phantoms` |
| `items.py` | бинт, метки «чем лечится», сведения о предмете для проклятья | `Items` |
| `papi.py` | регистрация расширения PlaceholderAPI | `PapiHook`, `BridgeLoader` |
| `bridge.py` | байты класса-моста `PlaceholderExpansion` (собираются в Python, чистая Java при запросе) | мост `CoreEffectsExpansion` |
| `settings.py` | разбор `config.yml` (те же ключи, пределы, предупреждения) | `Settings` |
| `logic.py` | чистая логика | `EffectBook`, `EffectType`, `Durations`, `Range`, `*Rules`, `Disorient`, `CurseFilter`, `Overlay` |
| `common.py` | общие типы Java, ключи, отправка сообщений, контекст/плагин, `f32`, Java-задачи без Python | `Keys`, `Text` |
| `tests_core_effects.py` | тесты чистой логики — `python3 tests_core_effects.py` (без сервера) | `src/test/**` |

## Отличия от Java (ради интерпретатора GraalPy)

- **Задача игрока — только пока на нём есть эффекты** (Java тикала каждого онлайн-игрока каждый тик). Шаг —
  2 тика; пока идёт паника с дезориентацией или игрок без сознания — 1 тик (толчки WASD, гашение скольжения).
  Счётчики внутри шага считаются по каждому тику (часы кровотечения и обморока, жажда, приступы тошноты, интервалы
  частиц/атрибутов/actionbar/сохранения); первый запуск задачи (через 1 тик) считается за 1 тик. Время, урон,
  обморок, предупреждения и смерть — те же; конец эффекта может наступить на 1 тик позже.
- Фантомы двигаются как у Java (шаг `approach-speed × 2` раз в 2 тика); без дезориентации — каждый вызов (2 тика).
- **Слушатели `EntityDamageEvent`, `EntityCombustEvent`, `EntityTargetEvent` не нужны** (для Python слишком частые):
  фантомы неуязвимы (`setInvulnerable`), без ИИ (цели не выбирают), зомби/скелеты не горят днём
  (`setShouldBurnInDay(false)`), огонь любого фантома гасится в его ходе (`setFireTicks(0)` раз в 2 тика); урон от
  сущностей по фантому отменяет `EntityDamageByEntityEvent` (удар игрока растворяет фантома). Остаточная разница:
  урон, проходящий сквозь неуязвимость (VOID, `/kill`), не отменяется; фантомы прошлых запусков узнаются только по
  метке `coreeffects:phantom` при входе/загрузке (их убирает уборка), а не в событиях урона.
- `EntityDamageByEntityEvent`, `BlockBreak/Place`, `PlayerInteract*` — частые события: выход сразу, пока никто не
  без сознания (`fx.気絶者`) и живых фантомов нет (`fx.幻影ID`).
- Кулдаун бинта — по номеру тика сервера (`Bukkit.getCurrentTick()`), как у Java по тикам.
- Все числа, уходящие в `float`-параметры Java (громкость/высота звука, yaw, размер пыли), — через `common.f32`:
  GraalPy не сужает double → float с потерей точности (`playSound(..., 0.7, 0.6)` иначе бросает исключение).
- Метка бинта пишется `PersistentDataType.BOOLEAN` (в NBT — тот же байт 1, что `BYTE` у Java).

## Потоки и уборка

- Игрок — только в его потоке; фантомы — в потоке их региона (чужие — Java-задачей в планировщике сущности); взрывное
  зелье и удары приходят в потоке региона — игроку через `run_once_for_player`; команды только читают состояние
  (под замком `効果帳`). `/effects reload` читает `config.yml` в `run_async`.
- **PlaceholderAPI без Python.** Класс Python — наследник `PlaceholderExpansion` — GraalPy создать не может (адаптеру
  нужен загрузчик, видящий и PlaceholderAPI, и GraalPy). Как у Java-системы, мост определяется в загрузчике-потомке
  загрузчика PlaceholderAPI (`URLClassLoader` над `data/core_effects/papi-bridge/`, байты собирает `bridge.py`).
  Его `onRequest` — чистая Java: два `Map.get` в `common.PAPI表` (`ConcurrentHashMap`: UUID → готовые ответы,
  `""` → ответы по умолчанию и `<эффект>_name`). Ответы готовит поток игрока раз в 10 тиков и при каждом изменении,
  в Java отдаются только при изменении (секунды — раз в секунду). Запросы MythicHUD не берут GIL системы.
  Идентификатор, автор `tearsmilk`, версия `1.0.0`, `persist=true`, проверка «не наш — не трогаем» — как у Java.
  `unregister()` — только в глобальном потоке (из другого — Java-задачей туда).
- Модификаторы атрибутов (`coreeffects:panic_*`, `faint_*`) и ванильные «Тьма»/«Слепота»/«Тошнота» накладываются
  через `amazake.add_modifier` / `add_effect` — при выгрузке их снимает ядро core-python.
- `@on_unload`: снимается расширение PlaceholderAPI; каждому игроку — сохранение `coreeffects:state`, `clearTitle`,
  удаление фантомов. Python-задачи в этот момент уже не планируются, поэтому для чужих потоков это чистые
  Java-`Runnable` (`MethodHandleProxies`): сохранение и заголовок — через `amazake.run_java_for_player`
  (= `SystemContext.runOnceForPlayer` ядра: при остановке сервера выполняется сразу, иначе в потоке игрока; запасной
  путь — `amazake.run_java_on_entity`, при остановке сервера — запись напрямую), фантомы — `run_java_on_entity`,
  расширение PlaceholderAPI — `run_java_global`. Страховка — сохранение раз в 100 тиков.
- Объект плагина (для `showEntity`) — `amazake.plugin()`, классы — `amazake.find_class()`. На старом
  `core-python.azs` (без этого API, до его передеплоя) `SystemContext` и плагин берутся как раньше — отражением по
  закрытым именам ядра (`ScriptApi.script` → `PyScript.platform` → `BukkitPlatform.context`); при неудаче —
  предупреждение (один раз), фантомов нет, уборка при выгрузке — только из потоков игроков. Эту ветку (`_新API` в
  `common.py`) убрать после передеплоя core-python.
