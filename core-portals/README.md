# core-portals

Система Amazake (Paper 1.21.11, форк; Java 21). Тестовый предмет **«Портал (смола)»**: ПКМ — перед игроком
открывается огненный портал 2×3. Сначала трескается земля и летят угли, затем тёмная рама с тонкими
красно-оранжевыми светящимися кромками поднимается из-под земли, а внутри раскрывается от средней линии
«небо»: закрученные фиолетово-синие облака, розовое свечение горизонта, дрейфующие искры, у основания —
языки пламени и тлеющие трещины. Кто вошёл в портал — уходит на варп (по умолчанию `/warp dungeon1`). В конце
срока жизни портал закрывается обратной анимацией и исчезает.

Портал собран из дисплеев (видны всем вокруг) и рисуется собственным core-шейдером из пакета ресурсов;
частицы и звуки — ванильные. Настоящие блоки никогда не ставятся и не ломаются.

## Установка

1. Сборка: `./gradlew azs test --no-daemon -q` → `build/libs/core-portals.azs`.
2. Файл в `systems/` сервера (через временный файл и `mv`), затем в консоли:
   `amazake system enable core-portals` (обновление — `amazake system restart core-portals`).
3. Пакет ресурсов: содержимое `pack/assets/` скопировать в `plugins/Nexo/pack/assets/`
   (`coreportals/…` целиком и `minecraft/shaders/core/…` — см. «Шейдер» ниже: файл ядра надо **слить** с
   хуком rezusmenu), затем `nexo reload pack` (игроки получат пакет заново).
4. По желанию: `item.model: "coreportals:portal_item"` в `systems/core-portals/config.yml` — своя иконка
   предмета (без пакета будет фиолетово-чёрный куб, поэтому по умолчанию пусто — обычная смола).

Без пакета система работает (анимация, вход, телепорт), но дисплеи будут без моделей.

## Команды и права

| Команда | Что делает |
|---|---|
| `/portals give [игрок] [кол-во]` | Выдать предмет-портал (себе или игроку; из консоли — с ником) |
| `/portals reload` | Перечитать `config.yml` |
| `/portals close` | Закрыть все открытые порталы (с анимацией) |
| `/portals list` | Список открытых порталов: владелец, мир, координаты, стадия |

Все — право `coreportals.admin` (у OP есть). Алиас: `/coreportals`. Сам предмет может использовать любой.

## Как работает

- **Предмет**: `RESIN_CLUMP` с именем «Портал» (градиент красный → оранжевый), описанием и меткой
  `coreportals:portal_item` в PDC (узнаётся по метке, не по имени). ПКМ по воздуху или блоку; смола на блок не
  ставится. В творческом режиме не тратится.
- **Место**: взгляд округляется до стороны света; портал встаёт в ряд блоков на `portal.distance` впереди,
  по сетке (2 столбца шириной), лицом к игроку. Уровень земли ищется от ног: 0, −1, +1, −2, +2… до
  `portal.ground-search`. Нужны твёрдая опора под обоими столбцами и свободный (проходимый, не лава) проём
  2×3. Нет места — сообщение `no-room`, предмет не тратится.
- **Анимация** (тики): 0–10 трещина и угли → 10–40 рама поднимается из-под земли (inOutCubic) → 40–60
  поверхность раскрывается от средней линии (outBack, лёгкий перелёт) → открыт → за 40 тиков до конца:
  поверхность схлопывается (15) и рама уходит в землю (25) → сущности убираются. Клиент интерполирует
  трансформацию дисплеев линейно, поэтому сервер шлёт ключевой кадр каждые 2 тика по кривой сглаживания.
- **Сущности**: 3 блок-дисплея (тёмная рама, `portal.frame-block`) + 4 item-дисплея с маркерными моделями
  (кромки, пламя, трещина, поверхность). Все в одной точке, не сохраняются в чанк, помечены id экземпляра
  системы в PDC (хвосты прошлого запуска убираются при включении рядом с игроками).
- **Вход**: каждый тик в потоке региона портала — игроки, чей хитбокс задевает объём поверхности
  (проём ±0,3 блока по нормали). Вспышка (FLASH, END_ROD, пламя, звук телепорта) → действие в потоке игрока.
  После входа `portal.immunity-seconds` портал игрока не трогает. С `entry: owner` чужих не пускает.
