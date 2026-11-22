> **Устарело.** Этот патч писался для ядра 0.9 → 0.10 и на мод 0.17 не накладывается. Актуальный список добавок — в [`../mod-0.17-additions/`](../mod-0.17-additions/README.md).

# Правка клиентского ядра: крючки неба, тумана, рендера мира и тел игроков (ядро 0.10.0)

Патч: `core-client-hooks.patch` — `git diff` поверх `main` ядра HokaOdyssey, коммит **`d081300`**
(«Руководство CONTENT.md…»). Меняет только `client/core/` (ядро-мод), сервер и модули не трогает.
Полные копии всех новых и изменённых файлов — в `files/` (те же пути, что в репозитории ядра).
Пример клиентского модуля, который пользуется всеми крючками, — `examples/hooks-demo/`.

## Зачем

Для «настоящего космоса» (модули нашего сервера: gravity, spacesky и новые клиентские модули) нужно то, чего
клиентский модуль сам сделать не может — миксины разрешены только ядру:

- **своё небо** — звёзды, туманности, планеты и чёрные дыры процедурно, шейдером, вместо ванильного неба;
- **туман** — чёрный и далёкий в космосе (цвет тумана — это ещё и цвет очистки кадра);
- **облака и дождь** — убрать в космосе;
- **поворот тел других игроков** — крен и «вверх ногами» (ванилла умеет только курс и наклон ±90°);
- **поза своего игрока** — «лёжа» в невесомости (хитбокс 0,6) без воды и элитр;
- служебное: проход рендера мира без утечки слушателей Fabric, свои RenderPipeline с шейдерами, переживающие
  F3+T, закрытие GPU-ресурсов при выгрузке модуля, событие смены мира, ввод и порядок физики своего игрока.

Ничего не включается само: пока ни один модуль не повесил крючок, ядро ведёт себя как 0.9.0
(каждый миксин сначала проверяет «есть ли крючки» — это одно сравнение со списком).

## Что меняется

Версия ядра: `0.9.0` → **`0.10.0`** (`client/core/build.gradle.kts`): у API новые методы.

### Новое в `ClientContext` (пакет `ink.hoka.odyssey.client.api`)

Всё снимается при выгрузке модуля (`Hooks.removeOwner`), как существующие крючки. Ошибка в крючке — модуль
выгружается (как сейчас), игра не падает.

