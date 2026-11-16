# core-python

Система Amazake (Paper 1.21.11, форк; собирается под Java 21, на проде Java 25). Позволяет писать системы сервера
**на Python**: папка `systems/core-python/scripts/<имя>/` с `manifest.yml` и `.py`-файлами — это «Python-система».
Python — [GraalPy](https://www.graalvm.org/python/) 25.0.5 (Python 3.12) внутри JVM сервера. Модуль `amazake` даёт
события, команды, задачи по правилам потоков Amazake, конфиги, PDC, файлы, MiniMessage и прямой доступ к любому
классу Java (Bukkit/Paper/плагины). Первая Python-система — **кастомная броня** (`scripts/custom_armor/`, README там).

```
/python list                       список Python-систем и их состояние
/python load|unload <имя>          загрузить / выгрузить
/python reload <имя|all>           перезагрузить (из файлов на диске)
/python info                       версия GraalPy, режим (JIT/интерпретатор), память
```
Право — `corepython.admin` (псевдонимы `/py`, `/corepython`).

---

## Установка и обновление

1. Сборка: `./gradlew test azs` → `build/libs/core-python.azs` (~100 КБ) и `build/python-lib/` (18 jar-ов GraalPy,
   ~150 МБ, + `libs.txt`).
2. `.azs` — в `/systems/` (как любая система: сначала `.azs.new`, сверить sha256, потом переименовать), затем
   `amazake system enable core-python`.
3. **Библиотеки GraalPy** лежат в `/systems/core-python/lib/`. Если их нет, система сама скачивает их из Maven Central
   (`repo1.maven.org`, ~11 с на тестовом сервере) и проверяет каждый файл по sha256 из `libs.txt` внутри `.azs`.
   Если у сервера нет выхода в интернет — `libraries.auto-download: false` и залить `build/python-lib/*.jar` в
   `/systems/core-python/lib/` вручную (файл `python-language-25.0.5.jar` ~100 МБ — через SFTP, API панели такие не
   принимает).
4. Python-системы по умолчанию (`scripts/` из репозитория, сейчас `custom_armor`) распаковываются из `.azs` в
   `/systems/core-python/scripts/` при первом включении — **только если такой папки ещё нет**. Обновить систему на
   проде = залить файлы в `/systems/core-python/scripts/<имя>/` и `/python reload <имя>`.
5. Стандартная библиотека Python распаковывается один раз в `/systems/core-python/cache/` (≈ 24 МБ, делает GraalPy).

Папки на сервере: `lib/` (jar-ы), `scripts/<имя>/` (код), `data/<имя>/` (данные систем: `amazake.storage`),
`runtime/amazake.py` (модуль API, переписывается при запуске — читать можно, править бессмысленно), `cache/`.

**Смена версии GraalPy** (новый `libs.txt`) требует рестарта сервера: классы GraalPy грузятся один раз за жизнь JVM
(см. ниже), новые подхватятся, старые останутся в памяти до рестарта (в логе будет предупреждение).

---

## Как это устроено

```
core-python.azs
 ├─ CorePythonSystem      класс системы (загрузчик). enable() на тике: /python, распаковка scripts, всё остальное — в
 │                         асинхронном потоке (тик не ждёт ни скачивания, ни запуска GraalPy)
 ├─ libs.txt              список jar-ов GraalPy (имя, размер, sha256, путь в Maven Central)
 ├─ host/**.class.bin     код хоста (компилируется против polyglot API) — ресурсами, его определяет HostLoader
 ├─ host/python/amazake.py  модуль API
 └─ default-scripts/      Python-системы по умолчанию

ClassLoader-ы:   сервер ─┬─ SystemClassLoader(core-python.azs)       новый на каждое включение
                         └─ «core-python/graalpy» (lib/*.jar)          ОДИН на всю JVM (System.getProperties())
                               └─ HostLoader (host/**.class.bin)       новый на каждое включение
```

- **Почему загрузчик GraalPy общий.** Замер на JDK 25: если на каждое включение создавать новый загрузчик jar-ов
  GraalPy, после закрытия движка он остаётся в памяти (Truffle держит ссылки; ~18 МБ кучи + классы за раз), а нативную
  библиотеку TruffleAttach JVM разрешает привязать только к одному загрузчику. Поэтому классы GraalPy (15 945 классов,
  ~72 МБ metaspace) грузятся один раз, а `amazake system restart core-python` пересоздаёт только хост, движок и
  контексты. Проверено: после 6 включений/выключений и перезагрузок скриптов живы ровно один HostLoader и один
  загрузчик GraalPy, куча после GC не растёт.
- **Движок один** (`Engine`, общий кэш разобранного кода), **у каждой Python-системы свой контекст** (`Context`): свои
  модули, глобальные переменные, свой GIL. Выгрузка = снять слушатели, команды, задачи, временные атрибуты/эффекты
  игроков, вызвать `@on_unload`, закрыть контекст (`close(cancel=true)`).
- **Загрузка**: модуль выполняется в асинхронном потоке; слушатели и команды, объявленные при этом, включаются только
  когда модуль выполнен целиком (событие не попадёт в наполовину загруженный модуль); ошибка при загрузке — ничего не
  регистрируется, в ответ команде и в лог — трасса Python.
- **Ошибки в обработчиках** не роняют событие: пишутся в лог с трассой Python (файл, строка, функция), одинаковые —
  не чаще раза в 30 с.

### Потоки и GIL

Python вызывается из того потока, где произошло событие или задача: регион игрока, глобальный поток, асинхронный пул.
GraalPy пускает в один контекст несколько потоков **по очереди (GIL)** — проверено тестом: 8 потоков × 200 вызовов
одного обработчика без взаимоблокировок (~0,3 мс на вызов в режиме интерпретатора).

Что из этого следует:
- обработчики одной Python-системы из разных регионов выполняются **последовательно**; разные системы друг другу
  не мешают (у каждой свой GIL);
- GIL переключается между байткодами — составные изменения общих структур (`n[0] += 1` из разных потоков) делать под
  `threading.Lock()`; простые операции над dict/list атомарны;
- **нельзя** из Python ждать то, что делает другой поток этой же системы (future.get(), join, синхронная загрузка
  чанка из чужого региона) — поток держит GIL, и второй поток в Python не войдёт: взаимоблокировка;
- состояние игрока держите в dict по UUID и трогайте только из потока игрока (как в `custom_armor/bonuses.py`).

### Скорость: интерпретатор или JIT

На проде обычный Oracle JDK 25 без JVMCI, поэтому GraalPy работает **без JIT-компиляции (интерпретатор)** —
`/python info` пишет «Interpreted». Предупреждение Truffle об этом выключено (`graalpy.warn-interpreter-only: false`
в `config.yml`) — режим принят осознанно. Замеры (тот же JDK 25+37, что на проде):

| | интерпретатор (прод сейчас) | GraalVM JDK 25 (JIT) |
|---|---|---|
| цикл Python 2 млн итераций (прогретый) | 620–720 мс | 70–150 мс |
| первый контекст после старта JVM | ~3,5 с | ~4,8 с |
| следующие контексты / перезагрузка custom_armor | 0,15–0,6 с | ~0,6–1 с |
| вызов обработчика события | ~0,1–0,3 мс | меньше |

Для обработчиков событий, команд и задач раз в 1–2 секунды интерпретатора хватает с запасом (CPython примерно в
5–15 раз быстрее интерпретатора GraalPy на «голых» циклах). Не делайте на Python: обработку каждого `PlayerMoveEvent`,
тяжёлые циклы по блокам/сущностям каждый тик — это для Java-систем.

Как включить JIT (решение владельца, ничего не сделано):
1. **Образ «Java 25 GraalVM»** (`hostingminecraft/java:java25_graalvm`) — он есть в списке образов панели (вкладка
   Startup/Settings → Docker Image). GraalVM JDK включает JVMCI и Graal по умолчанию: Truffle сам переходит на JIT,
   флаги не нужны (проверено локально на Oracle GraalVM 25.0.4: оптимизирующий рантайм работает с теми же jar-ами).
   Это смена JVM всего сервера — сначала проверить на тестовом.
2. **Флаги JVM** на обычном JDK не помогают: `-XX:+EnableJVMCI` + `--upgrade-module-path=compiler.jar` отвергается
   («The VM does not support the minimum JVMCI API version required by Graal: 25+37 < 25.0.5+9-jvmci-b01») —
   нужен JDK со сборкой JVMCI (то есть GraalVM). К тому же в панели флаги не меняются: переменная `AD_VAR` принимает
   только значения из списка (`--add-opens…`, `--add-modules=jdk.incubator.vector`, `-javaagent…`), `ADMIN_START`
   меняет только поддержка по тикету, своя строка запуска — через запрос `custom_startup` с одобрением поддержки.

---

## Python API (модуль `amazake`)

Полная документация — в docstring-ах `src/host/resources/python/amazake.py` (на сервере — `runtime/amazake.py`).
Имена API — английские, имена в своих системах можно писать по-японски (или как угодно — Python 3 разрешает Unicode
в идентификаторах; ключевые слова остаются английскими). Имена **файлов** `.py` — латиницей.

### Минимальная система

`scripts/hello/manifest.yml`:
```yaml
name: hello            # = имя папки: a-z, 0-9, _ и -
version: 1.0.0
main: main.py
enabled: true          # загружать при включении core-python
commands:              # необязательно: свойства по умолчанию для @amazake.command
  hello:
    permission: hello.use
    aliases: [привет, こんにちは]
```
`scripts/hello/main.py`:
```python
import java
import amazake

# 設定を読む（YAML → dict）
設定 = amazake.config("config.yml", {"挨拶": "<gold>Привет, <name>!"})

@amazake.on("PlayerJoinEvent")                    # короткое имя события — ищется в пакетах Bukkit/Paper
def 参加(event):                                    # поток игрока
    amazake.send(event.getPlayer(), 設定["挨拶"], name=event.getPlayer().getName())

@amazake.command("hello")                          # право и псевдонимы — из manifest.yml
def こんにちは(sender, args):                        # поток отправителя
    amazake.send(sender, "<green>args: <a>", a=" ".join(args))

@こんにちは.tab                                     # автодополнение (фильтр по началу слова — сам)
def 補完(sender, args):
    return ["one", "two"]
```
Затем `/python load hello`. Правка файлов → `/python reload hello`.

### Справочник

| Что | API | Поток |
|---|---|---|
| событие | `@amazake.on(Event, priority="normal", ignore_cancelled=False)`; `Event` — `java.type("…")`, полное или короткое имя (`"EntityDamageByEntityEvent"`) | поток события |
| команда | `@amazake.command(name, permission=, aliases=, description=, usage=)` → `fn(sender, args)`; `@fn.tab` → `fn(sender, args) -> list` | поток отправителя |
| загрузка/выгрузка | `@amazake.on_load` (после модуля, асинхронный поток), `@amazake.on_unload` (до закрытия контекста) | |
| поток игрока | `run_for_player(player, fn, period=20)` (повтор; `return False` — стоп; сам отменяется при выходе), `run_once_for_player(player, fn, delay=1)` | игрока |
| поток региона | `run_in_region(место|сущность|блок, fn, delay=0, period=0)` | региона |
| глобальный / вне тиков | `run_global(fn, delay=0, period=0)` (тики), `run_async(fn, delay_ms=0, period_ms=0)` | |
| проверка | `amazake.owns(игрок|сущность|место|блок)` — можно ли трогать прямо сейчас | |
| конфиг | `amazake.config("file.yml", defaults)` → dict (YAML из папки системы; числовые ключи → строки) | любой |
| файлы | `st = amazake.storage("x.json")`; `st.load(default)`, `st.save(obj)` (атомарно), `st.save_async(obj)` | `save` — вне тиков |
| PDC | `d = amazake.data(игрок|сущность|ItemStack)`; `d.get/set/has/remove(k)` (JSON-значения), `d.get_string/set_string`; ключ без `:` → `py_<система>:k`, `"nexo:id"` — как есть | владельца |
| ключ | `amazake.key("k")` → `NamespacedKey` | |
| атрибуты игрока | `add_modifier(p, "max_health", "k", 4, "add")`, `remove_modifier(p, attr, "k")` — **временные, снимаются при выгрузке** | игрока |
| эффекты игрока | `add_effect(p, "speed", duration=100, amplifier=0, ambient=True, particles=False, icon=True)`, `remove_effect(p, "speed")` — **снимаются при выгрузке** | игрока |
| атрибуты предметов | `amazake.attribute("armor")`, `amazake.modifier("k", 8, "add", "chest")` → для `ItemMeta.addAttributeModifier` | |
| эффект по имени | `amazake.effect_type("slowness")` | |
| текст | `amazake.mm("<gold>Hi <n>", n="Rezus")` → Component, `item_text(...)` (без курсива), `send(target, text, **ph)`, `actionbar(p, text, **ph)` | |
| лог | `amazake.log.info/warn/error/debug(...)`; `print()` тоже уходит в лог | |
| Java | `import java; java.type("org.bukkit.Material")`; `amazake.java_class(name)` (ищет и в плагинах); `amazake.plugin_class("Nexo", "com.nexomc.nexo.api.NexoItems")` (или `None`); `java.instanceof(obj, Type)`; `amazake.Bukkit` | |
| сведения | `amazake.name`, `amazake.version`, `amazake.script_folder`, `amazake.data_folder()` | |

Модули своей системы импортируются как обычно (`import items` — файл `items.py` рядом с `main.py`).
Стандартная библиотека Python есть (json, re, random, math, dataclasses, …); C-расширений (numpy и т.п.) — нет.

### Правила, которые важно не нарушать

1. Игрок — только в его потоке; блоки/сущности — в потоке их региона; файлы и сеть — `run_async`.
2. На верхнем уровне модуля и в `@on_load` мир не трогать — только объявлять обработчики, читать конфиги,
   планировать задачи (`run_once_for_player` для каждого онлайн-игрока и т.п.).
3. Временные изменения игроков — через `add_modifier`/`add_effect` (их хост снимет сам). То, что вы поменяли
   напрямую Java-методами (`player.setHealth`, предметы, блоки), при выгрузке само не откатится — делайте это в
   `@on_unload` (там ещё можно звать Python, но только планировать задачи бессмысленно — контекст сейчас закроется).
4. Частые события не слушать (см. «Скорость»). Тяжёлое — в `run_async`, результат — обратно в нужный поток.
5. Не ждать в Python других потоков этой же системы (GIL — см. выше).

---

## Тесты

`./gradlew test` — 9 тестов на настоящем GraalPy (интерпретатор) с поддельной платформой (`FakePlatform`):
загрузка, события, команды с параметрами из manifest и автодополнением, конфиг/хранилище/задачи, 8 потоков в одном
контексте, выгрузка (всё снято, старый обработчик больше не зовёт Python), ошибки (лог с трассой, плохой модуль ничего
не регистрирует), 8 перезагрузок (куча не растёт), доступ к Java; `CustomArmorScriptTest` грузит
`scripts/custom_armor` из репозитория и проверяет его события, команду `/carmor` (+ `/防具`), логику бонусов.

Живые проверки на mc-test (JDK 25+37 — та же сборка, что на проде) — `/home/user/mc-bot/py1_armor.js`,
`py2_ignite.js`; результаты — в разделе custom_armor README и в итоговом отчёте.

## Конфиг (`config.yml`)

```yaml
libraries:
  auto-download: true                      # докачивать jar-ы GraalPy из Maven Central
  repository: "https://repo1.maven.org/maven2/"
graalpy:
  warn-interpreter-only: false             # предупреждение Truffle о режиме без JIT
```
