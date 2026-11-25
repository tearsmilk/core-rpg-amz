# Добавка в мод (после 0.48.0): сдвиг модели в `EntityPose`

Одна маленькая добавка в клиентское ядро мода — для поворота тел игроков в невесомости (клиентский модуль
`spaceflight`). Обратно совместима: уже собранные модули и jar работают без изменений.

> **Про исходники.** На GitHub (`SakuraMesd/hokaodyssey`) лежат исходники ядра **0.25**, а на сервере — **0.48.0**.
> Файлы в `files/` восстановлены по байткоду 0.48.0 (`javap -c` jar с сервера) и исходникам 0.25; изменения в них
> помечены `// НОВОЕ`. **Влейте их в своё дерево 0.48, а не копируйте поверх** — если у вас там что-то иначе, верно
> ваше. И, пожалуйста, выложите на GitHub текущие исходники ядра — тогда добавки можно будет присылать готовым патчем.

## Зачем

Игрок в невесомости должен выглядеть так:

- **стоит на поверхности** (пол, стена, потолок) — повёрнут относительно ног: ноги на поверхности, верх тела по её
  нормали;
- **свободно в пространстве** — всё тело повёрнуто к прицелу (туда, куда он смотрит), поворот — вокруг середины тела.

В 0.48.0 для этого есть `onEntityPose` (`EntityPose(tilt, bodyYaw, headYaw, headPitch)`), и модуль им уже
пользуется. Но ядро поворачивает модель **только вокруг ног** (поворот `tilt` в начале `setupRotations`, начало
PoseStack — ноги сущности). А рамка (AABB) игрока всегда ровная (столкновения позу не видят — так и надо):

- свободно летящий игрок, повёрнутый «вниз головой», рисуется висящим над рамкой вверх ногами — поворот вокруг
  ступней, а не середины;
- игрок на стене стоит ногами **в середине своей рамки**, а не на стене: точка касания со стеной — сбоку рамки,
  а ноги модели — в её нижнем центре.

Ни поворотом, ни углами это не исправить — нужен сдвиг модели. Модуль считает его сам; ядру нужно только
сдвинуть модель перед поворотом.

## Изменение API (`ClientContext`)

Запись `EntityPose` получает пятое необязательное поле `offset` и конструктор с прежними четырьмя полями
(полный текст — `files/ClientContext-EntityPose.java`):

```java
record EntityPose(org.joml.Quaternionfc tilt, float bodyYaw, float headYaw, float headPitch,
                  org.joml.Vector3fc offset) {
    /** Как в 0.48.0 — без сдвига: старые модули (и уже собранные jar) работают без изменений. */
    public EntityPose(org.joml.Quaternionfc tilt, float bodyYaw, float headYaw, float headPitch) {
        this(tilt, bodyYaw, headYaw, headPitch, null);
    }
}
```

Смысл `offset`: сдвиг модели в **мировых** осях, в блоках, от позиции сущности (её ног) до точки, вокруг которой
модель поворачивается на `tilt` (ног модели после сдвига). `null` — как сейчас.

Почему совместимо: конструктор `(Quaternionfc, float, float, float)` и методы `tilt()`, `bodyYaw()`, `headYaw()`,
`headPitch()` остаются с теми же дескрипторами — модули, собранные против 0.48.0, грузятся как есть. Модуль
`spaceflight` ищет 5-аргументный конструктор рефлексией один раз: нет его — шлёт позу без сдвига (как сейчас).

## Код

### 1. `EntityTiltMixin` (`files/EntityTiltMixin.java`)

В 0.48.0 (по байткоду): `@Mixin(LivingEntityRenderer.class)`, `extractRenderState(...)` TAIL — если `Hooks.pose(e, pt)`
не null, ставит `s.bodyRot = bodyYaw`, `s.yRot = wrapDegrees(headYaw − bodyYaw)`, `s.xRot = headPitch` и кладёт
`tilt` в `RenderStateDataKey` `hoka_odyssey:tilt`; иначе — старый путь `Hooks.tilt` с переводом углов. В
`setupRotations` HEAD — `pose.mulPose(tilt)`.