- **Потоки**: открытие — в событии игрока (место портала проверяется `Bukkit.isOwnedByCurrentRegion`),
  анимация, частицы, звуки, вход — задачей `Bukkit.getRegionScheduler()` (с `context.plugin()`) в регионе
  портала; команды действия — `runOnceForPlayer` (от игрока) или глобальный планировщик (от консоли).
  Планировщик сущностей не используется.
- **Уборка**: выгрузка чанка портала — портал убирается сразу; `/amazake system disable|restart` — все
  дисплеи убираются в `disable()`; рестарт сервера — дисплеи не сохраняются. Ничего не сохраняется на диск.

## Варп: почему действие «от игрока»

На проде `/warp` — модуль essentials системы AmazakeCore: команда `warp <имя>`, право
`amazakecore.essentials.warp`, только для игрока, который её вводит (из консоли — «только для игроков»,
указать другого игрока нельзя). Поэтому по умолчанию:

```yaml
teleport:
  run-as: player
  commands: ["warp dungeon1"]
  grant-permissions: [amazakecore.essentials.warp]   # выдаётся только на время выполнения команды
```

Игрок без права на `/warp` всё равно проходит портал, а права на другие варпы не получает (временное
`PermissionAttachment` снимается сразу после команды). Для другого способа телепорта можно, например,
`run-as: console` и `commands: ["tp {player} 100 64 100"]`.

## config.yml

| Ключ | По умолчанию | Что |
|---|---|---|
| `prefix` | `[Порталы]` | Префикс сообщений (MiniMessage) |
| `item.name`, `item.lore` | «Портал», 2 строки | Имя и описание предмета (MiniMessage) |
| `item.model` | `""` | item_model иконки (`coreportals:portal_item` с пакетом) |
| `item.consume` | `true` | Тратить предмет |
| `item.cooldown-seconds` | `5` | Задержка между открытиями одним игроком |
| `portal.lifetime-seconds` | `30` | Срок жизни от появления до исчезновения (мин. 6) |
| `portal.distance` | `3` | Рядов блоков перед игроком (1..6) |
| `portal.ground-search` | `3` | Поиск земли вверх/вниз (0..8) |
| `portal.max-per-player` | `1` | Порталов одного игрока одновременно (0 — без ограничения) |
| `portal.entry` | `everyone` | `everyone` или `owner` |
| `portal.immunity-seconds` | `3` | Неуязвимость к повторному входу |
| `portal.frame-block` | `POLISHED_BLACKSTONE` | Блок тёмной рамы |
| `portal.view-range` | `1.5` | Дальность видимости дисплеев (×64 блока) |
| `teleport.run-as` | `player` | `player` или `console` |
| `teleport.commands` | `["warp dungeon1"]` | Команды, `{player}`, `{uuid}` |
| `teleport.grant-permissions` | `[amazakecore.essentials.warp]` | Временные права для `run-as: player` |
| `effects.particles`, `effects.sounds` | `true` | Частицы и звуки |
| `messages.*` | — | Тексты (`entered` пусто — `/warp` пишет своё) |

Ошибки в значениях — предупреждение в консоли и значение по умолчанию.

## Шейдер

### Какой файл

Предмет в `ItemDisplay` с моделью из атласа предметов (`textures/item/…`) в 1.21.11 рисуется пайплайном
`pipeline/item_entity_translucent_cull` → шейдер **`minecraft:shaders/core/rendertype_item_entity_translucent_cull`**
(`.vsh` + `.fsh`; json для core-шейдеров в 1.21.11 нет — пайплайны в коде). База — ванильные файлы из клиента
1.21.11. Блок `Globals` (`GameTime`) клиент привязывает к любому core-шейдеру, который его объявляет, поэтому
время доступно и здесь.

### Маркерные цвета (зарезервировано за core-portals)

Цвет приходит через tint модели: `assets/coreportals/items/<id>.json` →
`"tints":[{"type":"minecraft:custom_model_data","index":0,"default":-1}]`, а у ItemStack в
`custom_model_data.colors` стоит маркер (ставит сервер, `Markers.java`). Любой другой цвет — не наш, вершина
рисуется как в ванили.

| RGB | Что |
|---|---|
| **(255, 64, 0)** | поверхность портала (небо) |
| **(255, 65, 0)** | вертикальная светящаяся кромка |
| **(255, 66, 0)** | языки пламени у основания |
| **(255, 67, 0)** | светящиеся трещины на земле |
| **(255, 68, 0)** | горизонтальная светящаяся кромка |
| (255, 69..95, 0) | запас core-portals, не занимать |