| Метод | Что даёт |
|---|---|
| `onSky(SkyHook)` | `boolean render(SkyFrame f)` — свой проход кадра `hoka_sky` **перед** ванильным небом (основной буфер, глубина очищена, туман выставлен). `true` — ванильное небо в этом кадре не рисуется. `SkyFrame`: поворот камеры (с креном) и проекция, позиция камеры, partialTick, gameTime, **id мира** (`level.dimension().identifier()` — у Minestom свой у каждого мира), DimensionType, ванильные `SkyRenderState` и `SkyRenderer` (можно взять ванильное солнце/звёзды), UBO тумана, цвет и глубина кадра, `vanillaSkip` — почему ваниль неба не рисовала бы (NONE / FLUID / BLIND / NO_SKYBOX / BOSS_FOG). При активном шейдерпаке Iris крючок не вызывается. |
| `onFog(FogHook)` | `apply(FogState s, Camera c, float pt)` — цвет `r,g,b,a` и расстояния `envStart/envEnd, rdStart/rdEnd, skyEnd, cloudEnd` после расчёта игрой. Цвет меняет и цвет очистки кадра. |
| `onClouds(BooleanSupplier)` | Спрятать облака, пока supplier — `true`. |
| `onWeather(BooleanSupplier)` | Спрятать полосы дождя и снега (граница мира остаётся). |
| `onWorldPass(WorldStage, WorldPassHook)` | `START_MAIN`, `AFTER_ENTITIES`, `BEFORE_TRANSLUCENT`, `END_MAIN` — обёртка над `WorldRenderEvents`. Ядро регистрирует события Fabric один раз, модули — через свои списки (события Fabric не снимаются, и модуль, вешающий их сам, после выгрузки оставляет слушатель с закрытым ClassLoader). |
| `pipeline(RenderPipeline, ShaderSource)` → `ModulePipeline` | Свой pipeline со своими шейдерами-строками. `prepare()` перед каждым использованием: игра при F3+T/смене пака чистит кэш шейдеров и пересобирает только ванильные, `prepare()` соберёт заново (это `computeIfAbsent`, дёшево). `#moj_import <minecraft:…>` работает (препроцессор игры, include из ресурсов). После выгрузки модуля `prepare()` = false, программа и шейдеры модуля убраны из кэша игры и освобождены. |
| `track(AutoCloseable)` | Закрыть ресурс (GpuBuffer, GpuTexture…) при выгрузке модуля. |
| `onWorld(Consumer<Identifier>)` | Сразу (если в мире) и при каждой смене объекта мира: id измерения; `null` — мира не стало, а модуль ещё загружен (при отключении от сервера модули выгружаются раньше). |
| `onAvatarRotations(AvatarRotHook)` | `boolean apply(int entityId, AvatarRenderState s, PoseStack pose, float bodyRot, float scale)` в начале `AvatarRenderer.setupRotations`: другие игроки, сам игрок в F5, манекены. `true` — ванильный поворот не делать (модуль сам поворачивает, включая курс тела `rotateY(180 - bodyRot)`); `false` — ваниль применит свои повороты после модульных. |
| `onAvatarState(AvatarStateHook)` | `apply(Avatar e, AvatarRenderState s, float pt)` в конце `extractRenderState` — править поля состояния (поза, swimAmount, наклон, руки). |
| `forceLocalPose(Pose)` | Поза своего игрока вместо ванильного выбора (`SWIMMING` — лёжа, хитбокс 0,6, высота глаз 0,4); `null` — снять. Несколько модулей — действует последняя. |
| `onInput(InputHook)` | `apply(InputState in)` — клавиши (forward…sprint) и вектор движения после `KeyboardInput.tick`, до движения; сервер получает то, что модуль оставил в клавишах. |
| `onLocalTravel(TravelHook)` | `before(LocalPlayer p)` — в начале `LivingEntity.travel` своего игрока: тяга и гравитация в тот же тик (onTick — уже после движения). |

`CameraState` — новое поле `@Nullable Quaternionf rotation`: задано — камера берёт полный поворот кватернионом
(как `Camera.rotation()`) вместо yaw/pitch/roll; нет вырождения при наклоне ±90°, можно «вверх ногами».

Попутно исправлен javadoc: описание `onTick` стояло над `model()`.

### Миксины (добавлены в `hoka_odyssey.mixins.json`, `defaultRequire: 1`)

Все цели и дескрипторы проверены по классам 1.21.11 (Mojang-имена из mapped-jar Loom, javap), после сборки —
что Loom переложил каждую цель в intermediary (в jar ядра нет ни одного не найденного имени), и в игре —
`MixinEnvironment.audit()` без ошибок.