Добавить второй ключ и сдвиг перед поворотом:

```java
@Unique private static final RenderStateDataKey<Vector3f> HOKA_OFFSET = RenderStateDataKey.create(() -> "hoka_odyssey:offset");

// extractRenderState, ветка pose != null — после setData(HOKA_TILT, ...):
s.setData(HOKA_OFFSET, pose.offset() == null ? null : new Vector3f(pose.offset()));
// ветка без pose (Hooks.tilt) — в самом начале:
s.setData(HOKA_OFFSET, null);

// setupRotations HEAD — до mulPose(tilt):
Vector3f off = s.getData(HOKA_OFFSET);
if (off != null && scale > 1e-6f) pose.translate(off.x / scale, off.y / scale, off.z / scale);
```

Деление на `scale`: в 1.21.11 `LivingEntityRenderer.submit` делает `poseStack.scale(s, s, s)` **до**
`setupRotations(state, poseStack, bodyRot, s)` (проверено по байткоду игры), а `offset` — в блоках мира. Порядок
«сдвиг, потом поворот» значит: точка модели p попадает в `ноги + offset + tilt·p`.

### 2. `HitboxTiltMixin` (`files/HitboxTiltMixin.java`)

В 0.48.0 (по байткоду) хитбокс F3+B повёрнут только для `Hooks.tilt` (`onEntityTilt` — его коробку считают
столкновения), поза `onEntityPose` не учитывается. Добавка: если поза задана **со сдвигом** — рисовать коробку
модели так же, как модель (сдвиг, потом поворот). Поза без сдвига — как в 0.48.0 (поведение для других модулей не
меняется):

```java
Quaternionfc t = null;
Vec3 feet = e.getPosition(pt);
var pose = Hooks.pose(e, pt);
if (pose != null && pose.tilt() != null && pose.offset() != null) {
    t = pose.tilt();
    feet = feet.add(pose.offset().x(), pose.offset().y(), pose.offset().z());
}
if (t == null) t = Hooks.tilt(e, pt);
if (t == null) return;
// дальше — как в 0.48.0 (углы коробки, линия глаз, стрелка взгляда от feet)
```

### 3. (Необязательно) `PickupTargetMixin` (`files/PickupTargetMixin.java`)

В 0.48.0 подбираемый предмет летит к `позиция + tilt·(0,1,0)·eye/2` своего игрока. Со сдвигом — прибавить
`offset`, иначе предмет летит к телу, повёрнутому вокруг ног, а не к показанному. Три строки.

Все три файла проверены компиляцией (`javac --release 21`) против Minecraft 1.21.11 (имена Mojang), Fabric API
(`fabric-rendering-v1`) и Mixin, с заглушками `Hooks`/`ClientContext`.

## Как проверить

1. Собрать мод с добавкой, на сервер — модуль `spaceflight` этой версии (пересобирать его под добавку не нужно —
   он находит её сам; в логе клиента: «тела игроков в невесомости поворачиваются (onEntityPose), со сдвигом модели
   (EntityPose.offset)»).
2. Два игрока в вакууме, оба с модом. Первый:
   - свободно летит и смотрит вниз/вверх/вбок, крутит крен (Z/C) — второй видит тело целиком повёрнутым к прицелу,
     середина тела остаётся в середине рамки (F3+B — белая коробка модели вокруг тела, а не вокруг ног);
   - Shift у стены — второй видит его стоящим ногами **на стене**, верх тела от стены, лицом по взгляду; на
     потолке — вниз головой ногами на потолке; на полу — как обычно;
   - R — лёжа: тело вдоль взгляда; на поверхности — лёжа на ней.
3. Свой игрок в F5 — то же самое.
4. Другие модули с `onEntityPose`/`onEntityTilt` (корабли и т.д.) — без изменений (они шлют позу без `offset`).

## Что уже есть в 0.48.0 (и было в `docs/mod-0.17-additions`)

`CameraState.rotation` (полный поворот камеры), `onInput` + `setMove`, `onLocalTravel`, `onEntityTilt`,
`onEntityPose`, `hideHand` — модуль `spaceflight` ими пользуется; документ `docs/mod-0.17-additions` устарел.
