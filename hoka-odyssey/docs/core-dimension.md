# Правка ядра: тип мира карты (dimension)

Патч: `core-dimension.patch` (один коммит поверх `main` ядра HokaOdyssey, `33ce2ff`).

## Зачем

Модуль spacesky (космос) регистрирует свой тип мира `hoka:space` (DimensionType: небо, свет, Timeline) и хочет,
чтобы отдельные карты из `maps/` загружались с ним. Сейчас `Maps.load` всегда создаёт инстанс overworld,
и выбрать тип мира карте нельзя.

## Что меняется

1. **`Maps.load`** читает ключ `dimension` из `maps/<карта>/map.yml`:
   ```yaml
   generator: void
   dimension: "hoka:space"
   ```
   Ключ резолвится через `MinecraftServer.getDimensionTypeRegistry().getKey(...)`, инстанс создаётся
   `createInstanceContainer(RegistryKey<DimensionType>, AnvilLoader)`. Нет ключа — `minecraft:overworld`
   (как раньше). Неизвестный ключ (модуль не установлен, опечатка) — WARN в лог, карта открывается как overworld.
   `GameMap.dimension()` — тип, с которым карта загружена.
2. **`/homap create <имя> [flat|void] [тип мира]`** — необязательный третий аргумент (resource location,
   подсказки — все ключи реестра; `the_nether` дополняется до `minecraft:the_nether`) пишет `dimension` в `map.yml`.
   Неизвестный тип — ошибка, карта не создаётся. **`/homap list`** показывает тип мира, если он не overworld.
3. **Баг:** `/homap tp <карта> [ник]` на карту, где игрок уже стоит, падал с
   `IllegalArgumentException: Instance should be different than the current one` (`Player.setInstance`).
   Теперь в этом случае — просто `teleport` на спавн карты.
4. **Баг:** `GameMap.setSpawn` (`/homap setspawn`) писал `map.yml` целиком из копии, прочитанной при загрузке карты,
   и возвращал ключи, изменённые в файле после этого. Пример: `/hosky world off galaxy1` убирает
   `dimension: "hoka:space"` из `map.yml` загруженной карты, затем `/homap setspawn` на ней — ключ снова в файле.
   Теперь `setSpawn` сначала перечитывает файл (`meta.reload()`), потом пишет точку появления.
5. Тесты `MapsTest.dimensionFromMapYml` (запись/чтение `dimension`, неизвестный ключ → overworld) и
   `MapsTest.setSpawnKeepsFileChanges` (ключи, изменённые в файле после загрузки, не возвращаются), абзац в DEVELOPER.md.

`OdysseyServer.java` патч **не трогает**.

## Чего в патче нет (намеренно)

Основная карта `maps/world` по-прежнему загружается в `OdysseyServer.main` **до** `core.modules.loadAll()`,
поэтому для неё сейчас работают только ванильные типы мира: `dimension: "hoka:space"` в `maps/world/map.yml`
даст WARN и overworld. Перенос загрузки основной карты после включения модулей (лениво в обработчике
`AsyncPlayerConfigurationEvent` + явная загрузка после `loadAll()` и до `server.start`) отложен — в этом месте
сейчас работает другой администратор (смена режима игры), правка будет согласована отдельно.
Космос пока — отдельные карты (`/homap create space void hoka:space`).

## Подводные камни

- **Реестр замораживается** после `MinecraftServer.start` (`DynamicRegistryImpl.isFrozen`): модуль должен
  регистрировать тип мира в `onLoad`/`onEnable` до старта; при горячей загрузке модуля после старта карта с его
  типом откроется как overworld (WARN), нужен перезапуск.
- **Anvil не хранит тип мира, а высота задаётся им.** `AnvilLoader` молча пропускает секции вне
  `minSection..maxSection` нового типа, и при следующем сохранении они теряются. Пример: карта с постройками на
  y<0, переведённая на `minecraft:the_nether` (min_y=0, height=256), лишится всего ниже 0. Для `hoka:space`
  модуль задаёт те же `minY`/`height`, что у overworld (-64/384), — тогда менять тип у существующей карты безопасно.
  Ядро это не проверяет.

## Как применить

```bash
cd HokaOdyssey            # клон ядра, ветка main
git am /путь/к/core-dimension.patch
./gradlew :server:build :server:shadowJar   # JDK 25; тесты BlockRulesTest и MapsTest должны пройти
```

## Как проверить

1. Тесты: `./gradlew :server:test` — `MapsTest` (4 теста, в т.ч. `dimensionFromMapYml`, `setSpawnKeepsFileChanges`).
2. Вживую (так проверено, офлайн-копия OdysseyServer, бот mineflayer 1.21.11):
   - `maps/neth/map.yml` со строкой `dimension: "minecraft:the_nether"` → `/homap tp neth <ник>`: клиент получает
     пакет respawn с типом `minecraft:the_nether`, в логе `карта «neth» загружена (flat, minecraft:the_nether)`;
   - `dimension: "hoka:missing"` → WARN `тип мира «hoka:missing» не найден в реестре — загружаю как minecraft:overworld`,
     respawn с overworld;
   - повторный `/homap tp neth <ник>`, стоя на neth, — без исключения, игрок на спавне карты;
   - вход на основную карту и `/homap tp world <ник>` работают как раньше;
   - `/homap create n2 void minecraft:the_end` пишет `dimension: minecraft:the_end` в map.yml;
     `/homap create n3 flat hoka:nope` — ошибка со списком доступных типов.