| Миксин | Цель (Mojang → intermediary) | Что делает |
|---|---|---|
| `LevelRendererSkyMixin` | `LevelRenderer.renderLevel` HEAD (`method_22710`) | запоминает камеру, вид, проекцию, туман кадра; сбрасывает флаг «небо забрано» |
| | `addSkyPass(FrameGraphBuilder, Camera, GpuBufferSlice)V` HEAD (`method_62200`) | если есть крючки неба — добавляет проход `hoka_sky` перед ванильным `sky` (`targets.main = pass.readsAndWrites(targets.main)`); ванильный проход не отменяется — совместимо с модами, которые в него вмешиваются |
| | `addMainPass(...)` HEAD (`method_62202`) | туман босса: ваниль не зовёт `addSkyPass` — проход `hoka_sky` добавляется здесь (`BOSS_FOG`) |
| | `addCloudsPass(FrameGraphBuilder, CloudStatus, Vec3, J, F, I, F)V` HEAD, cancellable (`method_62204`) | `onClouds` |
| `SkyRendererMixin` | `renderSkyDisc`, `renderDarkDisc`, `renderSunMoonAndStars`, `renderSunriseAndSunset`, `renderEndSky`, `renderEndFlash` HEAD, cancellable | модуль забрал небо — ванильные части не рисуются (флаг ставится после крючков, поэтому сам модуль может их звать) |
| `FogRendererMixin` | `FogRenderer.setupFog` HEAD + `@WrapOperation` на вызов `updateBuffer(ByteBuffer, I, Vector4f, FFFFFF)V` (`method_3211` / `method_71110`) | крючки тумана меняют значения перед записью в UBO; цвет правится в том же объекте `Vector4f`, который `setupFog` возвращает как цвет очистки. MixinExtras входит в Fabric Loader 0.19 |
| `WeatherEffectRendererMixin` | `WeatherEffectRenderer.render(MultiBufferSource, Vec3, WeatherRenderState)V` HEAD, cancellable (`method_62320`) | `onWeather`: проход `weather` целиком не отменяется — в нём же граница мира |
| `AvatarRendererMixin` | `AvatarRenderer.setupRotations(AvatarRenderState, PoseStack, FF)V` HEAD, cancellable (`method_4212`); `extractRenderState(Avatar, AvatarRenderState, F)V` TAIL (`method_62604`) | дескрипторы указаны явно — у класса есть мосты (bridge) с типами родителей |
| `PlayerPoseMixin` | `Player.updatePlayerPose()V` HEAD, cancellable (`method_7318`), только `LocalPlayer` | `forceLocalPose` |
| `KeyboardInputMixin` | `KeyboardInput.tick()V` TAIL (`method_3129`) | `onInput`; миксин наследуется от `ClientInput`, чтобы видеть `keyPresses` и `moveVector` |
| `LocalPlayerTravelMixin` | `LivingEntity.travel(Vec3)V` HEAD (`method_6091`), только `LocalPlayer` | `onLocalTravel` |
| `GlDeviceAccessor` | `GlDevice.pipelineCache`, `shaderCache` (`@Accessor`) | при выгрузке модуля убрать его pipeline и шейдеры из кэша игры (иначе обновлённый модуль с тем же id шейдера получил бы старый шейдер до F3+T) |
| `CameraMixin` (правка) | `Camera.setup` TAIL — как было | плюс `CameraState.rotation`: кладёт кватернион в `Camera.rotation` и пересчитывает `forwards/up/left` |

Новые классы ядра: `Pipelines` (pipeline модулей + препроцессор include), в `Hooks` — списки и вызовы новых
крючков, проверка шейдерпака Iris (рефлексией, `IrisApi.isShaderPackInUse`, без зависимости от Iris).
`HokaOdysseyClient` один раз регистрирует `WorldRenderEvents.START_MAIN / AFTER_ENTITIES / BEFORE_TRANSLUCENT / END_MAIN`.

## Совместимость

- **Старые модули** (под 0.9.0) работают без изменений: интерфейс только расширен.
- **Новый модуль на старом ядре**: при вызове нового метода будет `AbstractMethodError` (ядро поймает его и
  выгрузит модуль). Модуль проверяет ядро сам — **по имени метода**:
  ```java
  boolean newCore = Arrays.stream(ClientContext.class.getMethods()).anyMatch(m -> m.getName().equals("onSky"));
  if (!newCore) { /* старое ядро: обходной путь или ничего */ }
  ```
  (так делает `hooks-demo`). `ClientContext.class.getMethod("onSky", ClientContext.SkyHook.class)` не годится:
  на старом ядре нет и класса `SkyHook`, и вместо `NoSuchMethodException` вылетит `NoClassDefFoundError`.
- **Sodium**, по нашим данным, проход неба не трогает (не проверено в игре). **Iris с шейдерпаком** рисует небо сам — крючок неба тогда не вызывается
  (туман и тела игроков работают). Без шейдерпака Iris — как ваниль.
- Fabric API 0.141.6 миксует в `LevelRenderer.renderLevel` и в лямбду main-прохода, но не в `addSkyPass`,
  `addCloudsPass`, `SkyRenderer`, `FogRenderer` — наши инжекты с ними не пересекаются.

## Как применить