Для сравнения: rezusmenu использует (254, 0, 254) и UV2 = (240, 224) (брайтнес 15/14) — не пересекаются;
дисплеи порталов — брайтнес 15/15.

Положение внутри квада шейдер берёт из `gl_VertexID % 4`: вершины запечённой модели идут в порядке
`(u0,v0) (u0,v1) (u1,v1) (u1,v0)`, так что UV-угол известен без знания места спрайта в атласе.

### Хук (то, что нужно слить с хуком rezusmenu в один файл)

Вся логика — в `assets/coreportals/shaders/include/`: `portal_vertex.glsl`, `portal_fragment.glsl`,
`portal_noise.glsl` (все имена с префиксом `cp`/`cp_`). В файлах ядра — только эти блоки.

**`rendertype_item_entity_translucent_cull.vsh`** — после последнего `#moj_import`:

```glsl
// >>> coreportals hook (1/2): import >>>
#moj_import <coreportals:portal_vertex.glsl>
// <<< coreportals hook <<<
```

и **последней строкой `main()`** (после того, как `vertexColor` посчитан любой веткой):

```glsl
    // >>> coreportals hook (2/2): portal vertices are unlit, the fragment shader paints them >>>
    if (cp_vertex(Color, gl_VertexID)) vertexColor = vec4(1.0);
    // <<< coreportals hook <<<
```

`cp_vertex` всегда пишет выходы `cpKind`/`cpLocal`, поэтому вызов должен выполняться для каждой вершины
(не прятать его в `else`).

**`rendertype_item_entity_translucent_cull.fsh`** — после последнего `#moj_import`:

```glsl
// >>> coreportals hook (1/2): import >>>
#moj_import <coreportals:portal_fragment.glsl>
// <<< coreportals hook <<<
```

и **первыми строками `main()`**:

```glsl
    // >>> coreportals hook (2/2): animated portal surface / edges / flames / ground cracks >>>
    if (cpKind > 0) {
        vec4 cpColor = cp_fragment(cpKind, cpLocal);
        if (cpColor.a < 0.004) discard;
        fragColor = apply_fog(cpColor, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
        return;
    }
    // <<< coreportals hook <<<
```

### Слияние с rezusmenu (на сегодня)

rezusmenu кладёт свой `rendertype_item_entity_translucent_cull.vsh` (маркер курсора и ветка UV2 = (240, 224))
и **не** трогает `.fsh`. Итоговые файлы в `plugins/Nexo/pack/assets/minecraft/shaders/core/`:

- `.vsh` = файл rezusmenu + два блока core-portals выше (импорт после `#moj_import <minecraft:projection.glsl>`,
  вызов `cp_vertex` в самом конце `main()`, после их `if/else`);
- `.fsh` = файл core-portals как есть (ванильный + хук).

`assets/coreportals/…` и `assets/rezusmenu/…` копируются без изменений.

### Ограничения

- Шейдерпаки Iris/OptiFine заменяют core-шейдеры — там портал будет виден по запасным текстурам (без анимации).
- `GameTime` обнуляется раз в 20 минут игрового времени — анимация в этот момент перескакивает.
- Шейдер проверен компиляцией (glslang, include развёрнуты) и CPU-превью; на живом клиенте 1.21.11 вид
  нужно глянуть глазами.

## Файлы

```
src/main/java/core/amazake/systems/coreportals/
  CorePortalsSystem.java   включение, настройки
  PortalsCommand.java      /portals
  Settings.java, TeleportAction.java, PortalItem.java, Text.java
  geo/      Facing, Placement (чистая математика места), Spot, Box, BlockQuery
  anim/     Easing, Timeline (расписание анимации), Phase
  portal/   PortalManager (открытие, тик, вход, уборка), Portal, PortalVisuals (дисплеи),
            PortalEffects (частицы и звуки), Markers (маркерные цвета)
src/test/java/…            PlacementTest, TimelineTest, SettingsTest
pack/assets/coreportals/   items/, models/portal/, models/item/, textures/item/, shaders/include/
pack/assets/minecraft/shaders/core/rendertype_item_entity_translucent_cull.{vsh,fsh}
```