```bash
cd HokaOdyssey                 # клон ядра, main на d081300 (или новее, если client/core не менялся)
git apply --check core-client-hooks.patch
git apply core-client-hooks.patch
cd client
java -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain build --no-daemon --max-workers=1
# → core/build/libs/hoka-odyssey-0.10.0+1.21.11.jar
```
(`--max-workers=1` — только из-за ограничения частоты запросов Maven Central в нашей песочнице; JDK 25 как
toolchain, байткод Java 21 — как в текущей сборке.)

Пример модуля: скопировать `examples/hooks-demo` в `client/modules/hooks-demo` и добавить
`include("modules:hooks-demo")` в `client/settings.gradle.kts` — соберётся вместе с `hello` в `build/clientmods/`.

## Как проверено

1. **Сборка.** Патч наложен `git apply` на чистую копию `client/` из `d081300`, сборка той же командой —
   `BUILD SUCCESSFUL`, jar `hoka-odyssey-0.10.0+1.21.11.jar`. Отдельно собран `hooks-demo` против нового ядра.
2. **Цели миксинов.** Дескрипторы сверены `javap -s` по mapped-jar 1.21.11 из кэша Loom; в собранном jar все
   цели переложены в intermediary (`method_62200` = `addSkyPass`, `method_4212` = `setupRotations` и т.д.).
3. **В игре** (Fabric Loader 0.19.5 + Fabric API 0.141.6, Xvfb, программный GL): `MixinEnvironment.audit()` — без
   ошибок; на ванильном сервере 1.21.11 (плоский мир) загружен `hooks-demo`:
   - небо — градиент своим шейдером (`#moj_import <minecraft:globals.glsl>` разрешился), ванильного солнца и
     неба нет, туман того же цвета у горизонта (`img/sky-fog.png`);
   - тело игрока в F5 повёрнуто по крену крючком `onAvatarRotations` (`img/avatar-roll.png`);
   - в полёте клавиша бега переключает позу `SWIMMING` через `onInput` + `forceLocalPose` (`img/prone.png`);
   - `onWorld` («мир minecraft:overworld»), `onWorldPass(START_MAIN)`, `onLocalTravel` (счётчик в HUD) сработали.

4. **Выгрузка и обновление модуля** (там же, через настоящие `ModuleManager.load/unload`): после выгрузки
   `hooks-demo` все списки `Hooks` пусты, в кэше `GlDevice` не осталось ни его pipeline, ни шейдеров; затем
   загружен «обновлённый» `hooks-demo` с тем же id шейдера и другим цветом — небо нового цвета. На ядре без
   `GlDeviceAccessor` в той же проверке оставался старый шейдер (старый цвет) и копились pipeline в кэше.
5. **Старое ядро 0.9.0**: `hooks-demo` пишет «ядро без крючков неба» и ничего не делает; проверка через
   `getMethod("onSky", SkyHook.class)` на нём падала `NoClassDefFoundError`.

Не проверено в игре: скрытие дождя (на тестовом сервере не было дождя), `CameraState.rotation`, проход
`BOSS_FOG`, работа с Iris. Это проверит отдельный стенд с нашим сервером.

## Подводные камни для авторов модулей

- **Кэш шейдеров игры — по (id шейдера, тип, defines).** При выгрузке модуля ядро убирает его pipeline и шейдеры
  из кэша игры (`GlDeviceAccessor`), поэтому модуль, обновлённый с сервера, получает новый исходник и с тем же id.
  Но если исходник меняется на лету, без перезагрузки модуля, — дайте шейдеру новый id (с версией или хэшем),
  иначе возьмётся старый скомпилированный. id шейдеров — в своём пространстве имён, не `minecraft:` (кэш общий
  с ванилью).
- Свой исходник идёт мимо `ShaderManager`: `#moj_import` с относительным путём (`"…"`) не поддерживается, только
  `<ns:path>` из `assets/<ns>/shaders/include/`.
- Небо рисуется **до** террейна: полноэкранный треугольник, без теста и записи глубины; направление взгляда —
  из `inverse(projection * modelView)`.
- `forceLocalPose(SWIMMING)` опускает глаза до 0,4 — камеру модуль двигает сам (`onCamera`). Сервер позу
  своего игрока не видит: для других игроков позу ставит серверный модуль (meta swimming).
- `onAvatarRotations` с `false` — повороты модуля применяются **до** ванильного курса тела, т.е. в осях мира.
